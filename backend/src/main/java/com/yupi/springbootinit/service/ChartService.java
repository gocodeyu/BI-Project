package com.yupi.springbootinit.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.springbootinit.model.dto.chart.ChartQueryRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 *
 */
public interface ChartService extends IService<Chart> {

    Page<Chart> listMyDeletedChartByPage(Page<Chart> chartPage, ChartQueryRequest chartQueryRequest);
    boolean createChart(Chart chart);
}
