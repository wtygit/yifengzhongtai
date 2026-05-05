-- ----------------------------
-- 以下为 API 生成器表（追加到报表库后执行）
-- ----------------------------
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `api_param`;
DROP TABLE IF EXISTS `api_field`;
DROP TABLE IF EXISTS `api_table`;
DROP TABLE IF EXISTS `api_info`;

CREATE TABLE `api_info` (
  `id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '主键',
  `api_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'API名称',
  `api_path` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'API路径',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'API描述',
  `create_by` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '状态（1：启用，0：禁用）',
  `api_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT 'table' COMMENT 'API类型：table-表类型，sql-SQL类型',
  `sql_content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'SQL内容（SQL类型API使用）',
  `sql_database_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'SQL使用的数据源名称（SQL类型API使用）',
  `need_pagination` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否分页：1-是，0-否',
  `page_size` int NOT NULL DEFAULT 20 COMMENT '默认每页条数',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_api_path`(`api_path`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC COMMENT = 'API基本信息';

CREATE TABLE `api_table` (
  `id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '主键',
  `api_id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'API ID',
  `database_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '数据源/数据库名称',
  `table_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '表名',
  `alias` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '表别名',
  `join_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '关联类型',
  `join_condition` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '关联条件',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_api_id`(`api_id`) USING BTREE,
  CONSTRAINT `fk_api_table_api` FOREIGN KEY (`api_id`) REFERENCES `api_info` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC COMMENT = 'API关联表';

CREATE TABLE `api_field` (
  `id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '主键',
  `api_id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'API ID',
  `database_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '数据源/数据库名称',
  `table_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '表名',
  `field_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '字段名',
  `alias` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '字段别名',
  `data_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '数据类型',
  `is_primary` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否主键',
  `is_required` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否必填',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '字段描述',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_api_id`(`api_id`) USING BTREE,
  CONSTRAINT `fk_api_field_api` FOREIGN KEY (`api_id`) REFERENCES `api_info` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC COMMENT = 'API返回字段';

CREATE TABLE `api_param` (
  `id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '主键',
  `api_id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'API ID',
  `param_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '参数名',
  `param_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'query' COMMENT '参数类型',
  `data_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'String' COMMENT '数据类型',
  `default_value` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '默认值',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '参数描述',
  `validate_rule` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '校验规则',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_api_id`(`api_id`) USING BTREE,
  CONSTRAINT `fk_api_param_api` FOREIGN KEY (`api_id`) REFERENCES `api_info` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC COMMENT = 'API请求参数';

SET FOREIGN_KEY_CHECKS = 1;
