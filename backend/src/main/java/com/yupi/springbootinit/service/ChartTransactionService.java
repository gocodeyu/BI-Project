package com.yupi.springbootinit.service;

import com.yupi.springbootinit.model.entity.Chart;

import java.util.List;

/**
 * 图表事务服务
 * 用于处理需要事务保证的图表操作
 */
public interface ChartTransactionService {
    
    /**
     * 事务内创建图表并发送MQ消息
     * 保证数据库操作和消息发送的原子性
     * 
     * @param chart 图表对象（ID会在方法内生成）
     * @param headers 表头
     * @param dataRows 数据行
     * @param isVip 是否VIP用户
     * @return 图表ID
     */
    Long createChartWithTransaction(Chart chart, List<String> headers, 
                                    List<List<Object>> dataRows, boolean isVip);
    
    /**
     * 事务内更新图表状态并发送MQ消息（用于重试）
     * 
     * @param chartId 图表ID
     * @param isVip 是否VIP用户
     */
    void updateChartStatusAndSendMessage(Long chartId, boolean isVip);
    
    /**
     * 事务内更新图表信息并发送MQ消息（用于编辑重新生成，不更新数据表）
     * 
     * @param chart 图表对象
     * @param isVip 是否VIP用户
     */
    void updateChartOnlyAndSendMessage(Chart chart, boolean isVip);
    
    /**
     * 事务内更新图表信息并发送MQ消息（用于编辑重新生成，需要更新数据表）
     * 
     * @param chart 图表对象
     * @param tableName 表名
     * @param headers 表头
     * @param dataRows 数据行
     * @param isVip 是否VIP用户
     */
    void updateChartAndSendMessage(Chart chart, String tableName, List<String> headers,
                                   List<List<Object>> dataRows, boolean isVip);
}

