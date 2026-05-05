-- 为已存在的表A增加审核状态字段（如果表已存在，执行此脚本）
-- 在海典同步库中执行

ALTER TABLE `mcp_order_create_request_log` 
ADD COLUMN `audit_status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '审核状态：0待审核 1已通过 2已驳回' AFTER `user_request_data`,
ADD COLUMN `audit_time` datetime DEFAULT NULL COMMENT '审核时间' AFTER `audit_status`,
ADD COLUMN `audit_remark` varchar(500) DEFAULT NULL COMMENT '审核备注' AFTER `audit_time`,
ADD KEY `idx_audit_status` (`audit_status`);
