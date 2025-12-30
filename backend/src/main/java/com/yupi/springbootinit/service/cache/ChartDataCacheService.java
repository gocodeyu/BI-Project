package com.yupi.springbootinit.service.cache;

import com.google.gson.Gson;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.vo.ChartDataPreviewResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 图表数据预览缓存服务
 * 支持三级缓存：Caffeine → Redis → DB
 * 仅缓存前 3 页和常用 pageSize（10、20）
 *
 * @author yupi
 */
@Service
@Slf4j
public class ChartDataCacheService {

    private static final String REDIS_KEY_PREFIX = "bi:chart:data:";
    private static final String CAFFEINE_KEY_PREFIX = "chart:data:";
    private static final int REDIS_TTL_SECONDS = 600; // 10分钟
    private static final int REDIS_TTL_RANDOM_OFFSET = 60; // ±60秒
    private static final int CAFFEINE_TTL_SECONDS = 120; // 2分钟
    private static final int MAX_CACHE_PAGE = 3; // 最多缓存前 3 页
    private static final int[] CACHEABLE_PAGE_SIZES = {10, 20}; // 可缓存的 pageSize

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ChartMapper chartMapper;

    private final Gson gson = new Gson();

    // Caffeine 本地缓存
    private Cache<String, ChartDataPreviewResponse> caffeineCache;

    @PostConstruct
    public void init() {
        caffeineCache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(CAFFEINE_TTL_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 获取图表数据预览（带缓存）
     *
     * @param chartId 图表ID
     * @param current 当前页
     * @param pageSize 每页大小
     * @return 数据预览响应
     */
    public ChartDataPreviewResponse getChartDataPreviewWithCache(Long chartId, long current, long pageSize) {
        if (chartId == null || chartId <= 0) {
            return null;
        }

        // 判断是否应该缓存
        if (!shouldUseCache(current, pageSize)) {
            return queryFromDb(chartId, current, pageSize);
        }

        try {
            String cacheKey = REDIS_KEY_PREFIX + chartId + ":p" + current + ":s" + pageSize;
            String caffeineKey = CAFFEINE_KEY_PREFIX + chartId + ":p" + current + ":s" + pageSize;

            // 1. 查 Caffeine
            ChartDataPreviewResponse result = caffeineCache.getIfPresent(caffeineKey);
            if (result != null) {
                log.debug("数据预览 Caffeine 缓存命中: chartId={}, current={}", chartId, current);
                return result;
            }

            // 2. 查 Redis
            String redisValue = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StringUtils.isNotBlank(redisValue)) {
                try {
                    result = gson.fromJson(redisValue, ChartDataPreviewResponse.class);
                    if (result != null) {
                        // 存入 Caffeine
                        caffeineCache.put(caffeineKey, result);
                        log.debug("数据预览 Redis 缓存命中: chartId={}, current={}", chartId, current);
                        return result;
                    }
                } catch (Exception e) {
                    log.error("数据预览 Redis 缓存反序列化失败", e);
                }
            }

            // 3. 查 DB
            result = queryFromDb(chartId, current, pageSize);
            if (result != null) {
                // 写入 Redis
                String json = gson.toJson(result);
                long ttl = REDIS_TTL_SECONDS + (long) (Math.random() * 2 * REDIS_TTL_RANDOM_OFFSET - REDIS_TTL_RANDOM_OFFSET);
                stringRedisTemplate.opsForValue().set(cacheKey, json, ttl, TimeUnit.SECONDS);
                // 写入 Caffeine
                caffeineCache.put(caffeineKey, result);
            }

            return result;

        } catch (Exception e) {
            log.error("获取图表数据预览缓存失败, chartId: {}", chartId, e);
            return queryFromDb(chartId, current, pageSize);
        }
    }

    /**
     * 删除图表数据预览缓存
     * 删除该图表的所有分页缓存
     *
     * @param chartId 图表ID
     */
    public void evictChartData(Long chartId) {
        if (chartId == null || chartId <= 0) {
            return;
        }

        try {
            // 删除所有相关 Key（前 3 页 + 常用 pageSize）
            for (int page = 1; page <= MAX_CACHE_PAGE; page++) {
                for (int size : CACHEABLE_PAGE_SIZES) {
                    String cacheKey = REDIS_KEY_PREFIX + chartId + ":p" + page + ":s" + size;
                    stringRedisTemplate.delete(cacheKey);
                    // 删除 Caffeine
                    String caffeineKey = CAFFEINE_KEY_PREFIX + chartId + ":p" + page + ":s" + size;
                    caffeineCache.invalidate(caffeineKey);
                }
            }
            log.debug("图表数据预览缓存已删除: chartId={}", chartId);
        } catch (Exception e) {
            log.error("删除图表数据预览缓存失败, chartId: {}", chartId, e);
        }
    }

    /**
     * 判断是否应该使用缓存
     * 仅缓存前 3 页和常用 pageSize
     */
    private boolean shouldUseCache(long current, long pageSize) {
        if (current > MAX_CACHE_PAGE) {
            return false;
        }
        for (int size : CACHEABLE_PAGE_SIZES) {
            if (pageSize == size) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 DB 查询
     */
    private ChartDataPreviewResponse queryFromDb(Long chartId, long current, long pageSize) {
        String tableName = "chart_" + chartId;
        long offset = (current - 1) * pageSize;

        // 查询总记录数
        long total = chartMapper.countChartData(tableName);

        // 查询分页数据
        List<Map<String, Object>> dataList = chartMapper.queryChartDataWithPage(tableName, offset, pageSize);

        // 构建返回结果
        ChartDataPreviewResponse response = new ChartDataPreviewResponse();

        // 获取表头（排除id列）
        if (!dataList.isEmpty()) {
            Map<String, Object> firstRow = dataList.get(0);
            List<String> headers = firstRow.keySet().stream()
                    .filter(key -> !"id".equals(key))
                    .collect(Collectors.toList());
            response.setHeaders(headers);

            // 转换数据
            List<List<String>> data = dataList.stream()
                    .map(row -> headers.stream()
                            .map(header -> String.valueOf(row.get(header)))
                            .collect(Collectors.toList()))
                    .collect(Collectors.toList());
            response.setData(data);
        } else {
            response.setHeaders(java.util.Collections.emptyList());
            response.setData(java.util.Collections.emptyList());
        }

        response.setTotal(total);
        return response;
    }
}

