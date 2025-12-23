package com.yupi.springbootinit.mapper;

import com.yupi.springbootinit.model.entity.Chart;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * @Entity com.yupi.springbootinit.model.entity.Chart
 */
public interface ChartMapper extends BaseMapper<Chart> {
    /**
     * 动态创建数据表
     * 结构：chart_{数据表id}
     *
     * @param tableName 表名
     * @Param colNames 列名
     */
    void createChartTable(@Param("tableName") String tableName, @Param("colNames") List<String> colNames);

    /**
     * 动态插入数据
     *
     * @param tableName 表名
     * @Param colNames 列名
     * @Param dataList 数据列表
     */
    void insertChartData(@Param("tableName") String tableName, @Param("colNames") List<String>colNames, @Param("dataList") List<List<Object>> dataList);

    /**
     * 动态查询数据化表
     * @param tableName 表名
     * @Param colNames 列名
     *
     */
    List<Map<String,Object>> queryChartDataByColNames(@Param("tableName") String tableName, @Param("colNames") List<String>colNames);

    /**
     * 动态查询表中所有数据
     * @param tableName 表名
     */
    List<Map<String,Object>> queryChartData(@Param("tableName") String tableName);

}




