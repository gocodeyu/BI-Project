-- 图表收藏表
CREATE TABLE IF NOT EXISTS `chart_favour` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `chartId` bigint NOT NULL COMMENT '图表 id',
  `userId` bigint NOT NULL COMMENT '创建用户 id',
  `createTime` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chart_user` (`chartId`,`userId`),
  INDEX `idx_userId` (`userId`),
  INDEX `idx_chartId` (`chartId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图表收藏';




