# MCP订单审核流程说明

## 一、流程变更

### 原流程
用户请求 → 直接调用中台接口 → 返回结果

### 新流程
用户请求 → 保存到表A（待审核） → 管理人员在报表工作台查看 → 审核通过/驳回 → 通过后调用中台接口

## 二、数据库变更

### 表A新增字段

执行以下SQL脚本（如果表已存在）：

```sql
-- 在海典同步库执行
ALTER TABLE `mcp_order_create_request_log` 
ADD COLUMN `audit_status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '审核状态：0待审核 1已通过 2已驳回' AFTER `user_request_data`,
ADD COLUMN `audit_time` datetime DEFAULT NULL COMMENT '审核时间' AFTER `audit_status`,
ADD COLUMN `audit_remark` varchar(500) DEFAULT NULL COMMENT '审核备注' AFTER `audit_time`,
ADD KEY `idx_audit_status` (`audit_status`);
```

或直接执行：`db/mcp_order_create_log_add_audit.sql`

### 审核状态说明

- **0**：待审核（默认）
- **1**：已通过（已调用中台接口）
- **2**：已驳回

## 三、接口变更

### 1. 下单接口（不变）

**接口**：`POST /mcp/core_order_create`

**变更**：现在只保存到表A，状态为待审核，不调用中台接口

**返回示例**：
```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "pendingId": "PD-2026-03-20-abc12345",
    "message": "订单已提交，等待审核"
  }
}
```

### 2. 审核通过接口（新增）

**接口**：`POST /mcp/core_order_approve`

**请求体**：
```json
{
  "pendingId": "PD-2026-03-20-abc12345",
  "auditRemark": "审核通过"  // 可选
}
```

**功能**：
1. 从表A读取 `user_request_data`
2. 解析JSON，调用中台接口
3. 写入表B（订单日志）
4. 更新表A状态为已通过（audit_status=1）

**返回示例**：
```json
{
  "code": 0,
  "msg": "审核通过，已提交中台",
  "data": {
    "pendingId": "PD-2026-03-20-abc12345",
    "orderId": "PD-2026-03-20-abc12345",
    "order": {...},
    "middlePlatformResponse": {...}
  }
}
```

### 3. 审核驳回接口（新增）

**接口**：`POST /mcp/core_order_reject`

**请求体**：
```json
{
  "pendingId": "PD-2026-03-20-abc12345",
  "auditRemark": "患者信息不完整"  // 必填
}
```

**功能**：更新表A状态为已驳回（audit_status=2）

**返回示例**：
```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "pendingId": "PD-2026-03-20-abc12345",
    "updated": 1
  }
}
```

## 四、积木报表配置

### 1. 数据源配置

确保积木报表已配置**海典同步库**数据源（与 `haidian.datasource` 一致）

### 2. 创建报表

1. 进入积木报表工作台
2. 选择「数据报表」文件夹
3. 点击「新建报表」
4. 选择「SQL数据集」

### 3. SQL查询

**重要**：根据你的MySQL版本选择对应的SQL文件：

- **MySQL 8.0+**：使用 `db/jimureport_mcp_order_request_view.sql`（使用JSON_TABLE，性能更好）
- **MySQL 5.7**：使用 `db/jimureport_mcp_order_request_view_mysql57.sql`（兼容版本）

如果执行SQL时报错 `JSON_TABLE` 语法错误，说明你的MySQL版本是5.7，请使用MySQL 5.7版本的SQL文件。

**MySQL 8.0+ 版本SQL**（`db/jimureport_mcp_order_request_view.sql`）：

```sql
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
    item.drug_name,
    item.spec,
    item.qty,
    item.ware_id,
    item.bar_code,
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
```

**MySQL 5.7 版本SQL**（`db/jimureport_mcp_order_request_view_mysql57.sql`）：
```sql
-- 使用子查询和JSON函数展开items数组，兼容MySQL 5.7
-- 详见 db/jimureport_mcp_order_request_view_mysql57.sql 文件
```

### 4. 报表字段说明

| 字段名 | 说明 | 来源 |
|--------|------|------|
| id | 主键 | 表A |
| pending_id | 待下单单号 | 表A |
| order_id | 订单号 | 表A |
| request_source | 请求来源 | 表A |
| audit_status | 审核状态（数字） | 表A |
| audit_status_text | 审核状态（文本） | 计算字段 |
| audit_time | 审核时间 | 表A |
| audit_remark | 审核备注 | 表A |
| create_time | 创建时间 | 表A |
| patient_name | 患者姓名 | JSON解析 |
| patient_phone | 患者手机号 | JSON解析 |
| patient_id_card | 患者身份证 | JSON解析 |
| patient_education | 患教 | JSON解析 |
| drug_name | 药品名称 | JSON展开（items数组） |
| spec | 规格 | JSON展开 |
| qty | 数量 | JSON展开 |
| ware_id | 商品ID | JSON展开 |
| bar_code | 条码 | JSON展开 |
| user_request_data | 原始JSON | 表A（完整保留） |

### 5. 报表设计建议

- **主表**：显示订单基本信息（pending_id、患者信息、审核状态等）
- **明细表**：显示药品列表（drug_name、spec、qty等），一个订单可能有多行
- **筛选条件**：可按 `audit_status` 筛选待审核订单（audit_status=0）

### 6. MySQL版本说明

- **MySQL 8.0+**：使用 `JSON_TABLE`（推荐，性能更好）
- **MySQL 5.7**：可使用方案2（见SQL文件注释），但需要处理空items情况

## 五、使用流程

### 方式一：使用前端审核页面（推荐）

1. **访问审核页面**：`http://your-server:port/mcp/order-audit`
2. **查看订单列表**：页面自动加载待审核订单，可按状态筛选
3. **审核操作**：
   - 点击「审核」按钮
   - 填写审核备注（可选）
   - 点击「通过」或「驳回」按钮
