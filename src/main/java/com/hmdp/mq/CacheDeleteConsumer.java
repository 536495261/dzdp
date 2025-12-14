package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 缓存删除消息消费者
 */
@Slf4j
@Component
public class CacheDeleteConsumer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheDeleteProducer cacheDeleteProducer;

    private static final String STREAM_KEY = CacheDeleteProducer.STREAM_KEY;
    private static final String GROUP_NAME = "cache-delete-group";
    private static final String CONSUMER_NAME = "consumer-1";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        // 创建消费者组（如果不存在）
        createConsumerGroup();
        // 启动消费者线程
        executor.submit(this::consumeMessages);
        log.info("缓存删除消费者启动成功");
    }

    @PreDestroy
    public void destroy() {
        running = false;
        executor.shutdown();
        log.info("缓存删除消费者已停止");
    }

    private void createConsumerGroup() {
        try {
            // 先检查Stream是否存在，不存在则创建
            Boolean hasKey = stringRedisTemplate.hasKey(STREAM_KEY);
            if (!Boolean.TRUE.equals(hasKey)) {
                // 创建Stream并添加一条初始消息（会自动创建Stream）
                stringRedisTemplate.opsForStream().add(STREAM_KEY, java.util.Collections.singletonMap("init", "init"));
                log.info("创建Stream成功: {}", STREAM_KEY);
            }

            // 创建消费者组，使用 $ 表示只消费创建组之后的新消息
            // 这样初始化消息不会被消费
            stringRedisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.latest(), GROUP_NAME);
            log.info("创建消费者组成功: {}", GROUP_NAME);
        } catch (Exception e) {
            // 组已存在会抛异常，忽略即可
            log.debug("消费者组已存在或创建失败: {} - {}", GROUP_NAME, e.getMessage());
        }
    }

    private void consumeMessages() {
        while (running) {
            try {
                // 读取消息
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );

                if (records == null || records.isEmpty()) {
                    continue;
                }

                // 处理消息
                for (MapRecord<String, Object, Object> record : records) {
                    handleMessage(record);
                }
            } catch (Exception e) {
                log.error("消费消息异常", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void handleMessage(MapRecord<String, Object, Object> record) {
        String messageJson = record.getValue().values().iterator().next().toString();

        // 跳过初始化消息
        if ("init".equals(messageJson)) {
            stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
            log.debug("跳过初始化消息");
            return;
        }

        CacheDeleteMessage message = JSONUtil.toBean(messageJson, CacheDeleteMessage.class);

        // 跳过无效消息
        if (message == null || message.getCacheKey() == null) {
            stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
            log.warn("跳过无效消息: {}", messageJson);
            return;
        }

        log.info("收到缓存删除消息: key={}, retryCount={}", message.getCacheKey(), message.getRetryCount());

        try {
            // 尝试删除缓存
            Boolean deleted = stringRedisTemplate.delete(message.getCacheKey());

            if (Boolean.TRUE.equals(deleted)) {
                log.info("缓存删除成功: {}", message.getCacheKey());
            } else {
                log.info("缓存不存在或已删除: {}", message.getCacheKey());
            }

            // 确认消息
            stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());

        } catch (Exception e) {
            log.error("删除缓存失败: {}", message.getCacheKey(), e);

            // 判断是否需要重试
            if (message.getRetryCount() < CacheDeleteMessage.MAX_RETRY) {
                // 发送重试消息
                cacheDeleteProducer.sendRetryMessage(message);
                log.info("已发送重试消息, key={}, retryCount={}", message.getCacheKey(), message.getRetryCount() + 1);
            } else {
                log.error("缓存删除重试次数已达上限, key={}, 依赖TTL兜底", message.getCacheKey());
            }

            // 确认原消息（避免重复消费）
            stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
        }
    }
}
