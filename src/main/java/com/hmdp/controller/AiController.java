package com.hmdp.controller;

import com.hmdp.ai.DashScopeService;
import com.hmdp.dto.Result;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * AI店铺查询接口
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private DashScopeService dashScopeService;

    /**
     * AI对话接口
     * @param message 用户消息
     * @return AI回复
     */
    @PostMapping("/chat")
    public Result chat(@RequestParam("message") String message) {
        String response = dashScopeService.chat(message);
        return Result.ok(response);
    }

    /**
     * GET方式的对话接口（方便测试）
     */
    @GetMapping("/chat")
    public Result chatGet(@RequestParam("message") String message) {
        String response = dashScopeService.chat(message);
        return Result.ok(response);
    }
}
