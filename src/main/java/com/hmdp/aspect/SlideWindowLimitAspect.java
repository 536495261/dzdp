package com.hmdp.aspect;

import cn.hutool.core.net.Ipv4Util;
import com.hmdp.annotation.SlideWindowLimit;
import com.hmdp.exception.SlideWindowLimitException;
import com.hmdp.utils.SlideWindowLimitUtil;
import com.hmdp.utils.UserHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;

@Component
@Aspect
public class SlideWindowLimitAspect {
    @Autowired
    private SlideWindowLimitUtil limitUtil;
    // 切点：匹配所有标记@SlideWindowLimit的方法
    @Pointcut("@annotation(com.hmdp.annotation.SlideWindowLimit)")
    public void limitPointcut() {}
    @Around("limitPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 获取注解信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        SlideWindowLimit annotation = method.getAnnotation(SlideWindowLimit.class);

        // 2. 解析限流维度，拼接唯一key
        String limitKey = buildLimitKey(annotation,joinPoint,method);

        // 3. 转换窗口大小为毫秒
        long windowSizeMs = annotation.timeUnit().toMillis(annotation.windowSize());

        // 4. 检查是否限流
        boolean isLimited = limitUtil.checkLimit(limitKey, windowSizeMs, annotation.maxCount(), annotation.timeUnit());
        if (isLimited) {
            // 限流触发，可自定义异常（如业务异常、返回JSON等）
            throw new SlideWindowLimitException(annotation.message());
        }

        // 5. 未限流，执行原方法
        return joinPoint.proceed();
    }
    /**
     * 根据维度拼接限流key（字符串类型）
     */
    private String buildLimitKey(SlideWindowLimit annotation,ProceedingJoinPoint joinPoint,Method method) {
        StringBuilder key = new StringBuilder(annotation.prefix());
        // 拼接方法唯一标识（避免不同方法key冲突）
        key.append(annotation.dimension().name())
                .append(":")
                .append(joinPoint.getTarget().getClass().getName()) // 替代StackTrace，更高效
                .append(":")
                .append(method.getName());

        // 根据维度拼接标识
        switch (annotation.dimension()) {
            case IP:
                // 获取客户端真实IP（兼容反向代理）
                String ip = getClientIp();
                key.append(":").append(ip);
                break;
            case USER:
                // 获取登录用户ID（替换为实际业务逻辑）
                Long userId = UserHolder.getUser().getId();
                if (userId == null) {
                    throw new RuntimeException("用户未登录，无法按用户维度限流！");
                }
                key.append(":").append(userId);
                break;
            case GLOBAL:
                // 全局维度无需额外拼接
                break;
        }
        return key.toString();
    }
    private String getClientIp() {
        // 步骤1：获取Spring MVC的请求上下文（ServletRequestAttributes）
        // RequestContextHolder是Spring提供的线程绑定工具，存储当前请求的上下文
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            // 非HTTP请求场景（如定时任务、内部调用），无请求上下文，返回默认值
            return "unknown";
        }

        // 步骤2：从上下文获取HttpServletRequest对象（封装了所有请求信息）
        HttpServletRequest request = attributes.getRequest();

        // 步骤3：优先从反向代理头获取真实IP（核心！解决Nginx/网关代理后的IP失真问题）
        // X-Forwarded-For：主流反向代理头（Nginx/HAProxy/CDN都会设置），记录客户端原始IP
        String ip = request.getHeader("X-Forwarded-For");

        // 兜底逻辑：如果X-Forwarded-For为空/unknown，依次尝试其他代理头
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            // Proxy-Client-IP：Apache 代理常用头
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            // WL-Proxy-Client-IP：WebLogic 代理常用头
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            // 终极兜底：获取请求的直接连接IP（若没有代理，就是客户端真实IP；有代理则是代理服务器IP）
            ip = request.getRemoteAddr();
        }

        // 步骤4：处理多IP场景（X-Forwarded-For可能包含多个IP，格式如：客户端IP, 代理1IP, 代理2IP）
        if (ip != null && ip.contains(",")) {
            // 按逗号分割，取第一个IP（客户端原始IP），并去除首尾空格
            ip = ip.split(",")[0].trim();
        }

        // 步骤5：IP格式优化（可选）
        // IpUtil.ipv4ToLong：将IPv4字符串（如127.0.0.1）转为长整型数字（如2130706433）
        // 好处：减少Redis存储体积（数字字符串比IP字符串更短），且查询效率更高
        return Ipv4Util.ipv4ToLong(ip) + "";
    }
}