4. **查看结果**：页面自动刷新，显示最新审核状态

### 方式二：使用积木报表 + API接口

1. **用户下单**：调用 `core_order_create`，数据保存到表A，状态为待审核
2. **查看报表**：管理人员在积木报表工作台查看待审核订单
3. **审核操作**（通过API）：
   - **通过**：调用 `POST /mcp/core_order_approve`，系统自动调用中台接口
   - **驳回**：调用 `POST /mcp/core_order_reject`，填写驳回原因
4. **查看结果**：在报表中查看审核状态和结果

### 方式三：直接调用API

1. **获取订单列表**：`GET /mcp/order-audit-list?status=0`（status: 0待审核 1已通过 2已驳回）
2. **审核通过**：`POST /mcp/core_order_approve`，请求体：`{"pendingId":"xxx", "auditRemark":"审核通过"}`
3. **审核驳回**：`POST /mcp/core_order_reject`，请求体：`{"pendingId":"xxx", "auditRemark":"驳回原因"}`

## 六、注意事项

1. **数据源配置**：积木报表必须配置**海典同步库**数据源，不是jimureport主库
2. **MySQL版本**：如果使用MySQL 5.7，必须使用 `jimureport_mcp_order_request_view_mysql57.sql`，否则会报 `JSON_TABLE` 语法错误
3. **审核通过后**：如果中台接口调用失败，表A状态仍会更新为已通过，但表B会记录失败信息
4. **重复审核**：已审核的订单不能重复审核（会返回错误）
5. **驳回原因**：驳回时必须填写驳回原因（auditRemark必填）
6. **items展开**：报表SQL使用 `LEFT JOIN JSON_TABLE`（MySQL 8.0+）或子查询（MySQL 5.7），即使items为空也会返回一行（items相关字段为NULL）
7. **前端页面**：访问 `/mcp/order-audit` 可使用可视化审核界面，无需手动调用API
