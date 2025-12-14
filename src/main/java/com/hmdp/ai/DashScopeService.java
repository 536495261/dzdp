package com.hmdp.ai;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DashScopeService {

    @Value("${langchain4j.openai.api-key}")
    private String apiKey;

    @Value("${langchain4j.openai.model-name:qwen-plus}")
    private String modelName;

    @Resource
    private ShopTools shopTools;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    // Redis key前缀
    private static final String MEMORY_KEY_PREFIX = "ai:chat:memory:";
    // 会话过期时间（30分钟）
    private static final long MEMORY_TTL_MINUTES = 30;
    // 最大记忆轮数
    private static final int MAX_MEMORY_ROUNDS = 10;

    /**
     * 无会话的对话（不保留记忆）
     */
    public String chat(String userMessage) {
        return chat("default", userMessage);
    }

    /**
     * 带会话ID的对话（保留记忆到Redis）
     */
    public String chat(String sessionId, String userMessage) {
        log.info("会话[{}] 用户提问: {}", sessionId, userMessage);

        String redisKey = MEMORY_KEY_PREFIX + sessionId;

        // 从Redis获取历史消息
        JSONArray history = getHistory(redisKey);

        // 添加用户消息
        history.add(JSONUtil.createObj().set("role", "user").set("content", userMessage));

        // 构建请求
        JSONObject requestBody = buildRequest(history);

        try {
            String response = HttpRequest.post(API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(requestBody.toString())
                    .timeout(60000)
                    .execute()
                    .body();

            log.debug("API响应: {}", response);
            JSONObject result = JSONUtil.parseObj(response);

            if (result.containsKey("error")) {
                log.error("API错误: {}", result.getStr("error"));
                return "AI服务暂时不可用: " + result.getJSONObject("error").getStr("message");
            }

            JSONObject choice = result.getJSONArray("choices").getJSONObject(0);
            JSONObject message = choice.getJSONObject("message");

            String reply;
            if (message.containsKey("tool_calls") && !message.getJSONArray("tool_calls").isEmpty()) {
                reply = handleToolCalls(sessionId, userMessage, message);
            } else {
                reply = message.getStr("content");
            }

            // 保存AI回复到历史
            history.add(JSONUtil.createObj().set("role", "assistant").set("content", reply));

            // 保存到Redis
            saveHistory(redisKey, history);

            log.info("会话[{}] AI回复: {}", sessionId, reply);
            return reply;

        } catch (Exception e) {
            log.error("调用AI失败", e);
            return "AI服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 从Redis获取历史消息
     */
    private JSONArray getHistory(String redisKey) {
        String historyJson = stringRedisTemplate.opsForValue().get(redisKey);
        if (historyJson == null || historyJson.isEmpty()) {
            return JSONUtil.createArray();
        }
        return JSONUtil.parseArray(historyJson);
    }

    /**
     * 保存历史消息到Redis
     */
    private void saveHistory(String redisKey, JSONArray history) {
        // 限制历史消息数量
        int maxMessages = MAX_MEMORY_ROUNDS * 2;
        while (history.size() > maxMessages) {
            history.remove(0);
        }
        // 保存并设置过期时间
        stringRedisTemplate.opsForValue().set(redisKey, history.toString(), MEMORY_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 清除会话记忆
     */
    public void clearMemory(String sessionId) {
        stringRedisTemplate.delete(MEMORY_KEY_PREFIX + sessionId);
        log.info("已清除会话[{}]的记忆", sessionId);
    }

    private JSONObject buildRequest(JSONArray history) {
        JSONObject request = JSONUtil.createObj();
        request.set("model", modelName);

        JSONArray messages = JSONUtil.createArray();
        messages.add(JSONUtil.createObj()
                .set("role", "system")
                .set("content", "你是一个店铺查询助手。当用户询问店铺信息时，必须调用提供的工具函数获取数据，不要编造数据。"));

        // 添加历史消息
        for (int i = 0; i < history.size(); i++) {
            messages.add(history.getJSONObject(i));
        }
        request.set("messages", messages);
        request.set("tools", buildTools());

        return request;
    }


    private JSONArray buildTools() {
        JSONArray tools = JSONUtil.createArray();

        tools.add(buildTool("getTopRatedShops", "查询评分最高的店铺",
                JSONUtil.createObj()
                        .set("type", "object")
                        .set("properties", JSONUtil.createObj()
                                .set("limit", JSONUtil.createObj()
                                        .set("type", "integer")
                                        .set("description", "要查询的店铺数量，默认5个")))
                        .set("required", JSONUtil.createArray())));

        tools.add(buildTool("searchShopByName", "根据店铺名称模糊搜索店铺",
                JSONUtil.createObj()
                        .set("type", "object")
                        .set("properties", JSONUtil.createObj()
                                .set("name", JSONUtil.createObj()
                                        .set("type", "string")
                                        .set("description", "店铺名称关键词")))
                        .set("required", JSONUtil.createArray().put("name"))));

        tools.add(buildTool("getShopsByType", "根据店铺类型查询店铺列表，typeId: 1美食 2KTV 3酒店 4文化 5运动 6美发",
                JSONUtil.createObj()
                        .set("type", "object")
                        .set("properties", JSONUtil.createObj()
                                .set("typeId", JSONUtil.createObj()
                                        .set("type", "integer")
                                        .set("description", "店铺类型ID")))
                        .set("required", JSONUtil.createArray().put("typeId"))));

        tools.add(buildTool("getShopById", "根据店铺ID查询店铺详细信息",
                JSONUtil.createObj()
                        .set("type", "object")
                        .set("properties", JSONUtil.createObj()
                                .set("shopId", JSONUtil.createObj()
                                        .set("type", "integer")
                                        .set("description", "店铺ID")))
                        .set("required", JSONUtil.createArray().put("shopId"))));

        tools.add(buildTool("getShopsByArea", "根据商圈/区域查询店铺",
                JSONUtil.createObj()
                        .set("type", "object")
                        .set("properties", JSONUtil.createObj()
                                .set("area", JSONUtil.createObj()
                                        .set("type", "string")
                                        .set("description", "商圈名称")))
                        .set("required", JSONUtil.createArray().put("area"))));

        return tools;
    }

    private JSONObject buildTool(String name, String description, JSONObject parameters) {
        return JSONUtil.createObj()
                .set("type", "function")
                .set("function", JSONUtil.createObj()
                        .set("name", name)
                        .set("description", description)
                        .set("parameters", parameters));
    }

    private String handleToolCalls(String sessionId, String userMessage, JSONObject message) {
        JSONArray toolCalls = message.getJSONArray("tool_calls");
        StringBuilder toolResults = new StringBuilder();

        for (int i = 0; i < toolCalls.size(); i++) {
            JSONObject toolCall = toolCalls.getJSONObject(i);
            JSONObject function = toolCall.getJSONObject("function");
            String functionName = function.getStr("name");
            String arguments = function.getStr("arguments");

            log.info("会话[{}] AI调用工具: {} 参数: {}", sessionId, functionName, arguments);
            String result = executeFunction(functionName, arguments);
            toolResults.append(result);
        }

        return generateFinalResponse(userMessage, toolResults.toString());
    }

    private String executeFunction(String functionName, String arguments) {
        JSONObject args = JSONUtil.parseObj(arguments);

        switch (functionName) {
            case "getTopRatedShops":
                return shopTools.getTopRatedShops(args.getInt("limit"));
            case "searchShopByName":
                return shopTools.searchShopByName(args.getStr("name"));
            case "getShopsByType":
                return shopTools.getShopsByType(args.getLong("typeId"));
            case "getShopById":
                return shopTools.getShopById(args.getLong("shopId"));
            case "getShopsByArea":
                return shopTools.getShopsByArea(args.getStr("area"));
            default:
                return "未知的工具: " + functionName;
        }
    }

    private String generateFinalResponse(String userMessage, String toolResult) {
        JSONObject request = JSONUtil.createObj();
        request.set("model", modelName);

        JSONArray messages = JSONUtil.createArray();
        messages.add(JSONUtil.createObj()
                .set("role", "system")
                .set("content", "你是一个店铺查询助手，根据查询结果用友好的语气回复用户。"));
        messages.add(JSONUtil.createObj()
                .set("role", "user")
                .set("content", userMessage));
        messages.add(JSONUtil.createObj()
                .set("role", "assistant")
                .set("content", "我查询到以下店铺信息：\n" + toolResult));
        messages.add(JSONUtil.createObj()
                .set("role", "user")
                .set("content", "请根据上面的查询结果，用友好的语气回复我。"));
        request.set("messages", messages);

        try {
            String response = HttpRequest.post(API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(request.toString())
                    .timeout(60000)
                    .execute()
                    .body();

            JSONObject result = JSONUtil.parseObj(response);
            String reply = result.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getStr("content");

            log.info("AI最终回复: {}", reply);
            return reply;
        } catch (Exception e) {
            log.error("生成最终回复失败", e);
            return toolResult;
        }
    }
}
