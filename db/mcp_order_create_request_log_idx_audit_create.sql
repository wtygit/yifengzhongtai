-- 可选：加速「按 audit_status 过滤 + create_time 倒序 LIMIT」类查询（群分词统计、审核列表等）
-- 在海典同步库执行；若已存在相近联合索引可跳过

ALTER TABLE `mcp_order_create_request_log`
  ADD KEY `idx_audit_status_create_time` (`audit_status`, `create_time`);
