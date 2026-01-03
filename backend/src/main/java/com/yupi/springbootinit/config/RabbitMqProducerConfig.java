package com.yupi.springbootinit.config;

import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.enums.GenChartStatusEnum;
import com.yupi.springbootinit.service.ChartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * RabbitMQ 生产者配置
 * 实现生产者确认机制（Publisher Confirms）和消息返回机制（Publisher Returns）
 */
@Configuration
@Slf4j
public class RabbitMqProducerConfig implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private ChartService chartService;

    /**
     * 初始化：设置 RabbitTemplate 的回调
     */
    @PostConstruct
    public void init() {
        // 设置确认回调：消息是否到达 Exchange
        rabbitTemplate.setConfirmCallback(this);
        // 设置返回回调：消息是否从 Exchange 路由到 Queue
        rabbitTemplate.setReturnsCallback(this);
    }

    /**
     * 确认回调：消息是否成功到达 Exchange
     * 
     * @param correlationData 相关数据（可以携带业务信息，如 chartId）
     * @param ack true=消息到达Exchange，false=消息未到达Exchange
     * @param cause ack=false时的失败原因
     */
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (ack) {
            // 消息成功到达 Exchange
            log.info("[MQ确认回调] 消息成功到达Exchange - correlationId={}", 
                correlationData != null ? correlationData.getId() : "null");
        } else {
            // 消息未到达 Exchange（网络故障、Exchange不存在等）
            log.error("[MQ确认回调] 消息发送失败，未到达Exchange - correlationId={}, cause={}", 
                correlationData != null ? correlationData.getId() : "null", cause);
            
            // 处理发送失败的情况
            handleSendFailure(correlationData, cause);
        }
    }

    /**
     * 返回回调：消息从 Exchange 无法路由到 Queue 时触发
     * 
     * @param returnedMessage 返回的消息信息
     */
    @Override
    public void returnedMessage(ReturnedMessage returnedMessage) {
        log.error("[MQ返回回调] 消息路由失败，从Exchange返回 - exchange={}, routingKey={}, replyCode={}, replyText={}, message={}", 
            returnedMessage.getExchange(),
            returnedMessage.getRoutingKey(),
            returnedMessage.getReplyCode(),
            returnedMessage.getReplyText(),
            new String(returnedMessage.getMessage().getBody()));
        
        // 处理路由失败的情况
        handleRoutingFailure(returnedMessage);
    }

    /**
     * 处理消息发送失败（未到达Exchange）
     * 异步更新数据库状态为失败
     */
    @Async
    protected void handleSendFailure(CorrelationData correlationData, String cause) {
        if (correlationData == null || correlationData.getId() == null) {
            log.warn("[MQ确认回调] 无法处理发送失败，correlationData为空");
            return;
        }
        
        try {
            // 从 correlationId 中解析 chartId（格式：chartId）
            String correlationId = correlationData.getId();
            Long chartId = Long.parseLong(correlationId);
            
            // 查询图表是否存在
            Chart chart = chartService.getById(chartId);
            if (chart == null) {
                log.warn("[MQ确认回调] 图表不存在，无法更新状态 - chartId={}", chartId);
                return;
            }
            
            // 只有状态为 wait 的图表才更新为失败（避免覆盖已处理的状态）
            if ("wait".equals(chart.getStatus())) {
                Chart updateChart = new Chart();
                updateChart.setId(chartId);
                updateChart.setStatus(GenChartStatusEnum.FAILED.getValue());
                updateChart.setExecMessage("消息发送失败，未到达消息队列: " + cause);
                chartService.updateById(updateChart);
                log.info("[MQ确认回调] 已更新图表状态为失败 - chartId={}", chartId);
            } else {
                log.info("[MQ确认回调] 图表状态不是wait，跳过更新 - chartId={}, status={}", chartId, chart.getStatus());
            }
            
            // TODO: 发送告警通知（邮件、短信、钉钉等）
            sendAlert("消息发送失败", "chartId=" + chartId + ", cause=" + cause);
            
        } catch (Exception e) {
            log.error("[MQ确认回调] 处理发送失败异常", e);
        }
    }

    /**
     * 处理消息路由失败（Exchange无法路由到Queue）
     * 异步更新数据库状态为失败
     */
    @Async
    protected void handleRoutingFailure(ReturnedMessage returnedMessage) {
        try {
            // 从消息体中解析 chartId
            String message = new String(returnedMessage.getMessage().getBody());
            Long chartId = Long.parseLong(message);
            
            // 查询图表是否存在
            Chart chart = chartService.getById(chartId);
            if (chart == null) {
                log.warn("[MQ返回回调] 图表不存在，无法更新状态 - chartId={}", chartId);
                return;
            }
            
            // 只有状态为 wait 的图表才更新为失败
            if ("wait".equals(chart.getStatus())) {
                Chart updateChart = new Chart();
                updateChart.setId(chartId);
                updateChart.setStatus(GenChartStatusEnum.FAILED.getValue());
                updateChart.setExecMessage("消息路由失败: " + returnedMessage.getReplyText());
                chartService.updateById(updateChart);
                log.info("[MQ返回回调] 已更新图表状态为失败 - chartId={}", chartId);
            } else {
                log.info("[MQ返回回调] 图表状态不是wait，跳过更新 - chartId={}, status={}", chartId, chart.getStatus());
            }
            
            // TODO: 发送告警通知
            sendAlert("消息路由失败", "chartId=" + chartId + ", replyText=" + returnedMessage.getReplyText());
            
        } catch (Exception e) {
            log.error("[MQ返回回调] 处理路由失败异常", e);
        }
    }

    /**
     * 发送告警通知（示例方法，需要根据实际情况实现）
     * 
     * @param title 告警标题
     * @param content 告警内容
     */
    private void sendAlert(String title, String content) {
        // TODO: 实现告警逻辑
        // 可以集成：
        // 1. 邮件服务（JavaMailSender）
        // 2. 短信服务（阿里云短信）
        // 3. 钉钉机器人
        // 4. 企业微信机器人
        // 5. 自定义告警系统
        log.warn("[告警] {}: {}", title, content);
    }
}

