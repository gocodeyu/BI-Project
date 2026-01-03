package com.yupi.springbootinit.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import com.github.rholder.retry.WaitStrategies;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.manager.AiPrompt;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.enums.GenChartStatusEnum;
import com.yupi.springbootinit.service.BiAsyncService;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.SseNotifyService;
import com.yupi.springbootinit.service.cache.ChartCacheService;
import com.yupi.springbootinit.service.cache.ChartDataCacheService;
import com.yupi.springbootinit.service.cache.ChartListCacheService;
import com.yupi.springbootinit.utils.ExcelUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
@Slf4j
public class BiAsyncServiceImpl implements BiAsyncService {
    @Resource
    private ChartService chartService;
    @Resource
    private ChartMapper chartMapper;
    @Resource
    private AiPrompt aiPrompt;
    @Resource
    private ChartCacheService chartCacheService;
    @Resource
    private ChartListCacheService chartListCacheService;
    @Resource
    private ChartDataCacheService chartDataCacheService;
    @Resource
    private SseNotifyService sseNotifyService;

    // AI 专用线程池 (aiExecutor)：专门“伺候”不稳定的 AI 服务。
    private final ThreadPoolExecutor aiExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10));
    public void executeGenChart(long chartId) {
        // 1. 幂等性检查：查询当前状态
        Chart chart = chartService.getById(chartId);
        if (chart == null) {
            log.warn("图表不存在，跳过执行: chartId={}", chartId);
            return;
        }

        // 2. 状态检查：只有 WAIT 状态才能执行（幂等性保证）
        String currentStatus = chart.getStatus();
        if (!GenChartStatusEnum.WAIT.getValue().equals(currentStatus)) {
            log.info("图表状态不是 WAIT，跳过执行（幂等性保证）: chartId={}, currentStatus={}", chartId, currentStatus);
            return; // 已处理过的请求直接返回，确保幂等性
        }

        // 3. CAS 更新：只有 WAIT 状态才能更新为 RUNNING（原子性保证）
        int updateCount = chartMapper.updateStatusFromWaitToRunning(chartId);
        if (updateCount <= 0) {
            log.warn("状态更新失败（可能已被其他线程处理），跳过执行: chartId={}", chartId);
            return; // CAS 更新失败，说明已被其他线程处理，确保幂等性
        }

        log.info("成功将图表状态更新为 RUNNING: chartId={}", chartId);

        try {
            // 4. 获取数据（chart 变量已在第52行定义）
            String goal = chart.getGoal();
            String chartType = chart.getChartType();
            String tableName = "chart_" + chartId;
            // 查询数据
            List<Map<String, Object>> chartDataList = chartMapper.queryChartData(tableName);
            if (CollUtil.isEmpty(chartDataList)) {
                //不会被放到线程池中
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表数据为空");

            }
            String csvData = ExcelUtils.mapToString(chartDataList);
            // 3. 构造重试器 (优化点1：Guava Retrying)
            Retryer<String> retryer = RetryerBuilder.<String>newBuilder()
                    .retryIfException() // 如果抛出异常就重试
                    .withWaitStrategy(WaitStrategies.fixedWait(2, TimeUnit.SECONDS)) // 每次等待2秒
                    .withStopStrategy(StopStrategies.stopAfterAttempt(3)) // 最多尝试3次
                    .build();

            // 4. 调用 AI (优化点3：超时控制)
            // 我们将 retryer.call 放入一个 Future 中，利用 get(timeout) 来控制总时长
            String result = CompletableFuture.supplyAsync(() -> {
                try {
                    return retryer.call(() -> aiPrompt.func(goal, chartType, csvData));
                } catch (Exception e) {
                    throw new RuntimeException("AI 生成失败: " + e.getMessage());
                }
            }, aiExecutor).get(2, TimeUnit.MINUTES); // 设置整个AI生成过程(含重试)不能超过2分钟

            // 5. 解析结果
            String[] splits = result.split("【【【【【");
            if (splits.length < 3) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成格式异常");
            }
            String genChart = splits[1].trim().replace("```json", "").replace("```", "").trim();
            String genResult = splits[2].trim();

            // 6. 更新数据库为成功
            Chart updateChartSuccess = new Chart();
            updateChartSuccess.setId(chartId);
            updateChartSuccess.setGenChart(genChart);
            updateChartSuccess.setGenResult(genResult);
            updateChartSuccess.setStatus(GenChartStatusEnum.SUCCEED.getValue());
            boolean res=chartService.updateById(updateChartSuccess);
            if (!res) {//不会被放到线程池中
                // 这一步非常重要：如果连报错都写不进去，必须打印 ERROR 日志！
                log.error("【严重】试图更新图表失败状态也失败了！可能是数据库挂了。chartId: {}", chartId);
                handleChartUpdateError(chartId, "更新图表状态失败");
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图表生成成功，但更新状态到数据库失败");

            }

            // 7. 删除缓存（写库成功后）
            evictChartCache(chartId, chart.getUserId());

            // 8. 推送 SSE 通知
            log.info("【SSE】准备推送任务完成通知: chartId={}, userId={}, status=succeed", chartId, chart.getUserId());
            sseNotifyService.publishTaskNotification(chart.getUserId(), chartId, "succeed", "图表生成成功");
            log.info("【SSE】任务完成通知已调用");

        } catch (TimeoutException e) {
            log.error("AI生成超时, chartId: {}", chartId);
            String errorMessage = "AI生成超时，系统自动终止";
            handleChartUpdateError(chartId, errorMessage);
            // 获取图表信息用于删除缓存和推送通知（chart 变量已在方法开头定义）
            if (chart != null) {
                evictChartCache(chartId, chart.getUserId());
                sseNotifyService.publishTaskNotification(chart.getUserId(), chartId, "failed", errorMessage);
            }
        } catch (Exception e) {
            log.error("AI生成异步任务失败, chartId: {}", chartId, e);
            String errorMessage = "执行失败: " + e.getMessage();
            handleChartUpdateError(chartId, errorMessage);
            // 获取图表信息用于删除缓存和推送通知（chart 变量已在方法开头定义）
            if (chart != null) {
                evictChartCache(chartId, chart.getUserId());
                sseNotifyService.publishTaskNotification(chart.getUserId(), chartId, "failed", errorMessage);
            }
        }
    }

    private void handleChartUpdateError(long chartId, String execMessage) {
        Chart updateChart = new Chart();
        updateChart.setId(chartId);
        updateChart.setStatus(GenChartStatusEnum.FAILED.getValue());
        updateChart.setExecMessage(execMessage);

        try{
            boolean res = chartService.updateById(updateChart);
            if(!res){//不会被放到线程池中
                // 这一步非常重要：如果连报错都写不进去，必须打印 ERROR 日志！
                log.error("【严重】更新图表FAILED状态失败！可能数据库故障。chartId: {}", chartId);
            } else {
                // 删除缓存
                Chart chart = chartService.getById(chartId);
                if (chart != null) {
                    evictChartCache(chartId, chart.getUserId());
                }
            }
        }catch (Exception e){
            log.error("更新图表失败状态也失败了！可能是数据库挂了。chartId: {}", chartId, e);
        }

    }

    /**
     * 统一的缓存删除方法
     */
    private void evictChartCache(Long chartId, Long userId) {
        if (chartId != null && chartId > 0) {
            chartCacheService.evictChart(chartId);
            chartDataCacheService.evictChartData(chartId);
        }
        if (userId != null && userId > 0) {
            chartListCacheService.evictMyChartList(userId);
        }
    }
}
