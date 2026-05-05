-- ============================================================
-- 多表关联与字段组合：配置持久化表
-- 用途：
-- - 保存拖拽建模配置（config_json）与生成SQL（sql_template）
-- - 便于二次编辑与复用
-- ============================================================

USE `jimureport`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for jm_table_association
-- ----------------------------
DROP TABLE IF EXISTS `jm_table_association`;
CREATE TABLE `jm_table_association` (
  `id` varchar(36) NOT NULL COMMENT '主键（可与数据集ID一致）',
  `name` varchar(100) NOT NULL COMMENT '关联配置名称/数据集名称',
  `description` text DEFAULT NULL COMMENT '描述',
  `data_source_id` varchar(36) DEFAULT NULL COMMENT '数据源ID（jimu_report_data_source.id）',
  `sql_template` longtext DEFAULT NULL COMMENT '生成的SQL（保存到数据集用）',
  `physical_table` varchar(128) DEFAULT NULL COMMENT '物化表名（多表关联落表）',
  `config_json` longtext DEFAULT NULL COMMENT '完整拖拽配置JSON',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_jm_ta_ds` (`data_source_id`) USING BTREE,
  KEY `idx_jm_ta_ut` (`update_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='多表关联配置主表';

-- ----------------------------
-- Table structure for jm_association_table
-- ----------------------------
DROP TABLE IF EXISTS `jm_association_table`;
CREATE TABLE `jm_association_table` (
  `id` varchar(36) NOT NULL COMMENT '主键',
  `association_id` varchar(36) NOT NULL COMMENT '关联配置ID（jm_table_association.id）',
  `table_name` varchar(100) NOT NULL COMMENT '表名',
  `alias` varchar(50) NOT NULL COMMENT '表别名',
  `order_num` int DEFAULT 0 COMMENT '排序',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_jm_at_aid` (`association_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='关联表配置';

-- ----------------------------
-- Table structure for jm_association_join
-- ----------------------------
DROP TABLE IF EXISTS `jm_association_join`;
CREATE TABLE `jm_association_join` (
  `id` varchar(36) NOT NULL COMMENT '主键',
  `association_id` varchar(36) NOT NULL COMMENT '关联配置ID（jm_table_association.id）',
  `left_table_alias` varchar(50) NOT NULL COMMENT '左表别名',
  `left_field` varchar(100) NOT NULL COMMENT '左字段',
  `join_type` varchar(20) NOT NULL COMMENT 'JOIN类型',
  `right_table_alias` varchar(50) NOT NULL COMMENT '右表别名',
  `right_field` varchar(100) NOT NULL COMMENT '右字段',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_jm_aj_aid` (`association_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='关联条件';

-- ----------------------------
-- Table structure for jm_association_field
-- ----------------------------
DROP TABLE IF EXISTS `jm_association_field`;
CREATE TABLE `jm_association_field` (
  `id` varchar(36) NOT NULL COMMENT '主键',
  `association_id` varchar(36) NOT NULL COMMENT '关联配置ID（jm_table_association.id）',
  `table_alias` varchar(50) NOT NULL COMMENT '表别名',
  `field_name` varchar(100) NOT NULL COMMENT '字段名',
  `alias` varchar(100) DEFAULT NULL COMMENT '字段别名',
  `order_num` int DEFAULT 0 COMMENT '排序',
  `is_selected` tinyint(1) DEFAULT 1 COMMENT '是否选中',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_jm_af_aid` (`association_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='字段配置';

SET FOREIGN_KEY_CHECKS = 1;

