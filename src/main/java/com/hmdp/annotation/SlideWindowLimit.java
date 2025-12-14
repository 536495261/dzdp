package com.hmdp.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;


@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SlideWindowLimit {
    /**
     * 限流key前缀（默认：limit:slide:）
     */
    String prefix() default "limit:slide";
    /**
     * 限流维度（全局/IP/用户）
     */
    LimitDimension dimension() default LimitDimension.GLOBAL;
    /**
     * 时间窗口大小（默认1毫秒）
     */
    long windowSize() default 1000;
    /**
     * 时间单位（默认毫秒）
     */
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;
    /**
     * 窗口内最大请求数（默认10次）
     */
    long maxCount() default 10;
    /**
     * 限流提示语
     */
    String message() default "请求过于频繁，请稍后再试！";
}

