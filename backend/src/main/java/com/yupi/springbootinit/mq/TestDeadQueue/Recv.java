package com.yupi.springbootinit.mq.TestDeadQueue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.util.HashMap;
import java.util.Map;

public class Recv {
    private static final String DEAD_EXCHANGE_NAME="dead-exchange";
    private static final String WORKING_EXCHANGE_NAME="working-exchange";
    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();


        //指定死信队列的参数
        Map<String, Object>args=new HashMap<>();
        //要绑定到哪个交换机上
        args.put("x-dead-letter-exchange", DEAD_EXCHANGE_NAME);
        //将死信转发到哪个队列上
        args.put("x-dead-letter-routing-key", "d1");

        //指定死信队列的参数
        Map<String, Object>args2=new HashMap<>();
        //要绑定到哪个交换机上
        args2.put("x-dead-letter-exchange", DEAD_EXCHANGE_NAME);
        //将死信转发到哪个队列上
        args2.put("x-dead-letter-routing-key", "d2");

        channel.exchangeDeclare(DEAD_EXCHANGE_NAME, "direct", true);
        channel.exchangeDeclare(WORKING_EXCHANGE_NAME, "topic",true);
        String queueName1="r1";
        channel.queueDeclare(queueName1, true, false, false, args);
        channel.queueBind(queueName1, WORKING_EXCHANGE_NAME, "r1.*");

        String queueName2="r2";
        channel.queueDeclare(queueName2, true, false, false, args2);
        channel.queueBind(queueName2, WORKING_EXCHANGE_NAME, "r2");

        String queueName3="r3";
        channel.queueDeclare(queueName3, true, false, false, args2);
        channel.queueBind(queueName3, WORKING_EXCHANGE_NAME, "#.r3");

        System.out.println(" [*] Waiting for messages. To exit press CTRL+C");
        // r1队列监听
        DeliverCallback deliverCallback1 = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            //拒绝消息
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(),false, false);
            System.out.println(" r1 Received '" +
                    delivery.getEnvelope().getRoutingKey() + "':'" + message + "'");
        };
        channel.basicConsume(queueName1, false, deliverCallback1, consumerTag -> { });
        // r2队列监听
        DeliverCallback deliverCallback2 = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            //拒绝消息
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(),false, false);
            System.out.println(" r2 Received '" +
                    delivery.getEnvelope().getRoutingKey() + "':'" + message + "'");
        };
        channel.basicConsume(queueName2, false, deliverCallback2, consumerTag -> { });
        // r3队列监听
        DeliverCallback deliverCallback3 = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            //拒绝消息
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(),false, false);
            System.out.println(" r3 Received '" +
                    delivery.getEnvelope().getRoutingKey() + "':'" + message + "'");
        };
        channel.basicConsume(queueName3, false, deliverCallback3, consumerTag -> { });


    }
}
