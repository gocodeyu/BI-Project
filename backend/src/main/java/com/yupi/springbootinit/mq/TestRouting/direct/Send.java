package com.yupi.springbootinit.mq.TestRouting.direct;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.util.Scanner;

public class Send {

    private static final String EXCHANGE_NAME = "direct_logs";

    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            //声明交换机，路由规则：direct
            channel.exchangeDeclare(EXCHANGE_NAME, "direct");
            Scanner scanner = new Scanner(System.in);
            while(scanner.hasNext()){
                String input = scanner.nextLine();
                if(input.length()<1)
                    continue;
                String[] arr = input.split(" ");
                int n=arr.length;
                String[] severities = new String[n-1];
                for(int i=0;i<n-1;i++){
                    severities[i] = arr[i];
                }
                String message = arr[1];
                for(String severity: severities){
                    channel.basicPublish(EXCHANGE_NAME, severity, null, message.getBytes("UTF-8"));
                    System.out.println(" [x] Sent '" + message + "'with routing key:'" + severity + "'");
                }

            }
        }
    }
    //..
}
