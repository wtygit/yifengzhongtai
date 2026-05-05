-- ============================================================
-- 仅创建 jimu_report_export_job 表（积木报表自动导出任务）
-- 当启动报错：Table 'xxx.jimu_report_export_job' doesn't exist 时，
-- 在服务器 MySQL 的 jimureport 库中执行本文件即可。
-- 执行前请确认：USE jimureport; 或已选择 jimureport 库
-- ============================================================

USE `jimureport`;

SET NAMES utf8mb4;

-- ----------------------------
-- Table structure for jimu_report_export_job
-- ----------------------------
DROP TABLE IF EXISTS `jimu_report_export_job`;
CREATE TABLE `jimu_report_export_job`  (
  `id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主键',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '任务名称',
  `begin_time` datetime NULL DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '结束时间',
  `exec_interval` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '执行频率',
  `report_conf` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '导出报表配置',
  `last_run_time` datetime NULL DEFAULT NULL COMMENT '最后执行时间',
  `receiver_email` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '接收通知的邮件',
  `file_sync_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '文件同步路径',
  `status` int NULL DEFAULT NULL COMMENT '状态(0:停止;1:启动)',
  `create_by` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL COMMENT '修改人',
  `update_time` datetime NULL DEFAULT NULL COMMENT '修改时间',
  `tenant_id` varchar(10) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL COMMENT '多租户标识',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '积木报表导出计划表' ROW_FORMAT = DYNAMIC;

-- 无需初始数据，空表即可满足启动查询
