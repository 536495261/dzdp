package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import com.hmdp.utils.RedisConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine本地缓存配置类
 */
@Configuration
public class CaffeineConfig {

    /**
     * 店铺详情本地缓存
     * 配置：最大容量1000个，过期时间与Redis一致(30分钟)
     */
    @Bean(name = "shopCache")
    public Cache<String, Object> shopCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000) // 最大缓存数量
                .expireAfterWrite(RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES) // 与Redis保持一致的过期时间
                .recordStats() // 开启统计
                .build();
    }

    /**
     * 通用本地缓存
     * 配置：最大容量500个，过期时间5分钟
     */
    @Bean(name = "commonCache")
    public Cache<String, Object> commonCache() {
        return Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }
}
