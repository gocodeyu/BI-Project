package com.yupi.springbootinit.job;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.enums.GenChartStatusEnum;
import com.yupi.springbootinit.service.BiAsyncService;
import com.yupi.springbootinit.service.ChartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

@Component
@Slf4j
public class ChartScheduler {
    @Resource
    private ChartService chartService;

    @Resource
    private BiAsyncService biAsyncService;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    /**
     * 任务1：重试机制
     * 每分钟执行一次，查找 "失败" 状态的任务，如果是“系统繁忙”或“超时”导致的，重新放入队列
     */
    @Scheduled(cron = "0 0/1 * * * ?") // 每分钟执行一次
    public void doRetryFailedCharts() {
        // 构造查询条件：(Status = FAILED) AND (msg like "系统繁忙" OR msg like "超时")
        QueryWrapper<Chart> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", GenChartStatusEnum.FAILED.getValue());

        // 关键逻辑：只重试因为资源不足或网络波动导致的问题
        queryWrapper.and(wrapper -> wrapper
                .like("execMessage", "系统繁忙")
                .or()
                .like("execMessage", "超时")
        );

        List<Chart> failedChartList = chartService.list(queryWrapper);
        if (failedChartList.isEmpty()) {
            return;
        }

        log.info("开始执行失败图表补偿任务，数量: {}", failedChartList.size());

        for (Chart chart : failedChartList) {
            // 1. 建议（可选）：判断重试次数，防止无限重试
            // if (chart.getRetryNum() > 3) continue;

            // 2. 重新把状态改回 WAIT
            chart.setStatus(GenChartStatusEnum.WAIT.getValue());
            chart.setExecMessage(""); // 清空错误信息
            chartService.updateById(chart);

            // 3. 重新提交到线程池
            try {
                CompletableFuture.runAsync(() -> {
                    biAsyncService.executeGenChart(chart.getId());
                }, threadPoolExecutor);
            } catch (Exception e) {
                log.error("补偿任务重新提交失败, chartId: {}", chart.getId(), e);
                // 这里如果又报错（队列又满），下一分钟定时任务会再次捞起它
            }
        }
    }

    /**
     * 任务2：兜底机制（清理僵尸任务）
     * 每 10 分钟执行一次
     * 查找状态一直卡在 RUNNING 且更新时间是很久之前的任务，强制标记为失败
     */
    @Scheduled(cron = "0 0/10 * * * ?")
    public void doCleanStuckRunningTasks() {
        // 定义“僵尸”标准：处于 RUNNING 状态，且 updateTime 在 30 分钟之前
        // 说明这个任务跑了30分钟还没完，或者服务器在执行过程中宕机了
        Date thirtyMinutesAgo = new Date(System.currentTimeMillis() - 30 * 60 * 1000);

        QueryWrapper<Chart> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", GenChartStatusEnum.RUNNING.getValue());
        queryWrapper.lt("updateTime", thirtyMinutesAgo);

        List<Chart> stuckList = chartService.list(queryWrapper);
        if (stuckList.isEmpty()) {
            return;
        }

        log.warn("发现僵尸任务，数量: {}，将强制标记为失败", stuckList.size());

        for (Chart chart : stuckList) {
            chart.setStatus(GenChartStatusEnum.FAILED.getValue());
            chart.setExecMessage("任务执行超时或系统异常（如服务器重启），被自动清理");

            boolean res = chartService.updateById(chart);
            if (!res) {
                log.error("清理僵尸任务失败，chartId: {}", chart.getId());
            }
        }
    }

    // 在 ChartScheduler 类中新增

    /**
     * 补偿机制：捞起那些因为起步失败（DB抖动）而一直停留在 WAIT 状态的任务
     * 比如：任务创建了 5 分钟了，还是 WAIT，说明之前的提交线程可能挂了且没改状态
     */
    @Scheduled(cron = "0 0/5 * * * ?") // 每5分钟
    public void doRetryStaleWaitTasks() {
        // 5分钟前
        Date fiveMinutesAgo = new Date(System.currentTimeMillis() - 5 * 60 * 1000);

        QueryWrapper<Chart> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", GenChartStatusEnum.WAIT.getValue());
        queryWrapper.lt("createTime", fiveMinutesAgo); // 创建超过5分钟还在等

        List<Chart> staleList = chartService.list(queryWrapper);
        for (Chart chart : staleList) {
            log.info("发现长期等待的任务，重新提交: {}", chart.getId());
            // 重新提交到线程池
            CompletableFuture.runAsync(() -> biAsyncService.executeGenChart(chart.getId()), threadPoolExecutor);
        }
    }
}
