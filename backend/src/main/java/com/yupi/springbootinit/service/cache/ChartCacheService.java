package com.yupi.springbootinit.service.cache;

import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.DistributedLockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 图表详情缓存服务
 * 注意：本服务只使用 Redis + DB 二级缓存，不使用 Caffeine 本地缓存
 * 原因：避免与 SSE 通知的时序冲突（详见设计文档）
 *
 * @author yupi
 */
@Service
@Slf4j
public class ChartCacheService {

    private static final String REDIS_KEY_PREFIX = "bi:chart:";
    private static final String LOCK_KEY_PREFIX = "lock:chart:cache:"; // 分布式锁Key前缀
    private static final int TTL_MINUTES = 15;
    private static final int TTL_RANDOM_OFFSET_SECONDS = 60; // 随机偏移 ±60秒，避免雪崩
    private static final String NULL_VALUE_MARKER = "__NULL__"; // 空值标记，用于防止缓存穿透
    private static final int NULL_VALUE_TTL_SECONDS = 60; // 空值缓存TTL：60秒（短TTL，防止恶意查询）
    private static final long LOCK_WAIT_TIME = 0; // 锁等待时间：0秒（不等待，立即失败）
    private static final long LOCK_LEASE_TIME = 10; // 锁自动释放时间：10秒（防止死锁）
    private static final long RETRY_WAIT_MILLIS = 50; // 获取锁失败后，等待重试的时间（毫秒）
    private static final int MAX_RETRY_COUNT = 3; // 最大重试次数

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ChartService chartService;

    @Resource
    private DistributedLockService distributedLockService;

