-- ============================================================
-- 仅创建 jimu_report_category 表（积木报表分类）
-- 当服务器报错：Table 'xxx.jimu_report_category' doesn't exist 时，
-- 在服务器 MySQL 的 jimureport 库中执行本文件即可。
-- 执行前请确认：USE jimureport; 或已选择 jimureport 库
-- ============================================================

USE `jimureport`;

SET NAMES utf8mb4;

-- ----------------------------
-- Table structure for jimu_report_category
-- ----------------------------
DROP TABLE IF EXISTS `jimu_report_category`;
CREATE TABLE `jimu_report_category`  (
  `id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '主键',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '分类名称',
  `parent_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '父级id',
  `iz_leaf` int NULL DEFAULT NULL COMMENT '是否为叶子节点(0 否 1是)',
  `source_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '来源类型( report 积木报表 screen 大屏  drag 仪表盘)',
  `create_by` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL COMMENT '创建人',
  `create_time` timestamp NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL COMMENT '更新人',
  `update_time` timestamp NULL DEFAULT NULL COMMENT '更新时间',
  `tenant_id` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '租户id',
  `del_flag` int NULL DEFAULT NULL COMMENT '删除状态(0未删除，1已删除，2临时删除)',
  `sort_no` int NULL DEFAULT NULL COMMENT '排序',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '分类' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of jimu_report_category
-- ----------------------------
INSERT INTO `jimu_report_category` VALUES ('1011126161407836160', '填报报表', '0', 1, 'report', 'admin', '2024-10-29 14:21:13', NULL, NULL, '1', 0, 3);
INSERT INTO `jimu_report_category` VALUES ('1023810558598676480', '测试大屏', '0', 1, 'screen', 'admin', '2024-12-03 14:24:29', NULL, '2024-12-03 14:24:29', '1', 0, NULL);
INSERT INTO `jimu_report_category` VALUES ('984272091947253760', '数据报表', '0', 1, 'report', 'admin', '2024-08-15 11:52:44', 'admin', '2024-12-19 15:11:12', '1', 0, 0);
INSERT INTO `jimu_report_category` VALUES ('984302961118724096', '图形报表', '0', 1, 'report', 'admin', '2024-08-16 13:55:24', 'admin', '2024-09-09 14:18:57', '1', 0, 2);
INSERT INTO `jimu_report_category` VALUES ('984302991393210368', '打印设计', '0', 1, 'report', 'admin', '2024-08-16 13:55:31', 'admin', '2024-12-19 15:11:16', '1', 0, 1);
INSERT INTO `jimu_report_category` VALUES ('988299668956545024', '仪表盘设计', '0', 1, 'drag', '15931993294', '2024-08-27 00:00:00', 'admin', '2024-10-31 15:59:47', '1', 0, 0);
INSERT INTO `jimu_report_category` VALUES ('988299695309357056', '门户设计', '0', 1, 'drag', '15931993294', '2024-08-27 00:00:00', '15931993294', '2024-08-27 00:00:00', NULL, 0, 0);
