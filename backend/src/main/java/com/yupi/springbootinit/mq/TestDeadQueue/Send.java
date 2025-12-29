package com.yupi.springbootinit.mq.TestDeadQueue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.util.Scanner;

public class Send {
    private static final String DEAD_EXCHANGE_NAME="dead-exchange";
    private static final String WORKING_EXCHANGE_NAME="working-exchange";
    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            Scanner scanner = new Scanner(System.in);
            while(scanner.hasNext()) {
                String input = scanner.nextLine();
                if(input.length()<1)
                    continue;
                String[] arr = input.split(" ");
                String routingKey = arr[0];
                String message = arr[1];
                channel.basicPublish(WORKING_EXCHANGE_NAME, routingKey, null, message.getBytes("UTF-8"));
                System.out.println(" [x] Sent '" + message + "'with routing key:'" + routingKey + "'");
            }
        }
    }
}
