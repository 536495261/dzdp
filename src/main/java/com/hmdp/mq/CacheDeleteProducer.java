package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 缓存删除消息生产者
 */
@Slf4j
@Component
public class CacheDeleteProducer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public static final String STREAM_KEY = "stream:cache:delete";

    /**
     * 发送缓存删除消息
     */
    public void sendDeleteMessage(String cacheKey) {
        CacheDeleteMessage message = new CacheDeleteMessage(cacheKey, 0);
        sendMessage(message);
    }

    /**
     * 发送重试消息
     */
    public void sendRetryMessage(CacheDeleteMessage message) {
        message.setRetryCount(message.getRetryCount() + 1);
        sendMessage(message);
    }

    private void sendMessage(CacheDeleteMessage message) {
        try {
            ObjectRecord<String, String> record = StreamRecords.newRecord()
                    .ofObject(JSONUtil.toJsonStr(message))
                    .withStreamKey(STREAM_KEY);

            RecordId recordId = stringRedisTemplate.opsForStream().add(record);
            log.info("发送缓存删除消息成功, key={}, recordId={}, retryCount={}",
                    message.getCacheKey(), recordId, message.getRetryCount());
        } catch (Exception e) {
            log.error("发送缓存删除消息失败, key={}", message.getCacheKey(), e);
        }
    }
}
