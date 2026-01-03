package com.yupi.springbootinit.bizmq;

import com.rabbitmq.client.Channel;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.config.RabbitMqConfig;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

@Component
@Slf4j
public class BiMessageConsumer {

    @Resource
    private ChartService chartService;
    @Resource
    private com.yupi.springbootinit.service.BiAsyncService biAsyncService;

    /**
     * 自定义异常：用于标记需要重试的场景
     */
    private static class RetryableException extends RuntimeException {
        public RetryableException(String message, Throwable cause) {
            super(message, cause);
        }
        public RetryableException(String message) {
            super(message);
        }
    }

    /**
     * 监听 VIP 队列
     */
    @RabbitListener(queues = RabbitMqConfig.BI_VIP_QUEUE_NAME, concurrency = "5-10", ackMode = "MANUAL")
    public void receiveVipMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            log.info("[MQ消费者] VIP消费者收到消息 - message={}, deliveryTag={}", message, deliveryTag);
            processMessage(message);
            // 成功处理，手动 Ack
            channel.basicAck(deliveryTag, false);
            log.info("[MQ消费者] VIP消息处理成功，已确认 - message={}, deliveryTag={}", message, deliveryTag);
        } catch (RetryableException e) {
            // 1. 捕获可重试异常：抛出异常，让 Spring AMQP 自动重试
            log.warn("[MQ消费者] VIP队列出现可重试异常，等待Spring重试 - message={}, error={}", message, e.getMessage());
            throw e;
        } catch (Exception e) {
            // 2. 捕获不可重试异常（或未知致命错误）：直接拒绝，不重回队列（进入死信）
            log.error("[MQ消费者] VIP队列出现不可重试异常，拒绝消息进入死信 - message={}, error={}", message, e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 监听普通队列
     */
    @RabbitListener(queues = RabbitMqConfig.BI_COMMON_QUEUE_NAME, concurrency = "1-2", ackMode = "MANUAL")
    public void receiveCommonMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            log.info("[MQ消费者] 普通消费者收到消息 - message={}, deliveryTag={}", message, deliveryTag);
            processMessage(message);
            channel.basicAck(deliveryTag, false);
            log.info("[MQ消费者] 普通消息处理成功，已确认 - message={}, deliveryTag={}", message, deliveryTag);
        } catch (RetryableException e) {
            log.warn("[MQ消费者] 普通队列出现可重试异常，等待Spring重试 - message={}, error={}", message, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[MQ消费者] 普通队列出现不可重试异常，拒绝消息进入死信 - message={}, error={}", message, e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 核心业务处理逻辑（提取出来通用）
     * 该方法负责区分异常类型，将异常包装为 RetryableException 或 BusinessException
     */
    private void processMessage(String message) {
        // --- 1. 参数校验（不可重试） ---
        if (StringUtils.isBlank(message)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息为空");
        }
        long chartId = Long.parseLong(message);
        
        // --- 2. 幂等性检查：查询图表状态（提前检查，避免无效调用） ---
        Chart chart = chartService.getById(chartId);
        if (chart == null) {
            log.warn("[MQ消费者] 图表不存在，确认消息 - chartId={}", chartId);
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表不存在");
        }

        // --- 3. 状态检查：只有 WAIT 状态才处理（幂等性保证） ---
        String currentStatus = chart.getStatus();
        if (!"wait".equals(currentStatus)) {
            log.info("[MQ消费者] 图表状态不是WAIT，跳过处理（幂等性保证） - chartId={}, status={}", chartId, currentStatus);
            // 直接返回，不抛出异常（避免进入重试或死信队列）
            // 这是一种幂等性处理：已处理过的消息直接确认
            return;
        }

        // --- 4. 调用 BiAsyncService 来处理完整的图表生成流程 ---
        // 该服务包含: 状态更新（CAS）、AI调用、结果保存、缓存删除、SSE通知等完整流程
        // 注意：BiAsyncService 内部也有幂等性检查（双重保障）
        log.info("[MQ消费者] 开始处理图表生成任务 - chartId={}", chartId);
        try {
            biAsyncService.executeGenChart(chartId);
            log.info("[MQ消费者] 图表生成任务处理完成 - chartId={}", chartId);
        } catch (BusinessException e) {
            // 业务异常（不可重试）
            log.error("[MQ消费者] 图表生成业务异常（不可重试） - chartId={}, error={}", chartId, e.getMessage());
            throw e;
        } catch (Exception e) {
            // 其他未知异常，视为可重试
            log.error("[MQ消费者] 图表生成过程出现异常（可重试） - chartId={}, error={}", chartId, e.getMessage(), e);
            throw new RetryableException("图表生成异常", e);
        }
    }
}