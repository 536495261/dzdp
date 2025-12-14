package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    @Qualifier("commonCache")
    private Cache<String, Object> commonCache;
    @Autowired
    @Qualifier("shopCache")
    private Cache<String, Object> shopCache;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long timeout, TimeUnit unit){
        // 更新Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), timeout, unit);
        // 更新本地缓存
        updateLocalCache(key, value, timeout, unit);
    }
    public void setWithExpire(String key, Object value, Long timeout, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(timeout)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public <R,ID> R queryWithPassThrough(String prefix, ID id, Class<R> type, Function<ID,R> dbFallBack,
                                         Long timeout, TimeUnit unit){
        // 从本地缓存查询
        String key = prefix + id;
        R localCache = getLocalCache(key, type);
        if(localCache != null) {
            return localCache;
        }

        // 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 如果存在，直接返回
        if (StrUtil.isNotBlank(json)) {
            // 更新本地缓存
            R r = JSONUtil.toBean(json, type);
            updateLocalCache(key, r, timeout, unit);
            return r;
        }
        if(json != null){
            // 缓存穿透，空值也存入本地缓存
            updateLocalCache(key, null, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        R r = dbFallBack.apply(id);
        if(r == null){
            // 防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,"", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 空值也存入本地缓存
            updateLocalCache(key, null, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        this.set(key, r, timeout, unit);
        return r;
    }
    public <R,ID> R queryWithLogicalExpire(String prefix, ID id, Class<R> type, Function<ID,R> dbFallBack,
                                           Long timeout, TimeUnit unit){
        // 从本地缓存查询
        String key = prefix + id;
        R localCache = getLocalCache(key, type);
        if(localCache != null) {
            return localCache;
        }

        // 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 如果不存在，直接返回null
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        // 检查过期时间
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 未过期，更新本地缓存并返回
            updateLocalCache(key, r, timeout, unit);
            return r;
        }

        // 已过期，需要缓存重建
        // 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        // 未获取到互斥锁，返回旧数据
        if (isLock) {
            // 双重检查
            json = stringRedisTemplate.opsForValue().get(key);
            if (!StrUtil.isBlank(json)) {
                redisData = JSONUtil.toBean(json, RedisData.class);
                r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
                if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    updateLocalCache(key, r, timeout, unit);
                    unlock(lockKey);
                    return r;
                }
            }

            // 异步重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallBack.apply(id);
                    this.setWithExpire(key, r1, timeout, unit);
                    // 更新本地缓存
                    updateLocalCache(key, r1, timeout, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 无论是否成功，都需要释放互斥锁
                    unlock(lockKey);
                }
            });
        }

        // 返回过期数据
        return r;
    }
    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    // 释放互斥锁
    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 根据key获取本地缓存
     */
    private <R> R getLocalCache(String key, Class<R> type) {
        // 根据业务选择合适的缓存
        Cache<String, Object> cache = key.startsWith(CACHE_SHOP_KEY) ? shopCache : commonCache;
        Object value = cache.getIfPresent(key);

        if(value == null) {
            return null;
        }

        // 检查Redis缓存是否存在，确保数据一致性
        String redisValue = stringRedisTemplate.opsForValue().get(key);
        if (redisValue == null) {
            // Redis缓存不存在，清除本地缓存
            deleteLocalCache(key);
            return null;
        }

        if(value instanceof String) {
            if("" .equals(value)) {
                return null;
            }
            return JSONUtil.toBean((String)value, type);
        }

        return type.cast(value);
    }

    /**
     * 更新本地缓存
     */
    private void updateLocalCache(String key, Object value, Long timeout, TimeUnit unit) {
        if(value == null) {
            value = "";
        }

        // 根据业务选择合适的缓存
        Cache<String, Object> cache = key.startsWith(CACHE_SHOP_KEY) ? shopCache : commonCache;
        // 注意：Caffeine缓存的过期时间由缓存配置统一管理（在CaffeineConfig中配置）
        // 这里我们依赖Caffeine的自动过期机制，与Redis缓存保持相同的过期时间策略
        cache.put(key, value);
    }

    /**
     * 删除本地缓存
     */
    private void deleteLocalCache(String key) {
        if(key.startsWith(CACHE_SHOP_KEY)) {
            shopCache.invalidate(key);
        } else {
            commonCache.invalidate(key);
        }
    }

    /**
     * 删除缓存（同时删除Redis和本地缓存）
     */
    public void delete(String key) {
        // 删除Redis缓存
        stringRedisTemplate.delete(key);
        // 删除本地缓存
        deleteLocalCache(key);
    }
    public <R,ID> R queryWithMutex(String prefix, ID id, Class<R> type, Function<ID,R> dbFallBack,
                                   Long timeout, TimeUnit unit){
        // 从本地缓存查询
        String key = prefix + id;
        R localCache = getLocalCache(key, type);
        if(localCache != null) {
            log.info("从本地缓存查询到数据，key: {}", key);
            return localCache;
        }

        // 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 如果存在，直接返回
        if (StrUtil.isNotBlank(json)) {
            R r = JSONUtil.toBean(json, type);
            // 更新本地缓存
            updateLocalCache(key, r, timeout, unit);
            return r;
        }

        if(json != null){
            // 缓存穿透，空值也存入本地缓存
            updateLocalCache(key, null, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 实现缓存重建
        // 获取互斥锁
        R r = null;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;

        try {
            boolean isLock = tryLock(lockKey);
            // 未获取到互斥锁，休眠并重试
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(prefix, id, type, dbFallBack, timeout, unit);
            }

            // 二次检查本地缓存
            localCache = getLocalCache(key, type);
            if(localCache != null) {
                return localCache;
            }

            // 二次检查Redis
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                R redisR = JSONUtil.toBean(json, type);
                updateLocalCache(key, redisR, timeout, unit);
                return redisR;
            }

            if(json != null){
                updateLocalCache(key, null, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 模拟重建延迟
            Thread.sleep(200);
            r = dbFallBack.apply(id);
            if(r == null){
                // 防止缓存穿透
                stringRedisTemplate.opsForValue().set(key,"", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 空值也存入本地缓存
                updateLocalCache(key, null, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 写入Redis和本地缓存
            this.set(key, r, timeout, unit);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(lockKey);
        }

        return r;
    }




}
