package com.yupi.springbootinit.mq.TestTwo;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

public class Recv {

    private static final String TASK_QUEUE_NAME = "task_queue";

    // 封装消费逻辑
    private static void startConsumer(int consumerId) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        final Connection connection = factory.newConnection();
        final Channel channel = connection.createChannel();

        channel.queueDeclare(TASK_QUEUE_NAME, true, false, false, null);
        System.out.println(" [*] Waiting for messages. To exit press CTRL+C");

        channel.basicQos(1);//当前消费者（Consumer）在确认（Ack）处理完 1 条消息前，不要给它推送新的消息。

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            try {
                System.out.println(" [消费者" + consumerId + "] Received '" + message + "'");

            } catch (Exception e)
            {
                e.getMessage();
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
            } finally{
                System.out.println(" [消费者" + consumerId + "] Done");
                // 手动Ack，必须有！否则Qos失效
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            }
        };
        channel.basicConsume(TASK_QUEUE_NAME, false, deliverCallback, consumerTag -> { });

    }
    public static void main(String[] argv) throws Exception {
        // 启动两个独立线程作为消费者
        new Thread(() -> {
            try {
                startConsumer(1);
                Thread.sleep(1000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        new Thread(() -> {
            try {
                startConsumer(2);
                Thread.sleep(5000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}