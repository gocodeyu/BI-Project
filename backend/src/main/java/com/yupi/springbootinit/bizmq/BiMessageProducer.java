package com.yupi.springbootinit.bizmq;

import com.yupi.springbootinit.config.RabbitMqConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * BI 消息生产者
 * 实现消息持久化、生产者确认、事务后钩子机制
 */
@Component
@Slf4j
public class BiMessageProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送生成图表消息（事务同步版本）
     * 在数据库事务提交后才发送 MQ 消息，确保数据一致性
     * 
     * @param chartId 图表ID
     * @param isVip 是否是 VIP 用户
     */
    public void sendMessage(String chartId, boolean isVip) {
        // 判断当前是否在事务中
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 在事务中，注册事务后钩子，确保事务提交后才发送消息
            log.info("[MQ生产者] 检测到事务环境，注册事务后钩子 - chartId={}", chartId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 事务提交成功后，发送 MQ 消息
                    log.info("[MQ生产者] 事务提交成功，发送MQ消息 - chartId={}", chartId);
                    doSendMessage(chartId, isVip);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        // 事务回滚，不发送消息
                        log.warn("[MQ生产者] 事务回滚，取消发送MQ消息 - chartId={}", chartId);
                    }
                }
            });
        } else {
            // 不在事务中，直接发送消息
            log.info("[MQ生产者] 非事务环境，直接发送MQ消息 - chartId={}", chartId);
            doSendMessage(chartId, isVip);
        }
    }

    /**
     * 实际发送消息的方法
     * 实现消息持久化、生产者确认机制
     * 
     * @param chartId 图表ID
     * @param isVip 是否是 VIP 用户
     */
    private void doSendMessage(String chartId, boolean isVip) {
        try {
            // 1. 确定路由键
            String routingKey = isVip ? RabbitMqConfig.BI_VIP_ROUTING_KEY : RabbitMqConfig.BI_COMMON_ROUTING_KEY;
            
            // 2. 构建持久化消息
            MessageProperties messageProperties = new MessageProperties();
            // 设置消息持久化（关键：消息持久化到磁盘，RabbitMQ重启后不丢失）
            messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            // 设置消息内容类型
            messageProperties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
            messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
            
            // 3. 创建消息
            Message message = new Message(chartId.getBytes(StandardCharsets.UTF_8), messageProperties);
            
            // 4. 构建 CorrelationData（用于生产者确认回调）
            // correlationId 使用 chartId，方便在回调中识别是哪个图表的消息
            CorrelationData correlationData = new CorrelationData(chartId);
            
            // 5. 发送消息（携带 CorrelationData）
            rabbitTemplate.convertAndSend(
                RabbitMqConfig.BI_EXCHANGE_NAME, 
                routingKey, 
                message,
                correlationData
            );
            
            log.info("[MQ生产者] MQ消息发送成功 - chartId={}, exchange={}, routingKey={}, isVip={}, persistent=true", 
                chartId, RabbitMqConfig.BI_EXCHANGE_NAME, routingKey, isVip);
            
        } catch (Exception e) {
            // 发送失败，记录日志并抛出异常
            log.error("[MQ生产者] MQ消息发送失败 - chartId={}, isVip={}, error={}", chartId, isVip, e.getMessage(), e);
            // 抛出异常，让调用方感知失败
            throw new RuntimeException("消息发送失败: " + e.getMessage(), e);
        }
    }
}
