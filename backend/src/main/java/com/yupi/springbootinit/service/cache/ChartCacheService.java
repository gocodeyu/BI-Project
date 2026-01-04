package com.yupi.springbootinit.service.cache;

import com.google.gson.Gson;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
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
    private static final int TTL_MINUTES = 15;
    private static final int TTL_RANDOM_OFFSET_SECONDS = 60; // 随机偏移 ±60秒，避免雪崩
    private static final String NULL_VALUE_MARKER = "__NULL__"; // 空值标记，用于防止缓存穿透
    private static final int NULL_VALUE_TTL_SECONDS = 60; // 空值缓存TTL：60秒（短TTL，防止恶意查询）

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ChartService chartService;

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

            // 2. 查 DB
            log.info("[缓存未命中] Redis缓存未命中，查询数据库 - chartId={}", chartId);
            Chart chart = chartService.getById(chartId);
            if (chart != null) {
                // 3. 写入 Redis Hash（正常数据）
                log.info("[缓存写入] 将数据库查询结果写入Redis - chartId={}", chartId);
                saveChartToRedis(chart);
            } else {
                // 4. 防止缓存穿透：缓存空值（短TTL）
                log.warn("[缓存穿透防护] 数据库中不存在该图表，缓存空值 - chartId={}", chartId);
                saveNullValueToRedis(chartId);
            }
            return chart;

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

