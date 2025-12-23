package com.yupi.springbootinit.mapper;

import com.yupi.springbootinit.manager.RedisLimiterManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class RedisLimiterTest {
    @Resource
    private RedisLimiterManager redisLimiterManager;
    @Test
    public void testRateLimit() {
        for(int i = 0; i < 2; i++){
            redisLimiterManager.doRateLimit("user_");
            System.out.println("操作成功");
        }
        for(int i = 0; i < 10; i++){
            redisLimiterManager.doDailyLimit(1L, "user");
            System.out.println("操作成功1");
        }
    }
}
