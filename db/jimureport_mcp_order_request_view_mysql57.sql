-- 积木报表查询SQL：MCP下单请求记录（解析JSON并展开items）
-- MySQL 5.7兼容版本（不使用JSON_TABLE）
-- 在海典同步库中执行
--
-- ⚠️ 重要提示：
-- 1. 如果你的MySQL版本是5.7，必须使用此SQL文件！
-- 2. 执行此SQL前，必须先执行 db/fix_missing_audit_fields.sql 添加审核字段！
--    如果报错 "Unknown column 'r.audit_status'"，说明还没有添加审核字段
-- 3. 如果执行时仍然报错，请使用更简单的版本：jimureport_mcp_order_request_view_simple.sql
--
-- 说明：此查询使用子查询和JSON函数展开items数组
-- 如果items为空数组，仍会返回一行（items相关字段为NULL）

SELECT 
    r.id,
    r.pending_id,
    r.order_id,
    r.request_source,
    r.audit_status,
    CASE r.audit_status
        WHEN 0 THEN '待审核'
        WHEN 1 THEN '已通过'
        WHEN 2 THEN '已驳回'
        ELSE '未知'
    END AS audit_status_text,
    r.audit_time,
    r.audit_remark,
    r.create_time,
    -- 患者信息（从JSON提取）
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.patientName')) AS patient_name,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.patientPhone')) AS patient_phone,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.patientIdCard')) AS patient_id_card,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.patientEducation')) AS patient_education,
    -- 药品信息（从items数组展开，使用子查询）
    JSON_UNQUOTE(JSON_EXTRACT(
        JSON_EXTRACT(r.user_request_data, CONCAT('$.items[', item_idx.idx, ']')),
        '$.drugName'
    )) AS drug_name,
    JSON_UNQUOTE(JSON_EXTRACT(
        JSON_EXTRACT(r.user_request_data, CONCAT('$.items[', item_idx.idx, ']')),
        '$.spec'
    )) AS spec,
    CAST(JSON_EXTRACT(
        JSON_EXTRACT(r.user_request_data, CONCAT('$.items[', item_idx.idx, ']')),
        '$.qty'
    ) AS UNSIGNED) AS qty,
    JSON_UNQUOTE(JSON_EXTRACT(
        JSON_EXTRACT(r.user_request_data, CONCAT('$.items[', item_idx.idx, ']')),
        '$.wareId'
    )) AS ware_id,
    JSON_UNQUOTE(JSON_EXTRACT(
        JSON_EXTRACT(r.user_request_data, CONCAT('$.items[', item_idx.idx, ']')),
        '$.barCode'
    )) AS bar_code,
    -- 原始JSON（完整保留）
    r.user_request_data
FROM mcp_order_create_request_log r
LEFT JOIN (
    -- 生成索引序列：0, 1, 2, 3, 4, 5, 6, 7, 8, 9（最多支持10个items）
    SELECT 0 AS idx UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
    UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9
) AS item_idx
ON item_idx.idx < JSON_LENGTH(IFNULL(JSON_EXTRACT(r.user_request_data, '$.items'), '[]'))
WHERE JSON_EXTRACT(r.user_request_data, '$.items') IS NOT NULL
  AND JSON_LENGTH(IFNULL(JSON_EXTRACT(r.user_request_data, '$.items'), '[]')) > 0

UNION ALL

-- 处理items为空或不存在的情况（返回一行，items字段为NULL）
SELECT 
    r.id,
    r.pending_id,
    r.order_id,
    r.request_source,
    r.audit_status,
    CASE r.audit_status
        WHEN 0 THEN '待审核'
        WHEN 1 THEN '已通过'
        WHEN 2 THEN '已驳回'
        ELSE '未知'
    END AS audit_status_text,
    r.audit_time,
    r.audit_remark,
    r.create_time,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.patientName')) AS patient_name,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.patientPhone')) AS patient_phone,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.patientIdCard')) AS patient_id_card,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.patientEducation')) AS patient_education,
    NULL AS drug_name,
    NULL AS spec,
    NULL AS qty,
    NULL AS ware_id,
    NULL AS bar_code,
    r.user_request_data
FROM mcp_order_create_request_log r
WHERE JSON_EXTRACT(r.user_request_data, '$.items') IS NULL
   OR JSON_LENGTH(IFNULL(JSON_EXTRACT(r.user_request_data, '$.items'), '[]')) = 0

ORDER BY create_time DESC, id DESC, drug_name;
