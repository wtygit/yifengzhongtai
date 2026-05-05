-- 修复缺失的审核字段（在海典同步库中执行）
-- 如果字段已存在，会报错但可以忽略（不影响）

-- 添加审核状态字段
ALTER TABLE `mcp_order_create_request_log` 
ADD COLUMN `audit_status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '审核状态：0待审核 1已通过 2已驳回' AFTER `user_request_data`;

-- 添加审核时间字段
ALTER TABLE `mcp_order_create_request_log` 
ADD COLUMN `audit_time` datetime DEFAULT NULL COMMENT '审核时间' AFTER `audit_status`;

-- 添加审核备注字段
ALTER TABLE `mcp_order_create_request_log` 
ADD COLUMN `audit_remark` varchar(500) DEFAULT NULL COMMENT '审核备注' AFTER `audit_time`;

-- 添加索引
ALTER TABLE `mcp_order_create_request_log` 
ADD INDEX `idx_audit_status` (`audit_status`);

-- 验证：检查字段是否添加成功
DESC `mcp_order_create_request_log`;
