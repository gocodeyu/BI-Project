package com.yupi.springbootinit.service.impl;

import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.service.DistributedLockService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁服务实现类
 */
@Service
@Slf4j
public class DistributedLockServiceImpl implements DistributedLockService {
    
    @Resource
    private RedissonClient redissonClient;
    
    @Override
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Supplier<T> supplier) throws Exception {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        
        try {
            // 尝试获取锁
            acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            
            if (!acquired) {
                log.warn("获取分布式锁失败，检测到重复提交: lockKey={}", lockKey);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "检测到重复提交，请勿短时间内提交相同的请求");
            }
            
            log.info("成功获取分布式锁: lockKey={}", lockKey);
            
            // 执行业务逻辑
            return supplier.get();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取锁时被中断: lockKey={}", lockKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后再试");
        } finally {
            // 释放锁（仅当当前线程持有锁时）
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("释放分布式锁: lockKey={}", lockKey);
            }
        }
    }
    
    @Override
    public boolean tryLock(String lockKey, long waitTime, long leaseTime) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            return lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取锁时被中断: lockKey={}", lockKey, e);
            return false;
        }
    }
    
    @Override
    public void unlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("释放分布式锁: lockKey={}", lockKey);
        }
    }
    
    @Override
    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }
}

