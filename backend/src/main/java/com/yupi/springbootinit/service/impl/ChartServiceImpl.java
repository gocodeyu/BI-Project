package com.yupi.springbootinit.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.springbootinit.constant.CommonConstant;
import com.yupi.springbootinit.model.dto.chart.ChartQueryRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.utils.SqlUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 *
 */
@Service
public class ChartServiceImpl extends ServiceImpl<ChartMapper, Chart>
    implements ChartService{
    // ChartServiceImpl.java
    @Override
    public Page<Chart> listMyDeletedChartByPage(Page<Chart> page, ChartQueryRequest chartQueryRequest) {
        // 复用你写的 getQueryWrapperDelete 方法（注意要把里面的 isDelete=1 去掉，因为 XML 里写了）
        QueryWrapper<Chart> queryWrapper = getQueryWrapperDelete(chartQueryRequest);
        return baseMapper.queryListDelete(page, queryWrapper);
    }
    private QueryWrapper<Chart> getQueryWrapperDelete(ChartQueryRequest chartQueryRequest) {
        QueryWrapper<Chart> queryWrapper = new QueryWrapper<>();
        if (chartQueryRequest == null) {
            return queryWrapper;
        }
        Long id = chartQueryRequest.getId();
        String name = chartQueryRequest.getName();
        String goal = chartQueryRequest.getGoal();
        String chartType = chartQueryRequest.getChartType();
        Long userId = chartQueryRequest.getUserId();
        String sortField = chartQueryRequest.getSortField();
        String sortOrder = chartQueryRequest.getSortOrder();

        queryWrapper.eq(id != null && id > 0, "id", id);
        queryWrapper.like(StringUtils.isNotBlank(name), "name", name);
        queryWrapper.eq(StringUtils.isNotBlank(goal), "goal", goal);
        queryWrapper.eq(StringUtils.isNotBlank(chartType), "chartType", chartType);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq("isDelete", true);
        // [修改] 设置默认排序：如果没有指定排序字段，则默认按 updateTime 倒序
        // 这样可以保证最新修改或创建的图表排在最前面
        if (StringUtils.isBlank(sortField)) {
            sortField = "updateTime";
            sortOrder = CommonConstant.SORT_ORDER_DESC;
        }
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }
    public boolean createChart(Chart chart) {
        // 1. 【关键步骤】利用 MP 的工具类手动提前生成 ID
        long generatedId = IdWorker.getId();

        // 2. 将 ID 赋值给对象
        chart.setId(generatedId);

        // 3. 使用这个 ID 生成关联字段
        chart.setChartData("数据存储在分表：chart_" + generatedId);

        // 4. 保存
        // MyBatis-Plus 发现 ID 已经有值了，就不会再次自动生成，而是直接使用你填入的值
       return this.save(chart);
    }

}




