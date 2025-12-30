package com.yupi.springbootinit.model.vo;

import com.yupi.springbootinit.model.entity.Chart;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;

/**
 * 图表列表视图（不包含大字段）
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@Data
public class ChartListVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 图表名称
     */
    private String name;

    /**
     * 分析目标
     */
    private String goal;

    /**
     * 图表类型
     */
    private String chartType;

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    private Integer isDelete;

    /**
     * 图表状态
     */
    private String status;

    /**
     * 错误信息
     */
    private String execMessage;

    /**
     * 对象转包装类
     *
     * @param chart
     * @return
     */
    public static ChartListVO objToVo(Chart chart) {
        if (chart == null) {
            return null;
        }
        ChartListVO chartListVO = new ChartListVO();
        BeanUtils.copyProperties(chart, chartListVO);
        return chartListVO;
    }

    private static final long serialVersionUID = 1L;
}

