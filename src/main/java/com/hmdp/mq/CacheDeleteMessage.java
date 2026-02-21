package com.hmdp.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 缓存删除消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheDeleteMessage {
    /**
     * 缓存key
     */
    private String cacheKey;
    /**
     * 重试次数
     */
    private int retryCount;
    /**
     * 最大重试次数
     */
    public static final int MAX_RETRY = 3;
}
