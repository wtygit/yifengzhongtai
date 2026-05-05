-- MCP 下单表 audit_status 扩展说明（无需改表结构，应用使用数值 8）
-- 在海典库 antis_yifengdata_hub 等与 haidian.datasource 一致的库中执行本文件仅用于更新列注释时可选：

-- ALTER TABLE mcp_order_create_request_log
--   MODIFY COLUMN audit_status tinyint NOT NULL DEFAULT 0
--   COMMENT '0待审核 1已通过 2已驳回 8原始接入留痕(仅追溯不参与审核/合并)';

-- 查询「原始接入」快照（含未通过校验的请求体）：
-- SELECT pending_id, create_time, user_request_data
-- FROM mcp_order_create_request_log
-- WHERE audit_status = 8 AND request_source = 'ingest'
-- ORDER BY create_time DESC;
