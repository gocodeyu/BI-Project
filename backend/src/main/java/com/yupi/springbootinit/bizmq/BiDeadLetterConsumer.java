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

@Component
@Slf4j
public class BiDeadLetterConsumer {

    @Resource
    private ChartService chartService;

    @RabbitListener(queues = RabbitMqConfig.BI_DL_QUEUE_NAME, ackMode = "MANUAL")
    public void receiveDlMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.error("死信队列收到消息，处理失败的图表: {}", message);
        if (message == null) {
            try {
                channel.basicAck(deliveryTag, false);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }

        long chartId = Long.parseLong(message);
        Chart chart = chartService.getById(chartId);
        if(chart == null){
            try { channel.basicAck(deliveryTag, false); } catch (Exception e) {}
            return;
        }

        // 将数据库状态更新为 Failed，并记录信息
        Chart updateChart = new Chart();
        updateChart.setId(chartId);
        updateChart.setStatus(GenChartStatusEnum.FAILED.getValue());
        updateChart.setExecMessage("AI生成失败，系统重试次数已耗尽或请求超时");
        chartService.updateById(updateChart);

        try {
            // 确认死信消息，表示"处理了失败"，从死信队列移除
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("死信队列 Ack 失败", e);
        }
    }
}