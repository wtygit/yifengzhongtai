-- 检查并修复审核字段（在海典同步库中执行）
-- 如果字段已存在，会报错但可以忽略

-- 步骤1：检查字段是否存在
SELECT 
    COLUMN_NAME, 
    DATA_TYPE, 
    COLUMN_DEFAULT,
    COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS 
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'mcp_order_create_request_log'
  AND COLUMN_NAME IN ('audit_status', 'audit_time', 'audit_remark');

-- 步骤2：如果上面的查询返回0行或字段不存在，执行下面的ALTER语句

-- 添加审核状态字段
ALTER TABLE `mcp_order_create_request_log` 
ADD COLUMN IF NOT EXISTS `audit_status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '审核状态：0待审核 1已通过 2已驳回' AFTER `user_request_data`;

-- 添加审核时间字段
ALTER TABLE `mcp_order_create_request_log` 
ADD COLUMN IF NOT EXISTS `audit_time` datetime DEFAULT NULL COMMENT '审核时间' AFTER `audit_status`;

-- 添加审核备注字段
ALTER TABLE `mcp_order_create_request_log` 
ADD COLUMN IF NOT EXISTS `audit_remark` varchar(500) DEFAULT NULL COMMENT '审核备注' AFTER `audit_time`;

-- 添加索引（如果不存在）
ALTER TABLE `mcp_order_create_request_log` 
ADD INDEX IF NOT EXISTS `idx_audit_status` (`audit_status`);

-- 步骤3：验证字段是否添加成功
SELECT 
    COLUMN_NAME, 
    DATA_TYPE, 
    COLUMN_DEFAULT,
    COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS 
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'mcp_order_create_request_log'
  AND COLUMN_NAME IN ('audit_status', 'audit_time', 'audit_remark');
