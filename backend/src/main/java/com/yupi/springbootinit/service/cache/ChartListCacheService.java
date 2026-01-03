package com.yupi.springbootinit.service.cache;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import com.yupi.springbootinit.model.dto.chart.ChartQueryRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.vo.ChartListVO;
import com.yupi.springbootinit.service.ChartService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 图表列表缓存服务
 * 使用版本号机制解决列表缓存删除难的问题
 * 支持三级缓存：Caffeine → Redis → DB
 *
 * @author yupi
 */
@Service
@Slf4j
public class ChartListCacheService {

    private static final String REDIS_VERSION_KEY_PREFIX = "bi:chart:list:my:";
    private static final String REDIS_DATA_KEY_PREFIX = "bi:chart:list:my:";
    private static final String REDIS_DELETE_LIST_KEY_PREFIX = "bi:chart:list:mydel:";
    private static final String CAFFEINE_KEY_PREFIX = "chart:list:my:";
    private static final int REDIS_DATA_TTL_SECONDS = 60;
    private static final int REDIS_DATA_TTL_RANDOM_OFFSET = 10; // ±10秒
    private static final int CAFFEINE_TTL_SECONDS = 20;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ChartService chartService;

    private final Gson gson = new Gson();

    // Caffeine 本地缓存
    private Cache<String, Page<ChartListVO>> caffeineCache;

    @PostConstruct
    public void init() {
        caffeineCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(CAFFEINE_TTL_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 获取我的图表列表（带缓存）
     * 仅对简单条件缓存：name 为空、chartType 为空、current 在前 3 页内
     *
     * @param chartQueryRequest 查询请求
     * @return 分页结果
     */
    public Page<ChartListVO> getMyChartListWithCache(ChartQueryRequest chartQueryRequest) {
        if (chartQueryRequest == null) {
            return null;
        }

        // 判断是否应该使用缓存
        if (!shouldUseCache(chartQueryRequest)) {
            // 复杂查询直接查 DB
            return queryFromDb(chartQueryRequest);
        }

        Long userId = chartQueryRequest.getUserId();
        long current = chartQueryRequest.getCurrent();
        long pageSize = chartQueryRequest.getPageSize();

        try {
            // 1. 获取版本号（不递增，只读取）
            String versionKey = REDIS_VERSION_KEY_PREFIX + userId + ":v";
            String versionStr = stringRedisTemplate.opsForValue().get(versionKey);
            Long version;
            if (versionStr == null || versionStr.isEmpty()) {
                // 首次访问，初始化版本号为 1
                stringRedisTemplate.opsForValue().set(versionKey, "1", 7, TimeUnit.DAYS);
                version = 1L;
            } else {
                version = Long.parseLong(versionStr);
            }

            // 2. 构造缓存 Key
            String cacheKey = REDIS_DATA_KEY_PREFIX + userId + ":p" + current + ":s" + pageSize + ":v" + version;
            String caffeineKey = CAFFEINE_KEY_PREFIX + userId + ":p" + current + ":s" + pageSize + ":v" + version;

            // 3. 查 Caffeine
            Page<ChartListVO> result = caffeineCache.getIfPresent(caffeineKey);
            if (result != null) {
                log.info("[缓存命中] Caffeine本地缓存命中 - 图表列表 - userId={}, page={}, size={}, version={}", userId, current, pageSize, version);
                return result;
            }

            // 4. 查 Redis
            String redisValue = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StringUtils.isNotBlank(redisValue)) {
                try {
                    @SuppressWarnings("unchecked")
                    Page<ChartListVO> cachedPage = gson.fromJson(redisValue, Page.class);
                    result = cachedPage;
                    // 存入 Caffeine
                    if (result != null) {
                        caffeineCache.put(caffeineKey, result);
                        log.info("[缓存命中] Redis缓存命中，写入Caffeine - 图表列表 - userId={}, page={}, size={}, version={}", userId, current, pageSize, version);
                        return result;
                    }
                } catch (Exception e) {
                    log.error("[缓存错误] 图表列表Redis缓存反序列化失败 - userId={}, error={}", userId, e.getMessage(), e);
                }
            }

            // 5. 查 DB
            log.info("[缓存未命中] 多级缓存全未命中，查询数据库 - 图表列表 - userId={}, page={}, size={}", userId, current, pageSize);
            result = queryFromDb(chartQueryRequest);
            if (result != null) {
                // 写入 Redis
                String json = gson.toJson(result);
                long ttl = REDIS_DATA_TTL_SECONDS + (long) (Math.random() * 2 * REDIS_DATA_TTL_RANDOM_OFFSET - REDIS_DATA_TTL_RANDOM_OFFSET);
                stringRedisTemplate.opsForValue().set(cacheKey, json, ttl, TimeUnit.SECONDS);
                // 写入 Caffeine
                caffeineCache.put(caffeineKey, result);
                log.info("[缓存写入] 数据库查询结果写入Redis和Caffeine - 图表列表 - userId={}, page={}, size={}, ttl={}s", userId, current, pageSize, ttl);
            }

            return result;

        } catch (Exception e) {
            log.error("获取图表列表缓存失败", e);
            return queryFromDb(chartQueryRequest);
        }
    }

    /**
     * 获取回收站列表（带缓存）
     */
    public Page<Chart> getMyDeletedChartListWithCache(ChartQueryRequest chartQueryRequest) {
        if (chartQueryRequest == null) {
            return null;
        }

        Long userId = chartQueryRequest.getUserId();
        long current = chartQueryRequest.getCurrent();
        long pageSize = chartQueryRequest.getPageSize();

        try {
            String cacheKey = REDIS_DELETE_LIST_KEY_PREFIX + userId + ":p" + current + ":s" + pageSize;

            // 查 Redis
            String redisValue = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StringUtils.isNotBlank(redisValue)) {
                try {
                    @SuppressWarnings("unchecked")
                    Page<Chart> cachedPage = gson.fromJson(redisValue, Page.class);
                    Page<Chart> result = cachedPage;
                    if (result != null) {
                        log.info("[缓存命中] Redis缓存命中 - 回收站列表 - userId={}, page={}, size={}", userId, current, pageSize);
                        return result;
                    }
                } catch (Exception e) {
                    log.error("[缓存错误] 回收站列表Redis缓存反序列化失败 - userId={}, error={}", userId, e.getMessage(), e);
                }
            }

            // 查 DB
            log.info("[缓存未命中] Redis缓存未命中，查询数据库 - 回收站列表 - userId={}, page={}, size={}", userId, current, pageSize);
            Page<Chart> chartPage = new Page<>(current, pageSize);
            chartPage = chartService.listMyDeletedChartByPage(chartPage, chartQueryRequest);

            if (chartPage != null) {
                // 写入 Redis
                String json = gson.toJson(chartPage);
                stringRedisTemplate.opsForValue().set(cacheKey, json, 60, TimeUnit.SECONDS);
                log.info("[缓存写入] 数据库查询结果写入Redis - 回收站列表 - userId={}, page={}, size={}", userId, current, pageSize);
            }

            return chartPage;

        } catch (Exception e) {
            log.error("获取回收站列表缓存失败", e);
            Page<Chart> chartPage = new Page<>(current, pageSize);
            return chartService.listMyDeletedChartByPage(chartPage, chartQueryRequest);
        }
    }

