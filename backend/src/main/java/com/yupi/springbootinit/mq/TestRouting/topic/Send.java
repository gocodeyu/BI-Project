package com.yupi.springbootinit.mq.TestRouting.topic;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.util.Scanner;

public class Send {

    private static final String EXCHANGE_NAME = "topic_logs";

    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            channel.exchangeDeclare(EXCHANGE_NAME, "topic");
            Scanner scanner = new Scanner(System.in);
            while(scanner.hasNext()){
                String input = scanner.nextLine();
                if(input.length()<1)
                    continue;
                String[] arr = input.split(" ");
                String severity=arr[0];
                String message = arr[1];
                channel.basicPublish(EXCHANGE_NAME, severity, null, message.getBytes("UTF-8"));
                System.out.println(" [x] Sent '" + message + "'with routing key:'" + severity + "'");

            }
        }
    }
    //..
}
