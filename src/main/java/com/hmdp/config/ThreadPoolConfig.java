package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池配置类（优化版）
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    // 缓存重建线程池参数（可配置）
    @Value("${thread-pool.cache-rebuild.core-size:5}")
    private int cacheRebuildCoreSize;

    @Value("${thread-pool.cache-rebuild.max-size:10}")
    private int cacheRebuildMaxSize;

    @Value("${thread-pool.cache-rebuild.queue-capacity:100}")
    private int cacheRebuildQueueCapacity;

    // 秒杀订单线程池参数（可配置）
    @Value("${thread-pool.seckill-order.core-size:3}")
    private int seckillOrderCoreSize;

    private ThreadPoolExecutor cacheRebuildExecutor;
    private ThreadPoolExecutor seckillOrderExecutor;

    /**
     * 缓存重建线程池
     * 场景：逻辑过期缓存重建
     * 特点：IO密集型（查询数据库）
     * 策略：CALLER_RUNS - 调用者线程执行，不丢失任务，自然限流
     */
    @Bean("cacheRebuildExecutor")
    public ThreadPoolExecutor cacheRebuildExecutor() {
        cacheRebuildExecutor = new ThreadPoolExecutor(
                cacheRebuildCoreSize,           // 核心线程数（可配置）
                cacheRebuildMaxSize,            // 最大线程数（可配置）
                60L,                            // 空闲线程存活时间
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(cacheRebuildQueueCapacity), // 有界队列，防止OOM
                new NamedThreadFactory("cache-rebuild"),
                new CustomRejectedExecutionHandler("缓存重建", RejectedStrategy.CALLER_RUNS)
        );
        log.info("缓存重建线程池初始化完成: core={}, max={}, queue={}", 
                cacheRebuildCoreSize, cacheRebuildMaxSize, cacheRebuildQueueCapacity);
        return cacheRebuildExecutor;
    }

    /**
     * 秒杀订单处理线程池
     * 场景：固定3个消费者，长期运行
     * 特点：while(true)循环，线程永不空闲
     * 策略：DISCARD_WITH_LOG - 丢弃但记录日志（实际不会触发）
     */
    @Bean("seckillOrderExecutor")
    public ThreadPoolExecutor seckillOrderExecutor() {
        seckillOrderExecutor = new ThreadPoolExecutor(
                seckillOrderCoreSize,           // 核心线程数（可配置）
                seckillOrderCoreSize,           // 最大线程数=核心线程数（优化）
                0L,                             // 空闲时间=0（线程永不空闲）
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),  // 队列容量=10（只会提交3个任务）
                new NamedThreadFactory("seckill-consumer"),
                new CustomRejectedExecutionHandler("秒杀订单", RejectedStrategy.DISCARD_WITH_LOG)
        );
        log.info("秒杀订单线程池初始化完成: core={}, max={}, queue={}", 
                seckillOrderCoreSize, seckillOrderCoreSize, 10);
        return seckillOrderExecutor;
    }

    /**
     * 优雅停机
     */
    @PreDestroy
    public void shutdown() {
        log.info("开始关闭线程池...");
        
        shutdownThreadPool(cacheRebuildExecutor, "缓存重建");
        shutdownThreadPool(seckillOrderExecutor, "秒杀订单");
        
        log.info("所有线程池已关闭");
    }

    /**
     * 优雅关闭线程池
     */
    private void shutdownThreadPool(ThreadPoolExecutor executor, String poolName) {
        if (executor == null) {
            return;
        }
        
        try {
            log.info("关闭[{}]线程池...", poolName);
            executor.shutdown();
            
            // 等待60秒
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("[{}]线程池未能在60秒内关闭，强制关闭", poolName);
                executor.shutdownNow();
                
                // 再等待30秒
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.error("[{}]线程池强制关闭失败", poolName);
                }
            }
            log.info("[{}]线程池已关闭", poolName);
        } catch (InterruptedException e) {
            log.error("[{}]线程池关闭被中断", poolName, e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 自定义线程工厂（线程安全）
     */
    static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setName(namePrefix + "-" + threadNumber.getAndIncrement());
            thread.setDaemon(false);  // 非守护线程
            thread.setPriority(Thread.NORM_PRIORITY);
            
            // 设置未捕获异常处理器
            thread.setUncaughtExceptionHandler((t, e) -> 
                log.error("线程[{}]发生未捕获异常", t.getName(), e)
            );
            
            return thread;
        }
    }

    /**
     * 自定义拒绝策略（增强版）
     * 相比默认策略的优势：
     * 1. 详细日志：记录线程池状态，方便排查
     * 2. 告警机制：可接入监控系统
     * 3. 异常保护：try-catch 防止任务执行失败影响主流程
     * 4. 灵活降级：可根据业务选择不同策略
     */
    @Slf4j
    static class CustomRejectedExecutionHandler implements RejectedExecutionHandler {
        private final String poolName;
        private final RejectedStrategy strategy;

        public CustomRejectedExecutionHandler(String poolName) {
            this(poolName, RejectedStrategy.CALLER_RUNS);
        }

        public CustomRejectedExecutionHandler(String poolName, RejectedStrategy strategy) {
            this.poolName = poolName;
            this.strategy = strategy;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            // 1. 记录详细日志（这是核心增强点）
            log.error("[{}线程池]任务被拒绝! 当前线程池状态: " +
                            "核心线程数={}, 最大线程数={}, 活跃线程数={}, " +
                            "队列大小={}, 队列剩余容量={}, 已完成任务数={}",
                    poolName,
                    executor.getCorePoolSize(),
                    executor.getMaximumPoolSize(),
                    executor.getActiveCount(),
                    executor.getQueue().size(),
                    executor.getQueue().remainingCapacity(),
                    executor.getCompletedTaskCount()
            );

            // 2. 告警（可以接入监控系统）
            // alertService.sendAlert("线程池任务被拒绝", poolName);

            // 3. 根据策略执行不同的降级方案
            if (executor.isShutdown()) {
                log.warn("[{}线程池]线程池已关闭，任务被丢弃", poolName);
                return;
            }

            switch (strategy) {
                case CALLER_RUNS:
                    // 调用者线程执行（不丢失任务，但会阻塞调用者）
                    handleCallerRuns(r);
                    break;
                case DISCARD_OLDEST:
                    // 丢弃最老的任务，重试当前任务
                    handleDiscardOldest(r, executor);
                    break;
                case RETRY:
                    // 重试（适合短暂的流量峰值）
                    handleRetry(r, executor);
                    break;
                case DISCARD_WITH_LOG:
                    // 丢弃任务但记录日志（适合可以丢失的任务）
                    handleDiscardWithLog(r);
                    break;
                case ABORT:
                default:
                    // 抛异常（让调用者感知）
                    throw new RejectedExecutionException(
                            String.format("[%s线程池]任务被拒绝", poolName));
            }
        }

        /**
         * 策略1：调用者线程执行（增强版 CallerRunsPolicy）
         */
        private void handleCallerRuns(Runnable r) {
            try {
                log.warn("[{}线程池]使用调用者线程执行任务", poolName);
                r.run();
            } catch (Exception e) {
                log.error("[{}线程池]调用者线程执行任务失败", poolName, e);
            }
        }

        /**
         * 策略2：丢弃最老的任务（增强版 DiscardOldestPolicy）
         */
        private void handleDiscardOldest(Runnable r, ThreadPoolExecutor executor) {
            Runnable discarded = executor.getQueue().poll();
            if (discarded != null) {
                log.warn("[{}线程池]丢弃最老的任务: {}", poolName, discarded);
            }
            try {
                executor.execute(r);
            } catch (RejectedExecutionException e) {
                log.error("[{}线程池]重试失败，任务被丢弃", poolName);
            }
        }

        /**
         * 策略3：重试（适合短暂峰值）
         */
        private void handleRetry(Runnable r, ThreadPoolExecutor executor) {
            int retryCount = 3;
            for (int i = 0; i < retryCount; i++) {
                try {
                    Thread.sleep(50); // 等待50ms
                    executor.execute(r);
                    log.info("[{}线程池]重试第{}次成功", poolName, i + 1);
                    return;
                } catch (RejectedExecutionException | InterruptedException e) {
                    log.warn("[{}线程池]重试第{}次失败", poolName, i + 1);
                }
            }
            log.error("[{}线程池]重试{}次后仍失败，任务被丢弃", poolName, retryCount);
        }

        /**
         * 策略4：丢弃但记录日志（增强版 DiscardPolicy）
         */
        private void handleDiscardWithLog(Runnable r) {
            log.warn("[{}线程池]任务被丢弃: {}", poolName, r);
        }
    }

    /**
     * 拒绝策略枚举
     */
    enum RejectedStrategy {
        CALLER_RUNS,      // 调用者线程执行
        DISCARD_OLDEST,   // 丢弃最老的任务
        RETRY,            // 重试
        DISCARD_WITH_LOG, // 丢弃但记录日志
        ABORT             // 抛异常
    }
}
