-- 图表点赞表
CREATE TABLE IF NOT EXISTS `chart_thumb` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `chartId` bigint NOT NULL COMMENT '图表 id',
  `userId` bigint NOT NULL COMMENT '创建用户 id',
  `createTime` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chart_user` (`chartId`,`userId`),
  INDEX `idx_chartId` (`chartId`),
  INDEX `idx_userId` (`userId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图表点赞';

-- 在 Chart 表中添加点赞数字段（如果不存在）
ALTER TABLE `chart` 
ADD COLUMN IF NOT EXISTS `thumbNum` int DEFAULT 0 COMMENT '点赞数';




