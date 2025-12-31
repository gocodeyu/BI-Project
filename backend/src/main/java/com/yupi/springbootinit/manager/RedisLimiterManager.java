package com.yupi.springbootinit.manager;

import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class RedisLimiterManager {
    @Resource
    private RedissonClient redissonClient;
    /**
     * 单个用户限流操作，每秒不超过两次访问
     * @param key
     */
    public void doRateLimit(String key) {
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        // 参数：限流类型（整体/单机），速率，时间间隔，时间单位
        // OVERALL 表示整体限流（分布式），每 1 秒 2 个请求
        // trySetRate: 如果限流器不存在，则初始化；如果存在，则不更新配置（避免覆盖运行中的计数）
        rateLimiter.trySetRate(RateType.OVERALL, 2, 1, RateIntervalUnit.SECONDS);
        // 每当一个操作来了后，请求一个令牌
        boolean canOp = rateLimiter.tryAcquire(1);
        if (!canOp) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, "请求过于频繁，请稍后再试");
        }
    }

    /**
     * 针对不同用户进行限流(每天0点自动刷新)
     * @param userId
     * @param userRole
     */
    public void doDailyLimit(Long userId, String userRole) {
        //1.每日限流key
        String todayStr = getTodayStr();
        //key 格式：gen_chart_daily_123_20231201
        String dailyKey = "gen_chart_daily_" + userId+"_" + todayStr;
        RRateLimiter dailyLimiter = redissonClient.getRateLimiter(dailyKey);
        // 2. 判断角色分配额度
        // 假设 "vip" 为会员角色，"admin" 也拥有高额度，其他为普通用户
        long dailyLimitCount = 3; // 默认为非会员 3 次
        if ("vip".equals(userRole) || "admin".equals(userRole)) {
            dailyLimitCount = 50; // 会员/管理员 50 次
        }
        if(!dailyLimiter.isExists())
        {
            dailyLimiter.trySetRate(RateType.OVERALL, dailyLimitCount, 24, RateIntervalUnit.HOURS);
            dailyLimiter.expire(1, TimeUnit.DAYS);
        }
        // 4. 尝试获取令牌
        if (!dailyLimiter.tryAcquire(1)) {
            String message = "vip".equals(userRole) ? "今日次数已达上限 (50次)" : "非会员每日仅限 3 次，请升级会员";
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, message);
        }
    }

    /**
     * 查询用户剩余的提问次数
     * @param userId
     * @param userRole
     * @return  剩余次数
     */
    public long getRemainingPermits(Long userId, String userRole) {
        String todayStr = getTodayStr();
        String dailyKey = "gen_chart_daily_" + userId + "_" + todayStr;
        RRateLimiter dailyLimiter = redissonClient.getRateLimiter(dailyKey);

        // 计算该用户角色的总额度
        long dailyLimitCount = "vip".equals(userRole) || "admin".equals(userRole) ? 50 : 3;

        // 如果限流器没被初始化过（说明今天还没用过），直接返回总额度
        if (!dailyLimiter.isExists()) {
            return dailyLimitCount;
        }

        // 获取当前剩余的令牌数
        // 注意：Redisson 的 availablePermits 是获取当前可用的，但如果配置改变可能会有偏差，
        // 这里为了严谨，我们重新声明一下配置（不会覆盖旧的计数，只是确保配置存在）
        dailyLimiter.trySetRate(RateType.OVERALL, dailyLimitCount, 24, RateIntervalUnit.HOURS);

        return dailyLimiter.availablePermits();
    }
    /**
     * 删除用户的限流key
     */
    public void deleteUserRateLimit(Long userId, String userRole){
        String todayStr = getTodayStr();
        String dailyKey = "gen_chart_daily_" + userId + "_" + todayStr;
        redissonClient.getRateLimiter(dailyKey).delete();
    }
    /**
     * 回退用户的限流次数（用于释放已扣减但未使用的配额）
     * 适用场景：扣减限流后，因业务逻辑失败（如分布式锁获取失败）需要回退
     * 
     * @param userId 用户ID
     * @param userRole 用户角色
     */
    public void rollbackDailyLimit(Long userId, String userRole) {
        String todayStr = getTodayStr();
        String dailyKey = "gen_chart_daily_" + userId + "_" + todayStr;
        RRateLimiter dailyLimiter = redissonClient.getRateLimiter(dailyKey);
        
        // 如果限流器不存在，说明没有扣减过，无需回退
        if (!dailyLimiter.isExists()) {
            log.warn("限流器不存在，无需回退: userId={}", userId);
            return;
        }
        
        // 计算该用户角色的总额度
        long dailyLimitCount = "vip".equals(userRole) || "admin".equals(userRole) ? 50 : 3;
        
        // 获取当前剩余的令牌数
        long availablePermits = dailyLimiter.availablePermits();
        
        // 如果已经达到上限，说明没有扣减过或已经回退过，不再重复回退
        if (availablePermits >= dailyLimitCount) {
            log.info("限流次数已达上限，无需回退: userId={}, availablePermits={}", userId, availablePermits);
            return;
        }
        
        // 回退操作：通过 setRate 重新设置限流器来增加一个令牌
        // 注意：Redisson 的 RRateLimiter 没有直接的"增加令牌"方法
        // 我们使用一个变通方案：删除并重新创建限流器，设置新的令牌数
        try {
            // 保存当前已使用的次数
            long usedCount = dailyLimitCount - availablePermits;
            
            // 回退一次，即已使用次数减1
            long newUsedCount = Math.max(0, usedCount - 1);
            
            // 删除旧的限流器
            dailyLimiter.delete();
            
            // 重新创建限流器，设置新的令牌数
            dailyLimiter = redissonClient.getRateLimiter(dailyKey);
            dailyLimiter.trySetRate(RateType.OVERALL, dailyLimitCount, 24, RateIntervalUnit.HOURS);
            dailyLimiter.expire(1, TimeUnit.DAYS);
            
            // 预先消费掉已使用的令牌
            for (int i = 0; i < newUsedCount; i++) {
                dailyLimiter.tryAcquire(1);
            }
            
            log.info("成功回退限流次数: userId={}, 回退后可用次数={}", userId, dailyLimiter.availablePermits());
            
        } catch (Exception e) {
            log.error("回退限流次数失败: userId={}", userId, e);
        }
    }
    
    /**
     * 辅助方法：获取当前日期字符串 (yyyyMMdd)
     */
    private String getTodayStr() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        return sdf.format(new Date());
    }
}
