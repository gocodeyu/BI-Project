package com.yupi.springbootinit.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yupi.springbootinit.model.entity.ChartThumb;

/**
 * 图表点赞服务
 *
 * @author yupi
 */
public interface ChartThumbService extends IService<ChartThumb> {

    /**
     * 点赞/取消点赞图表（使用分布式锁）
     *
     * @param chartId 图表ID
     * @param userId  用户ID
     * @return 1-点赞成功, -1-取消点赞成功, 0-操作失败
     */
    int doChartThumb(long chartId, long userId);

    /**
     * 获取图表点赞数（多级缓存）
     *
     * @param chartId 图表ID
     * @return 点赞数
     */
    int getThumbNum(long chartId);

    /**
     * 判断用户是否已点赞
     *
     * @param chartId 图表ID
     * @param userId  用户ID
     * @return true-已点赞, false-未点赞
     */
    boolean isThumb(long chartId, long userId);
}

