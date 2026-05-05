-- 为 api_table / api_field 添加 database_name 字段（多数据源支持）
ALTER TABLE api_table ADD COLUMN database_name VARCHAR(100) DEFAULT NULL COMMENT '数据库名称';
ALTER TABLE api_field ADD COLUMN database_name VARCHAR(100) DEFAULT NULL COMMENT '数据库名称';
