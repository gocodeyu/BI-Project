package com.yupi.springbootinit.manager;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiManager {

    @Value("${ai.qianfan.api-key}")
    private String apiKey;

    @Value("${ai.qianfan.url}")
    private String url;

    @Value("${ai.qianfan.model}")
    private String model;

    /**
     * 发送请求到阿里大模型（OpenAI 兼容接口）
     *
     * @param systemPrompt 系统预设（指定AI的角色和输出格式）
     * @param userMessage  用户具体内容
     * @return AI 的回答内容
     */
    public String doChat(String systemPrompt, String userMessage) {
        // 1. 构造 OpenAI 格式的请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("stream", false); // 关闭流式输出，方便解析

        List<Map<String, String>> messages = new ArrayList<>();

        // 添加系统预设 (OpenAI 协议支持 system 角色)
        if (StrUtil.isNotBlank(systemPrompt)) {
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);
        }

        // 添加用户消息
        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        requestBody.put("messages", messages);

        // 2. 发送 HTTP 请求
        // 格式参考：Authorization: Bearer <API_KEY>
        String jsonBody = JSONUtil.toJsonStr(requestBody);

        try (HttpResponse response = HttpRequest.post(url)
                .header(Header.CONTENT_TYPE, ContentType.JSON.getValue())
                .header(Header.AUTHORIZATION, "Bearer " + apiKey)
                .body(jsonBody)
                .execute()) {

            if (!response.isOk()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 接口响应异常: " + response.getStatus() + " " + response.body());
            }

            // 3. 解析响应
            String resultBody = response.body();
            JSONObject jsonObject = JSONUtil.parseObj(resultBody);

            // 检查是否有错误
            if (jsonObject.containsKey("error")) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 调用失败: " + jsonObject.getStr("error"));
            }

            // 提取 content: choices[0].message.content
            JSONArray choices = jsonObject.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 返回结果为空");
            }
            JSONObject choice = (JSONObject) choices.get(0);
            JSONObject message = choice.getJSONObject("message");
            return message.getStr("content");
        }
    }
}