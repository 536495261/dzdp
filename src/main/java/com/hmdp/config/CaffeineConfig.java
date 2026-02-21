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
     * 配置：最大容量1000个，过期时间5分钟（比Redis的30分钟短）
     * 原因：本地缓存过期后可以从Redis重新加载，避免两级缓存同时失效
     */
    @Bean(name = "shopCache")
    public Cache<String, Object> shopCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000) // 最大缓存数量
                .expireAfterWrite(5, TimeUnit.MINUTES) // 5分钟过期，比Redis短
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