    /**
     * 获取图表详情（带缓存）
     * 流程：Redis Hash → DB
     *
     * @param chartId 图表ID
     * @return Chart 对象，不存在返回 null
     */
    public Chart getChartByIdWithCache(Long chartId) {
        if (chartId == null || chartId <= 0) {
            return null;
        }

        String redisKey = REDIS_KEY_PREFIX + chartId;

        try {
            // 1. 先查 Redis Hash
            Map<Object, Object> hashMap = stringRedisTemplate.opsForHash().entries(redisKey);
            if (!hashMap.isEmpty()) {
                // 检查是否为空值标记（防止缓存穿透）
                Object nullMarker = hashMap.get("__null__");
                if (NULL_VALUE_MARKER.equals(nullMarker)) {
                    log.info("[缓存穿透防护] 检测到空值缓存，直接返回null - chartId={}, cacheKey={}", chartId, redisKey);
                    return null;
                }
                
                // 从 Hash 转换为 Chart 对象
                Chart chart = hashToChart(hashMap);
                if (chart != null) {
                    log.info("[缓存命中] Redis缓存命中 - chartId={}, cacheKey={}", chartId, redisKey);
                    return chart;
                }
            }

            // 2. 缓存未命中，使用分布式锁防止缓存击穿
            String lockKey = LOCK_KEY_PREFIX + chartId;
            
            // 尝试获取锁并查询
            try {
                Chart chart = distributedLockService.executeWithLock(
                    lockKey,
                    LOCK_WAIT_TIME,
                    LOCK_LEASE_TIME,
                    () -> {
                        // 双重检查：获取锁后再次检查缓存（可能其他线程已写入）
                        Map<Object, Object> doubleCheckMap = stringRedisTemplate.opsForHash().entries(redisKey);
                        if (!doubleCheckMap.isEmpty()) {
                            // 检查是否为空值标记
                            Object nullMarker = doubleCheckMap.get("__null__");
                            if (NULL_VALUE_MARKER.equals(nullMarker)) {
                                log.info("[缓存击穿防护] 双重检查：检测到空值缓存 - chartId={}", chartId);
                                return null;
                            }
                            
                            // 从 Hash 转换为 Chart 对象
                            Chart cachedChart = hashToChart(doubleCheckMap);
                            if (cachedChart != null) {
                                log.info("[缓存击穿防护] 双重检查：缓存已存在，直接返回 - chartId={}", chartId);
                                return cachedChart;
                            }
                        }
                        
                        // 3. 查 DB
                        log.info("[缓存击穿防护] 获取锁成功，查询数据库 - chartId={}", chartId);
                        Chart dbChart = chartService.getById(chartId);
                        
                        if (dbChart != null) {
                            // 4. 写入 Redis Hash（正常数据）
                            log.info("[缓存击穿防护] 查询成功，写入Redis - chartId={}", chartId);
                            saveChartToRedis(dbChart);
                        } else {
                            // 5. 防止缓存穿透：缓存空值（短TTL）
                            log.warn("[缓存击穿防护] 数据库中不存在该图表，缓存空值 - chartId={}", chartId);
                            saveNullValueToRedis(chartId);
                        }
                        
                        return dbChart;
                    }
                );
                
                return chart;
                
            } catch (Exception e) {
                // 获取锁失败，等待后重试检查缓存（可能其他线程已写入）
                log.info("[缓存击穿防护] 获取锁失败，等待{}ms后重试检查缓存 - chartId={}", RETRY_WAIT_MILLIS, chartId);
                
                // 等待一小段时间，让获取锁成功的线程完成查询和写入缓存
                try {
                    Thread.sleep(RETRY_WAIT_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[缓存击穿防护] 等待被中断，直接查DB - chartId={}", chartId);
                    return chartService.getById(chartId);
                }
                
                // 重试检查缓存（可能其他线程已经写入）
                for (int i = 0; i < MAX_RETRY_COUNT; i++) {
                    Map<Object, Object> retryMap = stringRedisTemplate.opsForHash().entries(redisKey);
                    if (!retryMap.isEmpty()) {
                        // 检查是否为空值标记
                        Object nullMarker = retryMap.get("__null__");
                        if (NULL_VALUE_MARKER.equals(nullMarker)) {
                            log.info("[缓存击穿防护] 重试检查：检测到空值缓存 - chartId={}, retry={}", chartId, i + 1);
                            return null;
                        }
                        
                        // 从 Hash 转换为 Chart 对象
                        Chart retryChart = hashToChart(retryMap);
                        if (retryChart != null) {
                            log.info("[缓存击穿防护] 重试检查：缓存已存在，直接返回 - chartId={}, retry={}", chartId, i + 1);
                            return retryChart;
                        }
                    }
                    
                    // 如果缓存还是不存在，再等待一小段时间后重试
                    if (i < MAX_RETRY_COUNT - 1) {
                        try {
                            Thread.sleep(RETRY_WAIT_MILLIS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                
                // 如果重试多次后缓存还是不存在，降级：直接查DB
                log.warn("[缓存击穿防护] 重试{}次后缓存仍不存在，降级查DB - chartId={}", MAX_RETRY_COUNT, chartId);
                Chart chart = chartService.getById(chartId);
                if (chart != null) {
                    // 尝试写入缓存（不阻塞）
                    try {
                        saveChartToRedis(chart);
                    } catch (Exception ex) {
                        log.error("[缓存击穿防护] 降级写入缓存失败 - chartId={}", chartId, ex);
                    }
                }
                return chart;
            }

        } catch (Exception e) {
            log.error("获取图表详情缓存失败, chartId: {}", chartId, e);
            // 降级：直接查 DB
            return chartService.getById(chartId);
        }
    }

    /**
     * 删除图表详情缓存
     *
     * @param chartId 图表ID
     */
    public void evictChart(Long chartId) {
        if (chartId == null || chartId <= 0) {
            return;
        }

        String redisKey = REDIS_KEY_PREFIX + chartId;
        try {
            Boolean deleted = stringRedisTemplate.delete(redisKey);
            if (Boolean.TRUE.equals(deleted)) {
                log.info("[缓存删除] 成功删除图表详情缓存 - chartId={}, cacheKey={}", chartId, redisKey);
            } else {
                log.info("[缓存删除] 缓存键不存在或已删除 - chartId={}, cacheKey={}", chartId, redisKey);
            }
        } catch (Exception e) {
            log.error("[缓存删除失败] 删除图表详情缓存异常 - chartId={}, error={}", chartId, e.getMessage(), e);
        }
    }

    /**
     * 将 Chart 对象保存到 Redis Hash
     */
    private void saveChartToRedis(Chart chart) {
        if (chart == null) {
            return;
        }

        String redisKey = REDIS_KEY_PREFIX + chart.getId();
        try {
            // 使用 Hash 结构存储，避免整体序列化开销
            stringRedisTemplate.opsForHash().put(redisKey, "id", String.valueOf(chart.getId()));
            putIfNotNull(redisKey, "name", chart.getName());
            putIfNotNull(redisKey, "goal", chart.getGoal());
            putIfNotNull(redisKey, "chartType", chart.getChartType());
            putIfNotNull(redisKey, "chartData", chart.getChartData());
            putIfNotNull(redisKey, "genChart", chart.getGenChart());
            putIfNotNull(redisKey, "genResult", chart.getGenResult());
            putIfNotNull(redisKey, "userId", chart.getUserId() != null ? String.valueOf(chart.getUserId()) : null);
            putIfNotNull(redisKey, "status", chart.getStatus());
            putIfNotNull(redisKey, "execMessage", chart.getExecMessage());
            putIfNotNull(redisKey, "isDelete", chart.getIsDelete() != null ? String.valueOf(chart.getIsDelete()) : null);
            putIfNotNull(redisKey, "createTime", chart.getCreateTime() != null ? String.valueOf(chart.getCreateTime().getTime()) : null);
            putIfNotNull(redisKey, "updateTime", chart.getUpdateTime() != null ? String.valueOf(chart.getUpdateTime().getTime()) : null);

            // 设置 TTL：15分钟 + 随机偏移（±60秒）
            long ttlSeconds = TTL_MINUTES * 60L + (long) (Math.random() * 2 * TTL_RANDOM_OFFSET_SECONDS - TTL_RANDOM_OFFSET_SECONDS);
            stringRedisTemplate.expire(redisKey, ttlSeconds, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("保存图表到 Redis 失败, chartId: {}", chart.getId(), e);
        }
    }

    /**
     * 保存空值到 Redis（防止缓存穿透）
     * 当查询不存在的数据时，也缓存一个空值标记，避免重复查询数据库
     *
     * @param chartId 图表ID
     */
    private void saveNullValueToRedis(Long chartId) {
        if (chartId == null || chartId <= 0) {
            return;
        }

        String redisKey = REDIS_KEY_PREFIX + chartId;
        try {
            // 使用特殊字段标记空值
            stringRedisTemplate.opsForHash().put(redisKey, "__null__", NULL_VALUE_MARKER);
            // 设置短TTL（60秒），防止恶意查询占用缓存空间
            stringRedisTemplate.expire(redisKey, NULL_VALUE_TTL_SECONDS, TimeUnit.SECONDS);
            log.info("[缓存穿透防护] 已缓存空值标记 - chartId={}, ttl={}s", chartId, NULL_VALUE_TTL_SECONDS);
        } catch (Exception e) {
            log.error("保存空值到 Redis 失败, chartId: {}", chartId, e);
        }
    }

    /**
     * 从 Redis Hash 转换为 Chart 对象
     */
    private Chart hashToChart(Map<Object, Object> hashMap) {
        if (hashMap == null || hashMap.isEmpty()) {
            return null;
        }

        try {
            Chart chart = new Chart();
            chart.setId(getLongValue(hashMap, "id"));
            chart.setName(getStringValue(hashMap, "name"));
            chart.setGoal(getStringValue(hashMap, "goal"));
            chart.setChartType(getStringValue(hashMap, "chartType"));
            chart.setChartData(getStringValue(hashMap, "chartData"));
            chart.setGenChart(getStringValue(hashMap, "genChart"));
            chart.setGenResult(getStringValue(hashMap, "genResult"));
            chart.setUserId(getLongValue(hashMap, "userId"));
            chart.setStatus(getStringValue(hashMap, "status"));
            chart.setExecMessage(getStringValue(hashMap, "execMessage"));
            chart.setIsDelete(getIntegerValue(hashMap, "isDelete"));

            // 时间戳转 Date
            Long createTime = getLongValue(hashMap, "createTime");
            if (createTime != null) {
                chart.setCreateTime(new java.util.Date(createTime));
            }
            Long updateTime = getLongValue(hashMap, "updateTime");
            if (updateTime != null) {
                chart.setUpdateTime(new java.util.Date(updateTime));
            }

            return chart;
        } catch (Exception e) {
            log.error("从 Redis Hash 转换为 Chart 失败", e);
            return null;
        }
    }

    private void putIfNotNull(String key, String field, String value) {
        if (value != null) {
            stringRedisTemplate.opsForHash().put(key, field, value);
        }
    }

    private String getStringValue(Map<Object, Object> hashMap, String key) {
        Object value = hashMap.get(key);
        return value != null ? value.toString() : null;
    }

    private Long getLongValue(Map<Object, Object> hashMap, String key) {
        String value = getStringValue(hashMap, key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer getIntegerValue(Map<Object, Object> hashMap, String key) {
        String value = getStringValue(hashMap, key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

