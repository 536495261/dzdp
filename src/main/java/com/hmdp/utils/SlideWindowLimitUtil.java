package com.hmdp.utils;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import javafx.beans.binding.ObjectExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class SlideWindowLimitUtil {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    public boolean checkLimit(String key,long windowSize,long maxCount,TimeUnit timeUnit){
        long now = System.currentTimeMillis();
        // 将windowSize转换为毫秒单位
        long windowSizeMs = timeUnit.toMillis(windowSize);
        long max = now - windowSizeMs;
        ZSetOperations<String, String> zSetOperations = stringRedisTemplate.opsForZSet();
        // 删除时间窗口外的数据
        zSetOperations.removeRangeByScore(key,0,(double)max);
        Long count = zSetOperations.size(key);
        if(count != null && count >= maxCount){
            return true;
        }
        // 添加当前请求记录
        zSetOperations.add(key, IdUtil.simpleUUID(),(double)now);
        // 设置过期时间，比时间窗口大1秒，确保窗口内的数据都能被正确清理
        stringRedisTemplate.expire(key, windowSizeMs + 1000, TimeUnit.MILLISECONDS);
        return false;
    }
}
