package com.yupi.springbootinit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;

/**
 * Redis Pub/Sub 配置
 * 用于 SSE 跨实例消息推送
 *
 * @author yupi
 */
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    /**
     * 配置 StringRedisTemplate 使用 UTF-8 编码
     * 确保中文字符正确传输
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        
        // 使用 UTF-8 编码的 StringRedisSerializer
        StringRedisSerializer utf8Serializer = new StringRedisSerializer(StandardCharsets.UTF_8);
        template.setKeySerializer(utf8Serializer);
        template.setValueSerializer(utf8Serializer);
        template.setHashKeySerializer(utf8Serializer);
        template.setHashValueSerializer(utf8Serializer);
        
        template.afterPropertiesSet();
        return template;
    }
}

