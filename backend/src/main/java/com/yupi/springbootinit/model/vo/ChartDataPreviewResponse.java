package com.yupi.springbootinit.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 图表数据预览响应
 *
 * @author yupi
 */
@Data
public class ChartDataPreviewResponse implements Serializable {

    /**
     * 表头列表
     */
    private List<String> headers;

    /**
     * 数据列表
     */
    private List<List<String>> data;

    /**
     * 总记录数
     */
    private long total;

    private static final long serialVersionUID = 1L;
}