package com.yupi.springbootinit.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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


    // 必须手动定义一个方法，并用 @Param 传递 Wrapper
    Page<Chart> queryListDelete(@Param("page") Page<Chart> page, @Param("ew") Wrapper<Chart> queryWrapper);

    boolean recoverChart(@Param("id")long id);


    int deleteByIdforever(@Param("id")long id);

    Chart getById(@Param("id")long id);

    int chartTable(@Param("tableName")String tablename);

    /**
     * 分页查询图表数据
     * @param tableName 表名
     * @param offset 偏移量
     * @param pageSize 页面大小
     * @return 数据列表
     */
    List<Map<String, Object>> queryChartDataWithPage(@Param("tableName") String tableName, 
                                                    @Param("offset") long offset, 
                                                    @Param("pageSize") long pageSize);

    /**
     * 查询表总记录数
     * @param tableName 表名
     * @return 总记录数
     */
    long countChartData(@Param("tableName") String tableName);

    /**
     * CAS 更新：只有状态为 WAIT 时才能更新为 RUNNING
     * 用于幂等性保证，防止重复执行
     * @param chartId 图表ID
     * @return 影响的行数，0表示更新失败（当前状态不是WAIT），1表示更新成功
     */
    int updateStatusFromWaitToRunning(@Param("chartId") Long chartId);
}




