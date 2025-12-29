package com.yupi.springbootinit.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMqConfig {

    // --- 常量定义 ---
    public static final String BI_EXCHANGE_NAME = "bi_exchange";
    public static final String BI_VIP_QUEUE_NAME = "bi_vip_queue";
    public static final String BI_COMMON_QUEUE_NAME = "bi_common_queue";
    public static final String BI_VIP_ROUTING_KEY = "bi_vip_routing_key";
    public static final String BI_COMMON_ROUTING_KEY = "bi_common_routing_key";

    // --- 死信队列配置 (DLQ) ---
    public static final String BI_DLX_EXCHANGE_NAME = "bi_dlx_exchange";
    public static final String BI_DL_QUEUE_NAME = "bi_dl_queue";
    public static final String BI_DL_ROUTING_KEY = "bi_dl_routing_key";

    // 1. 声明业务交换机
    @Bean
    public DirectExchange biExchange() {
        return new DirectExchange(BI_EXCHANGE_NAME, true, false);
    }

    // 2. 声明死信交换机
    @Bean
    public DirectExchange biDlxExchange() {
        return new DirectExchange(BI_DLX_EXCHANGE_NAME, true, false);
    }

    // 3. 声明死信队列
    @Bean
    public Queue biDlQueue() {
        return QueueBuilder.durable(BI_DL_QUEUE_NAME).build();
    }

    // 4. 绑定死信队列到死信交换机
    @Bean
    public Binding biDlBinding() {
        return BindingBuilder.bind(biDlQueue()).to(biDlxExchange()).with(BI_DL_ROUTING_KEY);
    }

    // 5. 声明 VIP 队列 (绑定死信交换机)
    @Bean
    public Queue biVipQueue() {
        Map<String, Object> args = new HashMap<>();
        // 指定死信交换机
        args.put("x-dead-letter-exchange", BI_DLX_EXCHANGE_NAME);
        // 指定死信 RoutingKey
        args.put("x-dead-letter-routing-key", BI_DL_ROUTING_KEY);
        // 可选：设置队列 TTL (例如 60秒未消费则进入死信)，防止积压过久
        // args.put("x-message-ttl", 60000);
        return QueueBuilder.durable(BI_VIP_QUEUE_NAME).withArguments(args).build();
    }

    // 6. 声明普通队列 (绑定死信交换机)
    @Bean
    public Queue biCommonQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", BI_DLX_EXCHANGE_NAME);
        args.put("x-dead-letter-routing-key", BI_DL_ROUTING_KEY);
        return QueueBuilder.durable(BI_COMMON_QUEUE_NAME).withArguments(args).build();
    }

    // 7. 绑定业务队列
    @Bean
    public Binding biVipBinding() {
        return BindingBuilder.bind(biVipQueue()).to(biExchange()).with(BI_VIP_ROUTING_KEY);
    }

    @Bean
    public Binding biCommonBinding() {
        return BindingBuilder.bind(biCommonQueue()).to(biExchange()).with(BI_COMMON_ROUTING_KEY);
    }
}