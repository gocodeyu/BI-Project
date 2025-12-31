package com.yupi.springbootinit.service;

import org.redisson.api.RLock;
import java.util.function.Supplier;

/**
 * 分布式锁服务接口
 */
public interface DistributedLockService {
    
    /**
     * 尝试获取锁并执行业务逻辑
     * 
     * @param lockKey 锁的唯一标识
     * @param waitTime 等待获取锁的时间（秒）
     * @param leaseTime 锁的自动释放时间（秒）
     * @param supplier 需要执行的业务逻辑
     * @param <T> 返回值类型
     * @return 业务逻辑的执行结果
     * @throws Exception 如果获取锁失败或执行出错
     */
    <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Supplier<T> supplier) throws Exception;
    
    /**
     * 尝试获取锁（不执行业务逻辑）
     * 
     * @param lockKey 锁的唯一标识
     * @param waitTime 等待获取锁的时间（秒）
     * @param leaseTime 锁的自动释放时间（秒）
     * @return 是否成功获取锁
     */
    boolean tryLock(String lockKey, long waitTime, long leaseTime);
    
    /**
     * 释放锁
     * 
     * @param lockKey 锁的唯一标识
     */
    void unlock(String lockKey);
    
    /**
     * 获取锁对象
     * 
     * @param lockKey 锁的唯一标识
     * @return 锁对象
     */
    RLock getLock(String lockKey);
}

