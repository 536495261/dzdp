package com.hmdp.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池监控（优化版）
 */
@Slf4j
@Component
public class ThreadPoolMonitor {

    @Resource
    @Qualifier("cacheRebuildExecutor")
    private ThreadPoolExecutor cacheRebuildExecutor;

    @Resource
    @Qualifier("seckillOrderExecutor")
    private ThreadPoolExecutor seckillOrderExecutor;

    /**
     * 每分钟打印线程池状态
     */
    @Scheduled(cron = "0 * * * * ?")
    public void monitorThreadPool() {
        logThreadPoolStatus("缓存重建", cacheRebuildExecutor);
        logThreadPoolStatus("秒杀订单", seckillOrderExecutor);
    }

    /**
     * 记录线程池状态并告警
     */
    private void logThreadPoolStatus(String poolName, ThreadPoolExecutor executor) {
        if (executor == null || executor.isShutdown()) {
            return;
        }

        int queueSize = executor.getQueue().size();
        int queueCapacity = queueSize + executor.getQueue().remainingCapacity();
        int activeCount = executor.getActiveCount();
        int poolSize = executor.getPoolSize();
        int corePoolSize = executor.getCorePoolSize();
        int maximumPoolSize = executor.getMaximumPoolSize();
        long completedTaskCount = executor.getCompletedTaskCount();
        long taskCount = executor.getTaskCount();

        // 计算使用率
        double queueUsage = queueCapacity > 0 ? (double) queueSize / queueCapacity : 0;
        double threadUsage = maximumPoolSize > 0 ? (double) activeCount / maximumPoolSize : 0;

        log.info("[{}线程池] 核心={}, 最大={}, 当前={}, 活跃={}, " +
                        "队列={}/{} ({}%), 已完成={}, 总任务={}",
                poolName,
                corePoolSize,
                maximumPoolSize,
                poolSize,
                activeCount,
                queueSize,
                queueCapacity,
                String.format("%.1f", queueUsage * 100),
                completedTaskCount,
                taskCount
        );

        // 告警：队列使用率超过80%
        if (queueUsage > 0.8) {
            log.warn("[{}线程池]队列使用率过高: {}%, 可能需要扩容", 
                    poolName, String.format("%.1f", queueUsage * 100));
        }

        // 告警：线程使用率超过90%
        if (threadUsage > 0.9) {
            log.warn("[{}线程池]线程使用率过高: {}%, 可能需要扩容", 
                    poolName, String.format("%.1f", threadUsage * 100));
        }

        // 告警：活跃线程数 = 最大线程数（说明线程池已满负荷）
        if (activeCount == maximumPoolSize && queueSize > 0) {
            log.error("[{}线程池]已满负荷运行! 活跃线程={}, 队列积压={}", 
                    poolName, activeCount, queueSize);
        }
    }
}
