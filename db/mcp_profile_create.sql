-- ============================================================
-- MCP 建档表：用于「帮我建档」对话场景，存储用户姓名、身份证、手机号
-- 执行前请先 use 目标库，例如：USE antis_yifengdata_hub;
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for mcp_user_profile
-- ----------------------------
DROP TABLE IF EXISTS `mcp_user_profile`;
CREATE TABLE `mcp_user_profile` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) NOT NULL COMMENT '用户姓名',
  `id_card` varchar(18) NOT NULL COMMENT '18位身份证号',
  `mobile` varchar(11) NOT NULL COMMENT '11位手机号',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_id_card` (`id_card`),
  KEY `idx_mobile` (`mobile`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='MCP建档用户信息表-帮我建档';

SET FOREIGN_KEY_CHECKS = 1;