    /**
     * 使我的图表列表缓存失效（版本号自增）
     */
    public void evictMyChartList(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }

        try {
            String versionKey = REDIS_VERSION_KEY_PREFIX + userId + ":v";
            Long newVersion = stringRedisTemplate.opsForValue().increment(versionKey);
            log.info("[缓存删除] 图表列表缓存版本号自增 - userId={}, 新版本号={}", userId, newVersion);
        } catch (Exception e) {
            log.error("[缓存删除失败] 使图表列表缓存失效异常 - userId={}, error={}", userId, e.getMessage(), e);
        }
    }

    /**
     * 使回收站列表缓存失效
     */
    public void evictMyDeletedChartList(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }

        try {
            // 回收站列表使用简单的 Key 模式，需要删除所有相关 Key
            // 这里简化处理，只删除常见的前几页
            int deletedCount = 0;
            for (int page = 1; page <= 3; page++) {
                for (int size = 10; size <= 20; size += 10) {
                    String cacheKey = REDIS_DELETE_LIST_KEY_PREFIX + userId + ":p" + page + ":s" + size;
                    Boolean deleted = stringRedisTemplate.delete(cacheKey);
                    if (Boolean.TRUE.equals(deleted)) {
                        deletedCount++;
                    }
                }
            }
            log.info("[缓存删除] 回收站列表缓存已删除 - userId={}, 删除键数量={}", userId, deletedCount);
        } catch (Exception e) {
            log.error("[缓存删除失败] 使回收站列表缓存失效异常 - userId={}, error={}", userId, e.getMessage(), e);
        }
    }

    /**
     * 判断是否应该使用缓存
     * 仅对简单条件缓存：name 为空、chartType 为空、current 在前 3 页内
     */
    private boolean shouldUseCache(ChartQueryRequest request) {
        // name 不为空，不使用缓存
        if (StringUtils.isNotBlank(request.getName())) {
            return false;
        }
        // chartType 不为空，不使用缓存
        if (StringUtils.isNotBlank(request.getChartType())) {
            return false;
        }
        // current 不在前 3 页，不使用缓存
        if (request.getCurrent() > 3) {
            return false;
        }
        return true;
    }

    /**
     * 从 DB 查询
     */
    private Page<ChartListVO> queryFromDb(ChartQueryRequest chartQueryRequest) {
        long current = chartQueryRequest.getCurrent();
        long size = chartQueryRequest.getPageSize();

        // 构建查询条件（复用 Controller 的逻辑）
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Chart> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        queryWrapper.eq(chartQueryRequest.getUserId() != null, "userId", chartQueryRequest.getUserId());
        queryWrapper.like(StringUtils.isNotBlank(chartQueryRequest.getName()), "name", chartQueryRequest.getName());
        queryWrapper.eq(StringUtils.isNotBlank(chartQueryRequest.getChartType()), "chartType", chartQueryRequest.getChartType());
        queryWrapper.eq("isDelete", false);
        queryWrapper.orderByDesc("updateTime");

        Page<Chart> chartPage = chartService.page(new Page<>(current, size), queryWrapper);

        // 转换为 ChartListVO
        Page<ChartListVO> chartListVOPage = new Page<>(chartPage.getCurrent(), chartPage.getSize(), chartPage.getTotal());
        List<ChartListVO> chartListVOList = chartPage.getRecords().stream()
                .map(ChartListVO::objToVo)
                .collect(Collectors.toList());
        chartListVOPage.setRecords(chartListVOList);

        return chartListVOPage;
    }
}

