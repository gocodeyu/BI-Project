package com.yupi.springbootinit.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.springbootinit.model.entity.ChartFavour;
import com.baomidou.mybatisplus.extension.service.IService;
import com.yupi.springbootinit.model.vo.ChartListVO;

/**
 * 图表收藏服务
 *
 * @author yupi
 */
public interface ChartFavourService extends IService<ChartFavour> {

    /**
     * 收藏/取消收藏图表（使用分布式锁）
     *
     * @param chartId 图表ID
     * @param userId  用户ID
     * @return 1-收藏成功, -1-取消收藏成功, 0-操作失败
     */
    int doChartFavour(long chartId, long userId);

    /**
     * 获取用户收藏列表（多级缓存）
     *
     * @param current 当前页
     * @param size    每页大小
     * @param userId  用户ID
     * @return 分页结果
     */
    Page<ChartListVO> listMyFavourChartByPage(long current, long size, long userId);

    /**
     * 判断用户是否已收藏
     *
     * @param chartId 图表ID
     * @param userId  用户ID
     * @return true-已收藏, false-未收藏
     */
    boolean isFavour(long chartId, long userId);
}




