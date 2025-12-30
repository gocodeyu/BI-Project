package com.yupi.springbootinit.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.LongSerializationPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 通知服务
 * 使用 Redis Pub/Sub 实现跨实例的消息推送
 *
 * @author yupi
 */
@Service
@Slf4j
public class SseNotifyService implements MessageListener {

    private static final String REDIS_CHANNEL = "topic:sse-notify";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisMessageListenerContainer redisMessageListenerContainer;

    // 修复：使用 LongSerializationPolicy.STRING 避免大数字精度丢失
    // 注意：不设置 disableHtmlEscaping，保持默认行为即可
    private final Gson gson = new GsonBuilder()
            .setLongSerializationPolicy(LongSerializationPolicy.STRING)
            .disableHtmlEscaping() // 禁用 HTML 转义，避免中文被转义
            .create();

    // 本机维护的 SSE 连接：userId -> SseEmitter
    private final Map<Long, SseEmitter> sseEmitterMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 订阅 Redis Channel
        redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(REDIS_CHANNEL));
        log.info("SSE 通知服务已启动，已订阅 Redis Channel: {}", REDIS_CHANNEL);
    }

    @PreDestroy
    public void destroy() {
        // 关闭所有 SSE 连接
        sseEmitterMap.values().forEach(emitter -> {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.error("关闭 SSE 连接失败", e);
            }
        });
        sseEmitterMap.clear();
    }

    /**
     * 注册 SSE 连接
     *
     * @param userId 用户ID
     * @param emitter SSE 发射器
     */
    public void registerSseConnection(Long userId, SseEmitter emitter) {
        // 如果已存在连接，先关闭旧的
        SseEmitter oldEmitter = sseEmitterMap.put(userId, emitter);
        if (oldEmitter != null) {
            try {
                oldEmitter.complete();
            } catch (Exception e) {
                log.warn("关闭旧的 SSE 连接失败", e);
            }
        }

        // 设置连接完成和超时回调
        emitter.onCompletion(() -> {
            log.info("【SSE】连接完成，移除: userId={}", userId);
            sseEmitterMap.remove(userId);
        });

        emitter.onTimeout(() -> {
            log.info("【SSE】连接超时，移除: userId={}", userId);
            sseEmitterMap.remove(userId);
            try {
                emitter.complete();
            } catch (Exception e) {
                log.warn("【SSE】完成超时的 SSE 连接失败", e);
            }
        });

        emitter.onError((ex) -> {
            log.error("【SSE】连接错误，移除: userId={}", userId, ex);
            sseEmitterMap.remove(userId);
            try {
                emitter.complete();
            } catch (Exception e) {
                log.warn("【SSE】完成错误的 SSE 连接失败", e);
            }
        });

        // 发送初始连接消息
        try {
            Map<String, String> connectedMessage = new java.util.HashMap<>();
            connectedMessage.put("type", "connected");
            connectedMessage.put("message", "SSE连接已建立");
            String jsonMessage = gson.toJson(connectedMessage);
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(jsonMessage, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.error("发送初始 SSE 消息失败", e);
        }

        log.info("【SSE】连接已注册: userId={}", userId);
    }

    /**
     * 移除 SSE 连接
     *
     * @param userId 用户ID
     */
    public void removeSseConnection(Long userId) {
        SseEmitter emitter = sseEmitterMap.remove(userId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.warn("完成 SSE 连接失败", e);
            }
        }
    }

    /**
     * 发布任务完成通知到 Redis Channel
     * 所有节点都会收到消息，但只有有对应用户连接的节点才会推送
     *
     * @param userId 用户ID
     * @param chartId 图表ID
     * @param status 状态（succeed/failed）
     * @param execMessage 执行消息
     */
    public void publishTaskNotification(Long userId, Long chartId, String status, String execMessage) {
        try {
            Map<String, Object> message = new java.util.HashMap<>();
            message.put("userId", userId);
            message.put("chartId", chartId);
            message.put("status", status);
            message.put("execMessage", execMessage);
            message.put("type", "chart_task_done");

            String messageJson = gson.toJson(message);
            stringRedisTemplate.convertAndSend(REDIS_CHANNEL, messageJson);
            log.info("【SSE】已发布任务通知到 Redis: userId={}, chartId={}, status={}", userId, chartId, status);
        } catch (Exception e) {
            log.error("【SSE】发布任务通知到 Redis 失败", e);
        }
    }

    /**
     * Redis Pub/Sub 消息监听器
     * 当收到 Redis 消息时，检查本机是否有对应用户的 SSE 连接，如果有则推送
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            // 使用 UTF-8 编码解析消息，避免中文乱码
            String messageBody = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            log.info("【SSE】收到 Redis Pub/Sub 消息: {}", messageBody);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = gson.fromJson(messageBody, Map.class);
            
            // 修复：处理 Number 类型，避免精度丢失
            // Gson 可能将大数字解析为 Double，导致精度丢失
            Long userId = parseLongFromObject(data.get("userId"));
            Long chartId = parseLongFromObject(data.get("chartId"));
            String status = (String) data.get("status");
            String execMessage = (String) data.get("execMessage");

            // 检查本机是否有该用户的 SSE 连接
            SseEmitter emitter = sseEmitterMap.get(userId);
            if (emitter != null) {
                // 构造推送消息
                Map<String, Object> pushMessage = new java.util.HashMap<>();
                pushMessage.put("type", "chart_task_done");
                pushMessage.put("chartId", chartId);
                pushMessage.put("status", status);
                pushMessage.put("message", execMessage);

                try {
                    // 将消息转为 JSON 字符串并使用 UTF-8 编码
                    String jsonMessage = gson.toJson(pushMessage);
                    emitter.send(SseEmitter.event()
                            .name("chart_task_done")
                            .data(jsonMessage, org.springframework.http.MediaType.APPLICATION_JSON));
                    log.info("【SSE】推送成功: userId={}, chartId={}, status={}", userId, chartId, status);
                } catch (IOException e) {
                    log.error("【SSE】推送失败，移除连接: userId={}", userId, e);
                    sseEmitterMap.remove(userId);
                    try {
                        emitter.complete();
                    } catch (Exception ex) {
                        log.warn("完成失败的 SSE 连接失败", ex);
                    }
                }
            } else {
                log.info("【SSE】本机无该用户的 SSE 连接，忽略: userId={}", userId);
            }
        } catch (Exception e) {
            log.error("【SSE】处理 Redis Pub/Sub 消息失败", e);
        }
    }

    /**
     * 从 Object 安全地解析为 Long
     * 处理 Gson 将大数字解析为字符串的情况
     */
    private Long parseLongFromObject(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String) {
            // Gson 使用 LongSerializationPolicy.STRING 后，Long 会被序列化为字符串
            return Long.parseLong((String) obj);
        }
        if (obj instanceof Number) {
            // 兼容处理：如果仍然是 Number 类型
            return ((Number) obj).longValue();
        }
        // 其他情况，尝试转为字符串再解析
        return Long.parseLong(obj.toString());
    }
}

