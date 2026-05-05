-- 扩展api_info表，添加SQL类型API支持
ALTER TABLE `api_info` 
ADD COLUMN `api_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT 'table' COMMENT 'API类型：table-表类型，sql-SQL类型' AFTER `status`,
ADD COLUMN `sql_content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'SQL内容（SQL类型API使用）' AFTER `api_type`,
ADD COLUMN `sql_database_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'SQL使用的数据源名称（SQL类型API使用）' AFTER `sql_content`;
 