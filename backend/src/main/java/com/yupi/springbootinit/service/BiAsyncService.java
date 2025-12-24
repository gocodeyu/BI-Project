package com.yupi.springbootinit.service;

public interface BiAsyncService {
    /**
     * 核心异步方法：执行图表生成
     * @param chartId 图表ID
     */
    public void executeGenChart(long chartId);

}
