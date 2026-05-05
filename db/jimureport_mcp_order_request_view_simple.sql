-- 积木报表查询SQL：MCP下单请求记录（解析JSON并展开items）
-- MySQL 5.7 简化版本（最兼容，不使用复杂子查询）
-- 在海典同步库中执行
--
-- 注意：此版本使用最简单的方式，每个订单的每个药品显示一行
-- 如果items为空，只显示订单基本信息（药品字段为NULL）

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
    -- 药品信息（从items数组的第0个元素提取，如果需要展开多个，需要创建多个查询或使用存储过程）
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.items[0].drugName')) AS drug_name_1,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.items[0].spec')) AS spec_1,
    CAST(JSON_EXTRACT(r.user_request_data, '$.items[0].qty') AS UNSIGNED) AS qty_1,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.items[0].wareId')) AS ware_id_1,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.items[0].barCode')) AS bar_code_1,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.items[1].drugName')) AS drug_name_2,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.items[1].spec')) AS spec_2,
    CAST(JSON_EXTRACT(r.user_request_data, '$.items[1].qty') AS UNSIGNED) AS qty_2,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.items[2].drugName')) AS drug_name_3,
    JSON_UNQUOTE(JSON_EXTRACT(r.user_request_data, '$.items[2].spec')) AS spec_3,
    CAST(JSON_EXTRACT(r.user_request_data, '$.items[2].qty') AS UNSIGNED) AS qty_3,
    -- 原始JSON（完整保留，可在报表中使用表达式进一步解析）
    r.user_request_data,
    -- items数组长度（用于判断有多少个药品）
    JSON_LENGTH(IFNULL(JSON_EXTRACT(r.user_request_data, '$.items'), '[]')) AS items_count
FROM mcp_order_create_request_log r
ORDER BY r.create_time DESC, r.id DESC;

-- 说明：
-- 1. 此SQL将每个订单显示为一行，最多显示3个药品（drug_name_1, drug_name_2, drug_name_3）
-- 2. 如果订单有超过3个药品，可以在报表设计时使用 user_request_data 字段，通过表达式进一步解析
-- 3. 如果items为空，所有药品字段为NULL，但订单基本信息仍会显示
-- 4. 此SQL完全兼容MySQL 5.7，不依赖JSON_TABLE
