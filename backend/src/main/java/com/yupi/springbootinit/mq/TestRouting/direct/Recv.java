package com.yupi.springbootinit.mq.TestRouting.direct;

import com.rabbitmq.client.*;

public class Recv {

    private static final String EXCHANGE_NAME = "direct_logs";

    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        channel.exchangeDeclare(EXCHANGE_NAME, "direct");
        String queueName1="queue3";
        String queueName2="queue4";
        String[] keys = new String[]{"3", "1","2"};
        channel.queueDeclare(queueName1, true, false, false, null);
        for(String key:keys){
            channel.queueBind(queueName1, EXCHANGE_NAME, key);
        }

        channel.queueDeclare(queueName2, true, false, false, null);
        channel.queueBind(queueName2, EXCHANGE_NAME, "4");

        System.out.println(" [*] Waiting for messages. To exit press CTRL+C");

        DeliverCallback deliverCallback1 = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println(" queue3 Received '" +
                    delivery.getEnvelope().getRoutingKey() + "':'" + message + "'");
        };
        channel.basicConsume(queueName1, true, deliverCallback1, consumerTag -> { });

        DeliverCallback deliverCallback2 = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println(" queue4 Received '" +
                    delivery.getEnvelope().getRoutingKey() + "':'" + message + "'");
        };
        channel.basicConsume(queueName2, true, deliverCallback2, consumerTag -> { });

    }
}
