package com.hmdp.utils;

import cn.hutool.core.util.IdUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Component
public class SlideWindowLimitUtil {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 加载 Lua 脚本
    private static final DefaultRedisScript<Long> LIMIT_SCRIPT;

    static {
        LIMIT_SCRIPT = new DefaultRedisScript<>();
        LIMIT_SCRIPT.setLocation(new ClassPathResource("slide_window_limit.lua"));
        LIMIT_SCRIPT.setResultType(Long.class);
    }

    /**
     * 检查是否被限流
     * @return true-被限流，false-放行
     */
    public boolean checkLimit(String key, long windowSize, long maxCount, TimeUnit timeUnit) {
        long now = System.currentTimeMillis();
        long windowSizeMs = timeUnit.toMillis(windowSize);
        String requestId = IdUtil.simpleUUID();

        // 执行 Lua 脚本（原子操作）
        Long result = stringRedisTemplate.execute(
                LIMIT_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(windowSizeMs),
                String.valueOf(maxCount),
                String.valueOf(now),
                requestId
        );

        // 返回 0 表示被限流，1 表示放行
        return result == null || result == 0;
    }
}
