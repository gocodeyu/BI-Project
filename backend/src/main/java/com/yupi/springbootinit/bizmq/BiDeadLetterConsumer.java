package com.yupi.springbootinit.bizmq;

import com.rabbitmq.client.Channel;
import com.yupi.springbootinit.config.RabbitMqConfig;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.enums.GenChartStatusEnum;
import com.yupi.springbootinit.service.ChartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * BI 死信队列消费者
 * 处理重试失败的消息，实现兜底机制
 */
@Component
@Slf4j
public class BiDeadLetterConsumer {

    @Resource
    private ChartService chartService;

    @Resource
    private com.yupi.springbootinit.service.cache.ChartCacheService chartCacheService;

    @Resource
    private com.yupi.springbootinit.service.cache.ChartListCacheService chartListCacheService;

    @Resource
    private com.yupi.springbootinit.service.cache.ChartDataCacheService chartDataCacheService;

    /**
     * 监听死信队列
     * 重试次数耗尽后的消息会进入死信队列
     * 
     * @param message 消息内容（chartId）
     * @param channel RabbitMQ 通道
     * @param deliveryTag 消息投递标签
     */
    @RabbitListener(queues = RabbitMqConfig.BI_DL_QUEUE_NAME, ackMode = "MANUAL")
    public void receiveDlMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.error("========== 死信队列收到消息 ==========");
        log.error("死信消息内容: {}", message);
        log.error("处理原因: AI生成失败，系统重试次数已耗尽");
        log.error("========================================");
        
        try {
            // 1. 参数校验
            if (message == null || message.trim().isEmpty()) {
                log.warn("死信消息为空，直接确认");
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 2. 解析 chartId
            long chartId;
            try {
                chartId = Long.parseLong(message.trim());
            } catch (NumberFormatException e) {
                log.error("死信消息格式错误，无法解析chartId: {}", message);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3. 查询图表是否存在
            Chart chart = chartService.getById(chartId);
            if (chart == null) {
                log.warn("图表不存在，直接确认死信消息: chartId={}", chartId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 4. 更新数据库状态为 Failed
            Chart updateChart = new Chart();
            updateChart.setId(chartId);
            updateChart.setStatus(GenChartStatusEnum.FAILED.getValue());
            updateChart.setExecMessage("AI生成失败，系统重试次数已耗尽或请求超时，请稍后重试");
            boolean updateResult = chartService.updateById(updateChart);
            
            if (updateResult) {
                log.info("成功更新图表状态为失败: chartId={}", chartId);
                
                // 5. 清理缓存（确保前端能立即看到失败状态）
                evictChartCache(chartId, chart.getUserId());
                log.info("已清理图表缓存: chartId={}, userId={}", chartId, chart.getUserId());
            } else {
                log.error("更新图表状态失败: chartId={}", chartId);
            }

            // 6. 发送告警通知
            sendAlert(chartId, chart.getName(), chart.getUserId());

            // 7. 确认死信消息（从死信队列移除）
            channel.basicAck(deliveryTag, false);
            log.info("死信消息处理完成并确认: chartId={}", chartId);
            
        } catch (Exception e) {
            log.error("死信消息处理异常: message={}", message, e);
            try {
                // 发生异常也要确认消息，避免死信队列堆积
                channel.basicAck(deliveryTag, false);
                log.warn("异常情况下已确认死信消息: message={}", message);
            } catch (Exception ackException) {
                log.error("死信队列 Ack 失败", ackException);
            }
        }
    }

    /**
     * 清理图表相关的所有缓存
     * 
     * @param chartId 图表ID
     * @param userId 用户ID
     */
    private void evictChartCache(Long chartId, Long userId) {
        if (chartId != null && chartId > 0) {
            // 删除详情缓存
            chartCacheService.evictChart(chartId);
            // 删除数据预览缓存
            chartDataCacheService.evictChartData(chartId);
        }
        if (userId != null && userId > 0) {
            // 更新列表缓存版本号（我的图表列表）
            chartListCacheService.evictMyChartList(userId);
            // 删除回收站列表缓存
            chartListCacheService.evictMyDeletedChartList(userId);
        }
    }

    /**
     * 发送告警通知
     * 
     * @param chartId 图表ID
     * @param chartName 图表名称
     * @param userId 用户ID
     */
    private void sendAlert(Long chartId, String chartName, Long userId) {
        try {
            // TODO: 实现告警逻辑
            // 可以集成：
            // 1. 邮件服务（JavaMailSender）
            // 2. 短信服务（阿里云短信）
            // 3. 钉钉机器人
            // 4. 企业微信机器人
            // 5. 自定义告警系统
            
            String alertMessage = String.format(
                "[BI系统告警] 图表生成失败进入死信队列\n" +
                "图表ID: %d\n" +
                "图表名称: %s\n" +
                "用户ID: %d\n" +
                "失败原因: 重试次数耗尽\n" +
                "时间: %s",
                chartId, 
                chartName != null ? chartName : "未命名", 
                userId,
                java.time.LocalDateTime.now()
            );
            
            log.warn("========== 告警通知 ==========");
            log.warn(alertMessage);
            log.warn("==============================");
            
            // TODO: 调用告警服务接口
            // alertService.send(alertMessage);
            
        } catch (Exception e) {
            log.error("发送告警通知失败", e);
        }
    }
}
