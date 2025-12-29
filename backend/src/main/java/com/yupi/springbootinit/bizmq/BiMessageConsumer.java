package com.yupi.springbootinit.bizmq;

import cn.hutool.core.collection.CollUtil;
import com.rabbitmq.client.Channel;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.config.RabbitMqConfig;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.manager.AiPrompt;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.enums.GenChartStatusEnum;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.utils.ExcelUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
public class BiMessageConsumer {

    @Resource
    private ChartService chartService;
    @Resource
    private AiPrompt aiPrompt;
    @Resource
    private ChartMapper chartMapper;

    /**
     * 自定义异常：用于标记需要重试的场景
     */
    private static class RetryableException extends RuntimeException {
        public RetryableException(String message, Throwable cause) {
            super(message, cause);
        }
        public RetryableException(String message) {
            super(message);
        }
    }

    /**
     * 监听 VIP 队列
     */
    @RabbitListener(queues = RabbitMqConfig.BI_VIP_QUEUE_NAME, concurrency = "5-10", ackMode = "MANUAL")
    public void receiveVipMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            log.info("VIP 消费者收到消息: {}", message);
            processMessage(message);
            // 成功处理，手动 Ack
            channel.basicAck(deliveryTag, false);
        } catch (RetryableException e) {
            // 1. 捕获可重试异常：抛出异常，让 Spring AMQP 自动重试
            log.warn("VIP队列出现可重试异常，等待 Spring 重试。消息: {}, 错误: {}", message, e.getMessage());
            throw e;
        } catch (Exception e) {
            // 2. 捕获不可重试异常（或未知致命错误）：直接拒绝，不重回队列（进入死信）
            log.error("VIP队列出现不可重试异常，拒绝消息进入死信。消息: {}", message, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 监听普通队列
     */
    @RabbitListener(queues = RabbitMqConfig.BI_COMMON_QUEUE_NAME, concurrency = "1-2", ackMode = "MANUAL")
    public void receiveCommonMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            log.info("普通消费者收到消息: {}", message);
            processMessage(message);
            channel.basicAck(deliveryTag, false);
        } catch (RetryableException e) {
            log.warn("普通队列出现可重试异常，等待 Spring 重试。消息: {}, 错误: {}", message, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("普通队列出现不可重试异常，拒绝消息进入死信。消息: {}", message, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 核心业务处理逻辑（提取出来通用）
     * 该方法负责区分异常类型，将异常包装为 RetryableException 或 BusinessException
     */
    private void processMessage(String message) {
        // --- 1. 参数校验（不可重试） ---
        if (StringUtils.isBlank(message)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息为空");
        }
        long chartId = Long.parseLong(message);
        Chart chart = chartService.getById(chartId);
        if (chart == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表不存在");
        }

        // --- 2. 更新状态为执行中（可重试） ---
        // 这里的更新失败通常是数据库连接抖动，建议重试
        Chart updateChartRunning = new Chart();
        updateChartRunning.setId(chartId);
        updateChartRunning.setStatus(GenChartStatusEnum.RUNNING.getValue());
        boolean b = chartService.updateById(updateChartRunning);
        if (!b) {
            throw new RetryableException("更新图表状态为Running失败");
        }

        // --- 3. 调用 AI 服务 ---
        String result;
        try {
            result = CompletableFuture.supplyAsync(() -> {
                String csvData = getCsvData(chartId);
                return aiPrompt.func(chart.getGoal(), chart.getChartType(), csvData);
            }).get(2, TimeUnit.MINUTES); // 2分钟超时
        } catch (TimeoutException e) {
            // A. 超时异常 -> 视为可重试（网络慢等原因）
            throw new RetryableException("AI生成超时", e);
        } catch (ExecutionException e) {
            // B. 执行异常 -> 需要解包看是 AI 内部报错还是什么
            Throwable cause = e.getCause();
            if (cause instanceof BusinessException) {
                // 如果是 BusinessException (如 Key 错误，Prompt 过长)，不可重试
                throw (BusinessException) cause;
            }
            // 其他未知 AI 错误，保守起见视为可重试
            throw new RetryableException("AI服务调用异常", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException("线程被中断", e);
        }

        // --- 4. 解析结果并保存（关键：处理数据过长问题） ---
        try {
            handleUpdateSuccess(chartId, result);
        } catch (DataAccessException e) {
            // 数据库层面的错误
            if (e.getMessage() != null && e.getMessage().contains("Data too long")) {
                // 数据过长，不可重试！直接记录失败原因
                handleChartUpdateError(chartId, "AI生成的结果过长，无法存入数据库");
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "数据库字段长度不足");
            }
            // 其他数据库连接错误，可重试
            throw new RetryableException("数据库保存异常", e);
        } catch (Exception e) {
            // 其他解析错误等
            if (e instanceof BusinessException) {
                throw (BusinessException) e;
            }
            throw new RetryableException("结果解析或保存未知异常", e);
        }
    }

    /**
     * 获取数据（辅助方法）
     */
    private String getCsvData(long chartId) {
        String tableName = "chart_" + chartId;
        List<Map<String, Object>> chartDataList = chartMapper.queryChartData(tableName);
        if (CollUtil.isEmpty(chartDataList)) {
            // 数据没找到，不可重试
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表数据为空");
        }

        String csvData = ExcelUtils.mapToString(chartDataList);
        if (StringUtils.isBlank(csvData)) {
            // 转换失败，不可重试
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "数据转换CSV失败");
        }
        return csvData;
    }

    /**
     * 处理成功状态
     */
    private void handleUpdateSuccess(long chartId, String result) {
        String[] splits = result.split("【【【【【");
        if (splits.length < 3) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成格式异常");
        }
        String genChart = splits[1].trim().replace("```json", "").replace("```", "").trim();
        String genResult = splits[2].trim();

        Chart updateChartSuccess = new Chart();
        updateChartSuccess.setId(chartId);
        updateChartSuccess.setGenChart(genChart);
        updateChartSuccess.setGenResult(genResult);
        updateChartSuccess.setStatus(GenChartStatusEnum.SUCCEED.getValue());
        boolean update = chartService.updateById(updateChartSuccess);
        if (!update) {
            // 如果这里更新返回 false (非异常)，也抛出异常触发重试
            throw new RuntimeException("更新图表成功状态失败");
        }
    }

    /**
     * 辅助方法：处理失败状态 (用于不可重试场景下，记录具体错误信息)
     */
    private void handleChartUpdateError(long chartId, String execMessage) {
        Chart updateChart = new Chart();
        updateChart.setId(chartId);
        updateChart.setStatus(GenChartStatusEnum.FAILED.getValue());
        updateChart.setExecMessage(execMessage);
        chartService.updateById(updateChart);
    }
}