package com.yupi.springbootinit.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.yupi.springbootinit.bizmq.BiMessageProducer;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.enums.GenChartStatusEnum;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.ChartTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/**
 * 图表事务服务实现类
 * 所有方法都在Spring事务中执行，确保数据库操作和MQ消息发送的原子性
 */
@Service
@Slf4j
public class ChartTransactionServiceImpl implements ChartTransactionService {
    
    @Resource
    private ChartService chartService;
    
    @Resource
    private ChartMapper chartMapper;
    
    @Resource
    private BiMessageProducer biMessageProducer;
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createChartWithTransaction(Chart chart, List<String> headers,
                                          List<List<Object>> dataRows, boolean isVip) {
        log.info("[事务服务] 开始创建图表 - name={}, userId={}", chart.getName(), chart.getUserId());
        
        // 1. 保存图表基本信息（会生成ID）
        boolean saveResult = chartService.createChart(chart);
        if (!saveResult) {
            log.error("[事务服务] 保存图表失败");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存图表失败");
        }
        
        long chartId = chart.getId();
        String tableName = "chart_" + chartId;
        log.info("[事务服务] 图表基本信息保存成功 - chartId={}, tableName={}", chartId, tableName);
        
        // 2. 创建数据表
        chartMapper.createChartTable(tableName, headers);
        log.info("[事务服务] 数据表创建成功 - tableName={}", tableName);
        
        // 3. 批量插入数据
        if (CollUtil.isNotEmpty(dataRows)) {
            int batchSize = 1000;
            for (int i = 0; i < dataRows.size(); i += batchSize) {
                int end = Math.min(i + batchSize, dataRows.size());
                chartMapper.insertChartData(tableName, headers, dataRows.subList(i, end));
            }
            log.info("[事务服务] 数据插入成功 - tableName={}, rows={}", tableName, dataRows.size());
        }
        
        // 4. 发送MQ消息（在事务提交后才会真正发送）
        biMessageProducer.sendMessage(String.valueOf(chartId), isVip);
        log.info("[事务服务] MQ消息已提交（待事务提交后发送） - chartId={}, isVip={}", chartId, isVip);
        
        return chartId;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateChartStatusAndSendMessage(Long chartId, boolean isVip) {
        log.info("[事务服务] 开始更新图表状态 - chartId={}", chartId);
        
        // 1. 检查当前状态，只有 FAILED 状态才能重试（幂等性保证）
        Chart currentChart = chartService.getById(chartId);
        if (currentChart == null) {
            log.error("[事务服务] 图表不存在 - chartId={}", chartId);
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表不存在");
        }
        String currentStatus = currentChart.getStatus();
        if (!GenChartStatusEnum.FAILED.getValue().equals(currentStatus)) {
            log.warn("[事务服务] 图表状态不是 FAILED，不能重试 - chartId={}, currentStatus={}", chartId, currentStatus);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "只有失败状态的图表才能重试，当前状态：" + currentStatus);
        }
        
        // 2. 更新图表状态为等待
        Chart updateChart = new Chart();
        updateChart.setId(chartId);
        updateChart.setStatus(GenChartStatusEnum.WAIT.getValue());
        updateChart.setExecMessage("");
        
        boolean update = chartService.updateById(updateChart);
        if (!update) {
            log.error("[事务服务] 更新图表状态失败 - chartId={}", chartId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新图表状态失败");
        }
        
        log.info("[事务服务] 图表状态更新成功 - chartId={}, status=wait", chartId);
        
        // 3. 发送MQ消息（在事务提交后才会真正发送）
        biMessageProducer.sendMessage(String.valueOf(chartId), isVip);
        log.info("[事务服务] MQ消息已提交（待事务提交后发送） - chartId={}, isVip={}", chartId, isVip);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateChartOnlyAndSendMessage(Chart chart, boolean isVip) {
        log.info("[事务服务] 开始更新图表（不更新数据表）并发送MQ - chartId={}", chart.getId());
        
        // 1. 更新图表状态
        chart.setStatus(GenChartStatusEnum.WAIT.getValue());
        chart.setExecMessage("");
        chart.setGenChart("");
        chart.setGenResult("");
        
        boolean update = chartService.updateById(chart);
        if (!update) {
            log.error("[事务服务] 更新图表失败 - chartId={}", chart.getId());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新图表失败");
        }
        
        log.info("[事务服务] 图表状态更新成功 - chartId={}, status=wait", chart.getId());
        
        // 2. 发送MQ消息（在事务提交后才会真正发送）
        biMessageProducer.sendMessage(String.valueOf(chart.getId()), isVip);
        log.info("[事务服务] MQ消息已提交（待事务提交后发送） - chartId={}, isVip={}", chart.getId(), isVip);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateChartAndSendMessage(Chart chart, String tableName, List<String> headers,
                                         List<List<Object>> dataRows, boolean isVip) {
        log.info("[事务服务] 开始更新图表并重新生成 - chartId={}", chart.getId());
        
        // 1. 更新图表状态
        chart.setStatus(GenChartStatusEnum.WAIT.getValue());
        chart.setExecMessage("");
        chart.setGenChart("");
        chart.setGenResult("");
        
        boolean update = chartService.updateById(chart);
        if (!update) {
            log.error("[事务服务] 更新图表失败 - chartId={}", chart.getId());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新图表失败");
        }
        
        log.info("[事务服务] 图表状态更新成功 - chartId={}, status=wait", chart.getId());
        
        // 2. 如果有新数据，更新数据表
        if (headers != null && CollUtil.isNotEmpty(dataRows)) {
            // 删除旧表
            try {
                chartMapper.chartTable(tableName);
                log.info("[事务服务] 旧数据表已删除 - tableName={}", tableName);
            } catch (Exception e) {
                log.warn("[事务服务] 删除旧数据表失败（可能不存在） - tableName={}", tableName);
            }
            
            // 创建新表
            chartMapper.createChartTable(tableName, headers);
            log.info("[事务服务] 新数据表创建成功 - tableName={}", tableName);
            
            // 批量插入数据
            int batchSize = 1000;
            for (int i = 0; i < dataRows.size(); i += batchSize) {
                int end = Math.min(i + batchSize, dataRows.size());
                chartMapper.insertChartData(tableName, headers, dataRows.subList(i, end));
            }
            log.info("[事务服务] 数据插入成功 - tableName={}, rows={}", tableName, dataRows.size());
        }
        
        // 3. 发送MQ消息（在事务提交后才会真正发送）
        biMessageProducer.sendMessage(String.valueOf(chart.getId()), isVip);
        log.info("[事务服务] MQ消息已提交（待事务提交后发送） - chartId={}, isVip={}", chart.getId(), isVip);
    }
}

