package com.yupi.springbootinit.manager;

import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
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
     * 针对不同用户进行限流
     * @param userId
     * @param userRole
     */
    public void doDailyLimit(Long userId, String userRole) {
        //1.每日限流key
        String dailyKey = "gen_chart_daily_" + userId;
        RRateLimiter dailyLimiter = redissonClient.getRateLimiter(dailyKey);
        // 2. 判断角色分配额度
        // 假设 "vip" 为会员角色，"admin" 也拥有高额度，其他为普通用户
        long dailyLimitCount = 3; // 默认为非会员 3 次
        if ("vip".equals(userRole) || "admin".equals(userRole)) {
            dailyLimitCount = 50; // 会员/管理员 50 次
        }

        // 3. 设置限流规则：24小时内 X 次
        // 注意：Redisson 的 RateLimiter 是滑动窗口或令牌桶，不是严格的“自然日归零”
        // 如果需要严格自然日（0点清空），建议使用 Redis String + Expire 实现，但 Redisson 更简单
        dailyLimiter.trySetRate(RateType.OVERALL, dailyLimitCount, 24, RateIntervalUnit.HOURS);
        //这里是几点清零呢？

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
        String dailyKey = "gen_chart_daily_" + userId;
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
        String dailyKey = "gen_chart_daily_" + userId;
        redissonClient.getRateLimiter(dailyKey).delete();
    }
}
