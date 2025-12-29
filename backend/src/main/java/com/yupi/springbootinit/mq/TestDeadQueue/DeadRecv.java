package com.yupi.springbootinit.mq.TestDeadQueue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

public class DeadRecv {
    private static final String DEAD_EXCHANGE_NAME = "dead-exchange";

    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            // 声明死信交换机（幂等，多次声明不影响）
            channel.exchangeDeclare(DEAD_EXCHANGE_NAME, "direct", true);

            // 声明死信队列 d1 并绑定
            String deadQueue1 = "d1";
            channel.queueDeclare(deadQueue1, true, false, false, null);
            channel.queueBind(deadQueue1, DEAD_EXCHANGE_NAME, "d1");

            // 声明死信队列 d2 并绑定
            String deadQueue2 = "d2";
            channel.queueDeclare(deadQueue2, true, false, false, null);
            channel.queueBind(deadQueue2, DEAD_EXCHANGE_NAME, "d2");

            System.out.println(" [*] 死信队列消费者已启动，等待死信消息...（按 CTRL+C 退出）");

            // 消费死信队列 d1
            DeliverCallback d1Callback = (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), "UTF-8");
                System.out.println(" [d1 死信队列] 收到消息 -> 路由键：" + delivery.getEnvelope().getRoutingKey() + "，内容：" + message);
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false); // 手动确认
            };
            channel.basicConsume(deadQueue1, false, d1Callback, consumerTag -> {
            });

            // 消费死信队列 d2
            DeliverCallback d2Callback = (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), "UTF-8");
                System.out.println(" [d2 死信队列] 收到消息 -> 路由键：" + delivery.getEnvelope().getRoutingKey() + "，内容：" + message);
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false); // 手动确认
            };
            channel.basicConsume(deadQueue2, false, d2Callback, consumerTag -> {
            });
            // 阻塞主线程
            synchronized (DeadRecv.class) {
                DeadRecv.class.wait();
            }
        }
    }
}
