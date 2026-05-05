-- 积木报表查询SQL：MCP下单请求记录（解析JSON并展开items）
-- 在海典同步库中执行
-- 
-- 注意：根据MySQL版本选择对应的SQL文件
-- - MySQL 8.0+：使用本文件（JSON_TABLE，性能更好）
-- - MySQL 5.7：使用 jimureport_mcp_order_request_view_mysql57.sql

-- 方案1：MySQL 8.0+ 使用JSON_TABLE（推荐，性能更好）
-- 如果items为空数组或不存在，仍会返回一行（items字段为NULL）
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
    -- 药品信息（从items数组展开）
    item.drug_name,
    item.spec,
    item.qty,
    item.ware_id,
    item.bar_code,
    -- 原始JSON（完整保留）
    r.user_request_data
FROM mcp_order_create_request_log r
LEFT JOIN JSON_TABLE(
    r.user_request_data,
    '$.items[*]' COLUMNS (
        drug_name VARCHAR(200) PATH '$.drugName',
        spec VARCHAR(200) PATH '$.spec',
        qty INT PATH '$.qty',
        ware_id VARCHAR(100) PATH '$.wareId',
        bar_code VARCHAR(100) PATH '$.barCode'
    )
) AS item ON JSON_EXTRACT(r.user_request_data, '$.items') IS NOT NULL
ORDER BY r.create_time DESC, r.id DESC, item.drug_name;

-- 方案2：MySQL 5.7兼容版本（如果items为空，不返回行；需要UNION处理空items情况）
-- 注意：如果items为空数组，此查询不会返回该订单记录
/*
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
    JSON_UNQUOTE(JSON_EXTRACT(item.value, '$.drugName')) AS drug_name,
    JSON_UNQUOTE(JSON_EXTRACT(item.value, '$.spec')) AS spec,
    CAST(JSON_EXTRACT(item.value, '$.qty') AS UNSIGNED) AS qty,
    JSON_UNQUOTE(JSON_EXTRACT(item.value, '$.wareId')) AS ware_id,
    JSON_UNQUOTE(JSON_EXTRACT(item.value, '$.barCode')) AS bar_code,
    r.user_request_data
FROM mcp_order_create_request_log r,
JSON_TABLE(
    IFNULL(JSON_EXTRACT(r.user_request_data, '$.items'), '[]'),
    '$[*]' COLUMNS (value JSON PATH '$')
) AS item
WHERE JSON_EXTRACT(r.user_request_data, '$.items') IS NOT NULL
  AND JSON_LENGTH(IFNULL(JSON_EXTRACT(r.user_request_data, '$.items'), '[]')) > 0
UNION ALL
-- 处理items为空或不存在的情况
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
*/
