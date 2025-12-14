package com.hmdp.exception;

public class SlideWindowLimitException extends RuntimeException {
    // 限流异常专属状态码（建议与HTTP 429 Too Many Requests对应）
    private int code = 429;
    // 错误信息
    private String message;

    // 构造器1：仅传入错误信息（使用默认状态码429）
    public SlideWindowLimitException(String message) {
        super(message);
        this.message = message;
    }

    // 构造器2：自定义状态码+错误信息（灵活扩展）
    public SlideWindowLimitException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }
    // getter/setter
    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
