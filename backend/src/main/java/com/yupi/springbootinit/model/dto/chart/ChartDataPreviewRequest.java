package com.yupi.springbootinit.model.dto.chart;

import lombok.Data;

import java.io.Serializable;

/**
 * 图表数据预览请求
 *
 * @author yupi
 */
@Data
public class ChartDataPreviewRequest implements Serializable {

    /**
     * 图表 id
     */
    private Long chartId;

    /**
     * 当前页号
     */
    private long current = 1;

    /**
     * 页面大小
     */
    private long pageSize = 10;

    private static final long serialVersionUID = 1L;
}