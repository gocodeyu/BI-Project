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

    // AI 专用线程池 (aiExecutor)：专门“伺候”不稳定的 AI 服务。
    private final ThreadPoolExecutor aiExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10));
    public void executeGenChart(long chartId) {
        // 1. 先修改状态为“执行中”
        Chart updateChartRunning = new Chart();
        updateChartRunning.setId(chartId);
        updateChartRunning.setStatus(GenChartStatusEnum.RUNNING.getValue());
        boolean b = chartService.updateById(updateChartRunning);
        if (!b) {
            //会通过补偿机制重试
            // 如果这里失败，后续逻辑无意义，直接停止。
            // 依靠定时任务（僵尸清理）来兜底
            log.error("更新图表执行中状态失败，停止执行。chartId: {}", chartId);
            return;
        }

        try {
            // 2. 获取数据 (复用你之前的逻辑)
            Chart chart = chartService.getById(chartId);
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

        } catch (TimeoutException e) {
            log.error("AI生成超时, chartId: {}", chartId);
            handleChartUpdateError(chartId, "AI生成超时，系统自动终止");
        } catch (Exception e) {
            log.error("AI生成异步任务失败, chartId: {}", chartId, e);
            handleChartUpdateError(chartId, "执行失败: " + e.getMessage());
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
            }
        }catch (Exception e){
            log.error("更新图表失败状态也失败了！可能是数据库挂了。chartId: {}", chartId, e);
        }

    }
}
