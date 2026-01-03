package com.yupi.springbootinit.service;

import com.yupi.springbootinit.model.vo.ChartListVO;

import java.util.List;

/**
 * 图表排行榜服务
 *
 * @author yupi
 */
public interface ChartRankService {

    /**
     * 获取热门图表排行榜（多级缓存 + 分布式锁防击穿）
     *
     * @param limit 返回数量
     * @return 排行榜列表
     */
    List<ChartListVO> getHotChartRank(int limit);

    /**
     * 刷新排行榜缓存
     */
    void refreshHotRank();
}


