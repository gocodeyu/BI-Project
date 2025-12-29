package com.yupi.springbootinit.bizmq;

import com.yupi.springbootinit.config.RabbitMqConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class BiMessageProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送生成图表消息
     * @param message 消息内容（chartId）
     * @param isVip 是否是 VIP 用户
     */
    public void sendMessage(String message, boolean isVip) {
        String routingKey = isVip ? RabbitMqConfig.BI_VIP_ROUTING_KEY : RabbitMqConfig.BI_COMMON_ROUTING_KEY;
        rabbitTemplate.convertAndSend(RabbitMqConfig.BI_EXCHANGE_NAME, routingKey, message);
    }
}