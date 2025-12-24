package com.yupi.springbootinit.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
//import org.checkerframework.checker.units.qual.C;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
@ConfigurationProperties(prefix = "spring.redis")
@Data
public class RedissonConfig {
    private Integer database;
    private String host;
    private String port;
    private String password;
    private Integer timeout;

    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        String redisAddress = "redis://"+host+":"+port;
        config.useSingleServer()
                .setAddress(redisAddress)
                .setDatabase(database)
                .setPassword(password);
        return Redisson.create(config);
    }
}
