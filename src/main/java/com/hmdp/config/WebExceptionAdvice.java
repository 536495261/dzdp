package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.exception.SlideWindowLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {
    // 处理自定义限流异常
    @ExceptionHandler(SlideWindowLimitException.class)
    public Result handleSlideWindowLimitException(SlideWindowLimitException e) {
        log.error(e.toString(), e);
        return Result.fail(e.getMessage());
    }

    // 处理其他运行时异常
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
