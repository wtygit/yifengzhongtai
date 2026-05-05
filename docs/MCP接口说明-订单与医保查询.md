# MCP 接口说明文档（订单查询 + 医保查询 + 药品查询 + 回访策略 + 下单占位）

**版本**：1.0  
**数据源**：海典同步数据源（antis_yifengdata_hub）  
**适用场景**：客户对接 MCP 后，在大模型对话中通过自然语言调用，例如：「用订单查询接口查一下订单号 xxx 的详情」「用医保查询接口查一下身份证 xxx 的医保信息」。

---

## 一、通用约定

### 1.1 MCP 发现地址 / 服务根地址

- **发现地址（MCP 服务端点）**：采用 **JSON-RPC 2.0** 规范时，客户只需配置一个端点：  
  **`{服务根地址}/mcp/rpc`**  
  例如：`http://您的服务器IP或域名:39001/mcp/rpc`（端口以实际部署为准）。
- **通信流程**（符合 MCP 规范）：
  1. **Initialize（握手）**：客户端发送 `method: "initialize"`，服务端返回协议版本与能力（capabilities）。
  2. **initialized（可选）**：客户端发送 `method: "initialized"` 表示就绪。
  3. **tools/list（发现工具）**：客户端发送 `method: "tools/list"`，服务端返回工具列表（name、description、inputSchema）。
  4. **tools/call（调用工具）**：客户端发送 `method: "tools/call"`，`params.name` 为工具名，`params.arguments` 为参数对象；服务端返回 `result.content[].text`（JSON 字符串，即业务返回的 `{code, msg, data}`）。
- **请求/响应格式**：所有请求均为 **POST**，Content-Type: application/json，请求体为 JSON-RPC 2.0 格式（`jsonrpc`、`id`、`method`、`params`）；响应体为 JSON-RPC 2.0 的 `result` 或 `error`。
- **兼容**：保留按工具名单独 POST 的接口（如 `POST /mcp/core_order_query`），未使用 JSON-RPC 的客户仍可沿用原方式。

**JSON-RPC 2.0 请求示例（发现地址：POST `{base}/mcp/rpc`）**

- 握手：`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"client","version":"1.0"}}}`
- 获取工具列表：`{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}`
- 调用工具：`{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"core_order_query","arguments":{"orderId":"1551015496417349"}}}`

### 1.2 其他约定

- **协议**：MCP（Model Context Protocol），工具调用兼容 OpenAI Function Calling 语义。
- **数据源**：所有查询均针对海典同步数据源中的指定表，只读，不修改数据。
- **返回包装**（推荐）：统一使用 `code`、`msg`、`data` 包装；`code=0` 表示成功，非 0 表示失败。

---

## 二、接口一：订单查询（core_order_query）

### 2.1 功能说明

根据订单号查询海典核心订单表 `corecmsorder`，返回订单状态、支付、物流、患者及处方等信息。  
**场景**：下单、查单、复购。

### 2.2 MCP 工具定义（客户配置用）

```json
{
  "name": "core_order_query",
  "description": "根据订单号查询海典核心订单表 corecmsorder，返回订单状态、支付、物流和患者信息等详情。",
  "arguments": {
    "type": "object",
    "properties": {
      "orderId": {
        "type": "string",
        "description": "订单号，对应 corecmsorder.orderId。"
      }
    },
    "required": ["orderId"]
  }
}
```

### 2.3 请求参数

| 参数名   | 类型   | 必填 | 说明                    |
|----------|--------|------|-------------------------|
| orderId  | string | 是   | 订单号（corecmsorder.orderId） |

### 2.4 实现参考：查询 SQL

```sql
SELECT
  orderId, status, payStatus, shipStatus, orderType,
  goodsAmount, payedAmount, orderAmount, costFreight,
  orderDiscountAmount, goodsDiscountAmount, couponDiscountAmount,
  payType, paymentCode, paymentTime,
  sfwaybillNo, logisticsId, logisticsName,
  shipName, shipMobile, shipAddress,
  memcardno, cardholder, idcard, mobile,
  busno, prescriptionDoctor, patient, patientIllness, sex, age, office,
  createTime, updateTime
FROM corecmsorder
WHERE orderId = :orderId;
```

（`:orderId` 请使用参数化绑定，勿拼接字符串。）

### 2.5 返回示例（data 单条对象）

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "orderId": "1551015496417349",
    "status": 1,
    "payStatus": 2,
    "shipStatus": 1,
    "orderType": 1,
    "goodsAmount": 23,
    "payedAmount": 23,
    "orderAmount": 23,
    "costFreight": 0,
    "payType": "",
    "paymentCode": "balancepay",
    "paymentTime": "2024-05-26 02:27:53",
    "sfwaybillNo": "",
    "logisticsName": "默认配送方式",
    "shipName": "王哥",
    "shipMobile": "15498023455",
    "shipAddress": "大初公寓42号",
    "prescriptionDoctor": "李医生",
    "patient": "王哥",
    "patientIllness": "感冒",
    "sex": "男",
    "age": "35",
    "office": "胡",
    "createTime": "2024-05-26 02:27:50",
    "updateTime": "2024-05-26 02:27:53"
  }
}
```

订单不存在时建议返回：`{"code": 404, "msg": "订单不存在", "data": null}`。

### 2.6 用户对话示例

- 用户：「用订单查询接口查一下订单号 1551015496417349 的详情。」
- 模型调用：`core_order_query`，`arguments.orderId = "1551015496417349"`。

---

## 三、接口二：医保查询（core_insurance_query）

### 3.1 功能说明

根据患者标识（用户ID、手机号或身份证）查询海典同步数据源中的 `corecmsusership` 表，返回医保/患者档案信息（可多条）。  
**场景**：下单前查患者医保信息。

### 3.2 MCP 工具定义（客户配置用）

```json
{
  "name": "core_insurance_query",
  "description": "根据患者标识（userId、手机号或身份证）查询海典同步数据源 corecmsusership 表中的医保/患者档案信息。",
  "arguments": {
    "type": "object",
    "properties": {
      "userId": {
        "type": "integer",
        "description": "海典用户ID，对应 corecmsusership.userId。"
      },
      "mobile": {
        "type": "string",
        "description": "患者手机号，对应 corecmsusership.mobile。"
      },
      "idCard": {
        "type": "string",
        "description": "身份证号，对应 corecmsusership.idCard 或 bmrSfz。"
      }
    },
    "required": [],
    "description": "userId、mobile、idCard 至少提供一个；查询优先级建议：userId > idCard > mobile。"
  }
}
```

### 3.3 请求参数

| 参数名  | 类型    | 必填 | 说明                          |
|---------|---------|------|-------------------------------|
| userId  | integer | 否   | 海典用户ID（corecmsusership.userId） |
| mobile  | string  | 否   | 手机号（corecmsusership.mobile）     |
| idCard  | string  | 否   | 身份证（idCard 或 bmrSfz）     |

**约定**：三者至少传一个；实现时校验并建议优先级 userId > idCard > mobile。

### 3.4 实现参考：查询 SQL

- 仅传 `userId` 时：

```sql
SELECT id, userId, name, mobile, address, areaId, idCard, bmrSfz,
       customerId, bmrCustomerId, hzgx, sex, birthday, isDefault,
       yisheng, jibing, keshi, longitude, latitude, street,
       dispenser, dispenser_Phone, remark, createTime, updateTime
FROM corecmsusership
WHERE userId = :userId;
```

- 仅传 `idCard` 时：`WHERE idCard = :idCard OR bmrSfz = :idCard`
- 仅传 `mobile` 时：`WHERE mobile = :mobile`

（均使用参数化查询。）

### 3.5 返回示例（data 为数组）

```json
{
  "code": 0,
  "msg": "ok",
  "data": [
    {
      "id": 123,
      "userId": 10001,
      "name": "张三",
      "mobile": "13800000000",
      "sex": "男",
      "birthday": "1985-06-12",
      "idCard": "320************",
      "customerId": "YB123456",
      "relation": "本人",
      "isDefault": true,
      "address": "北京市朝阳区XX路XX号",
      "doctor": "李医生",
      "department": "心内科",
      "disease": "高血压",
      "pharmacist": "王药师",
      "pharmacistPhone": "010-88888888",
      "remark": "长期用药患者",
      "createTime": "2024-05-26 10:30:00",
      "updateTime": "2024-05-26 11:00:00"
    }
  ]
}
```

字段与表映射：`relation`←hzgx，`doctor`←yisheng，`department`←keshi，`disease`←jibing，`pharmacist`←dispenser，`pharmacistPhone`←dispenser_Phone。无数据时返回 `"data": []`。

### 3.6 用户对话示例

- 用户：「用医保查询接口查一下身份证 320************ 的医保信息。」
- 模型调用：`core_insurance_query`，`arguments.idCard = "320************"`。

---

## 四、提供给客户的方式建议

1. **直接发送本文件**  
   将 `MCP接口说明-订单与医保查询.md` 发给客户，客户可用 Markdown 阅读器或导出为 PDF 使用。

2. **导出为 PDF**  
   用 VS Code / Typora / 在线 Markdown 转 PDF 工具导出，便于盖章或归档。

3. **复制工具定义到客户 MCP 配置**  
   客户在配置 MCP 服务器时，将第二节、第三节中的 **「MCP 工具定义」**  JSON 复制到其 MCP 工具列表；实现端按文档中的 SQL 与返回结构对接海典同步数据源即可。

4. **按需脱敏**  
   若文档中有示例订单号、身份证号等，交付前可替换为脱敏示例或占位符。

---

---

## 四、接口三：药品查询（core_drug_query）

### 4.1 功能说明

根据药品名称、拼音码或条码，从海典同步数据源中的药品基础表 **`t_ware_base`** 中查询药品信息列表。  
**场景**：医生开方选药、药师调配、患者复购推荐等。

**数据源**：海典同步数据源（antis_yifengdata_hub）。

### 4.2 MCP 工具定义（客户配置用）

```json
{
  "name": "core_drug_query",
  "description": "根据药品名称、拼音或条码查询海典同步数据源 t_ware_base 表中的药品基础信息列表。",
  "arguments": {
    "type": "object",
    "properties": {
      "keyword": {
        "type": "string",
        "description": "关键字，支持药品名称(WARENAME)或拼音码(WAREABC)模糊匹配。"
      },
      "barCode": {
        "type": "string",
        "description": "药品条码，对应 t_ware_base.BARCODE，支持精准或包含匹配。"
      }
    },
    "required": [],
    "description": "keyword、barCode 至少提供一个；当同时提供时优先使用 barCode 精准查询。"
  }
}
```

### 4.3 请求参数

| 参数名    | 类型   | 必填 | 说明                                                                 |
|-----------|--------|------|----------------------------------------------------------------------|
| keyword   | string | 否   | 关键字（药品名称、拼音码等），对应 WARENAME / WAREABC 模糊查询。    |
| barCode   | string | 否   | 药品条码，对应 BARCODE，支持精确或包含匹配（表中 BARCODE 可能存多条用。分隔）。 |

**约定**：`keyword`、`barCode` 至少传一个；当同时提供时，优先使用 `barCode` 查询。

### 4.4 表结构说明（t_ware_base）

| 字段名           | 说明       |
|------------------|------------|
| WAREID           | 药品ID     |
| WARENAME         | 药品名称   |
| WAREGENERALNAME  | 通用名     |
| WARESPEC         | 规格       |
| FACTORYID        | 厂家ID     |
| WAREUNIT         | 单位       |
| FILENO           | 批准文号   |
| WAREABC          | 拼音码     |
| WARECODE         | 药品编码   |
| BARCODE          | 条码       |
| LASTTIME         | 最后修改时间 |

### 4.5 实现参考：查询 SQL

**按条码查询（精确或包含）：**

```sql
SELECT
  WAREID, WARENAME, WAREGENERALNAME, WARESPEC,
  FACTORYID, WAREUNIT, FILENO, WAREABC, WARECODE, BARCODE, LASTTIME
FROM t_ware_base
WHERE BARCODE = :barCode OR BARCODE LIKE CONCAT('%', :barCode, '%')
ORDER BY LASTTIME DESC
LIMIT 50;
```

**按关键字模糊查询（名称 / 拼音）：**

```sql
SELECT
  WAREID, WARENAME, WAREGENERALNAME, WARESPEC,
  FACTORYID, WAREUNIT, FILENO, WAREABC, WARECODE, BARCODE, LASTTIME
FROM t_ware_base
WHERE WARENAME LIKE :likeKeyword OR WAREABC LIKE :likeKeyword
ORDER BY LASTTIME DESC
LIMIT 50;
```

其中 `:likeKeyword` 为 `"%关键字%"`，务必使用参数化绑定。

### 4.6 返回示例（data 为数组）

```json
{
  "code": 0,
  "msg": "ok",
  "data": [
    {
      "WAREID": "102478",
      "WARENAME": "盐酸二甲双胍缓释片",
      "WAREGENERALNAME": "盐酸二甲双胍缓释片",
      "WARESPEC": "500mg*30片",
      "FACTORYID": "3158",
      "WAREUNIT": "盒",
      "FILENO": "国药准字H20080252",
      "WAREABC": "YSEJSGHSP",
      "WARECODE": "1113894",
      "BARCODE": "6945898600069",
      "LASTTIME": "2024-05-26 00:37:19"
    }
  ]
}
```

无数据时返回：`{"code": 0, "msg": "ok", "data": []}`。

### 4.7 用户对话示例

- 用户：「查一下有没有阿莫西林胶囊这类药，给我列几个常见规格。」  
- 模型调用：`core_drug_query`，`arguments.keyword = "阿莫西林胶囊"`。

- 用户：「根据条码 6945898600069 帮我查一下是哪种药。」  
- 模型调用：`core_drug_query`，`arguments.barCode = "6945898600069"`。

---

## 五、接口四：回访策略查询（core_visit_strategy_query）

### 5.1 功能说明

回访策略查询接口，用于给大模型提供统一的“回访策略”工具入口。  
**当前阶段尚未接入真实回访策略数据**，接口固定返回 `code=0` 且 `msg="暂无数据支持"`，`data=null`。  
后续接入真实策略数据时，可在保持 MCP 工具名称不变的前提下平滑升级实现。

### 5.2 MCP 工具定义（客户配置用）

```json
{
  "name": "core_visit_strategy_query",
  "description": "回访策略查询接口。当前暂未接入实际回访策略数据，固定返回“暂无数据支持”。后续有数据后可平滑升级。",
  "arguments": {
    "type": "object",
    "properties": {
      "businessId": {
        "type": "string",
        "description": "业务标识，例如订单号、患者ID等，可用于后续接入真实回访策略时做精确匹配。当前可为空。"
      }
    },
    "required": [],
    "description": "目前 businessId 为可选，仅作为预留扩展字段。"
  }
}
```

### 5.3 请求参数

| 参数名     | 类型   | 必填 | 说明                                                                |
|------------|--------|------|---------------------------------------------------------------------|
| businessId | string | 否   | 业务标识（如订单号、患者ID 等），当前阶段可不传，仅作扩展预留。   |

### 5.4 实现说明

当前实现逻辑非常简单：不访问任何外部库或表，直接固定返回：

```json
{
  "code": 0,
  "msg": "暂无数据支持",
  "data": null
}
```

未来接入真实回访策略数据时，可在后端根据 `businessId` 去查询具体策略并填充 `data` 字段，而无需变更 MCP 工具名称和参数结构。

### 5.5 返回示例

```json
{
  "code": 0,
  "msg": "暂无数据支持",
  "data": null
}
```

### 5.6 用户对话示例

- 用户：「帮我查一下订单 1551015496417349 有没有回访计划。」  
- 模型调用：`core_visit_strategy_query`，`arguments.businessId = "1551015496417349"`。  
- 接口返回：`{"code":0,"msg":"暂无数据支持","data":null}`，模型可据此回复用户「当前暂无回访策略相关数据」。

---

## 六、接口五：下单占位（core_order_create）

### 6.1 功能说明

下单操作类接口，用于大模型侧发起“下单”工具调用流程。  
**推荐用法（结构化入参）**：由大模型先抽取并填充患者信息与 `items` 药品列表，服务端不再做自然语言提取，仅负责匹配药品并调用中台下单接口。

### 6.2 MCP 工具定义（客户配置用）

```json
{
  "name": "core_order_create",
  "description": "下单接口（推荐结构化入参）：传患者信息 + 药品 items 列表，服务端不做自然语言抽取；兼容 requestJson（不推荐）。",
  "arguments": {
    "type": "object",
    "properties": {
      "patientName": { "type": "string", "description": "患者姓名（可选）" },
      "patientPhone": { "type": "string", "description": "患者手机号（可选）" },
      "patientIdCard": { "type": "string", "description": "患者身份证号（可选）" },
      "patientEducation": { "type": "string", "description": "患教（可选，原样入库）" },
      "items": { "type": "array", "description": "药品列表（必填）。每项至少包含 drugName、qty；可选 spec/barCode/wareId。" },
      "requestJson": { "type": "string", "description": "兼容参数：自然语言/字符串描述（不推荐，可能解析失败）。" }
    },
    "required": ["items"]
  }
}
```

### 6.3 请求参数

| 参数名      | 类型   | 必填 | 说明                                                                 |
|-------------|--------|------|----------------------------------------------------------------------|
| items | array | 是 | 药品列表：每项至少包含 `drugName`、`qty`；可选 `spec`、`barCode`、`wareId`。 |
| patientName/patientPhone/patientIdCard | string | 否 | 患者信息，可由大模型抽取后填充。 |
| requestJson | string | 否 | 兼容旧方式：自然语言/字符串描述（不推荐）。 |

> 说明：将来接入真实下单接口时，可以将该字段拆分为结构化参数（患者信息、商品列表、支付方式等），但 MCP 工具名称保持不变。

### 6.4 实现说明

当前实现会调用中台接口 `/api/PendingReceiver/Receive` 完成落库/创建，并在返回中提供：

- `data.pendingId`：本服务生成的待下单单号（与发往中台 `payload.pendingId` 一致；**成功或业务失败时多在 `data` 中返回**，便于与库表对账）
- `data.orderId`：订单标识（优先取中台返回的 `orderId/pendingId`，取不到则回退为本次生成的 `pendingId`）
- `data.order`：中台返回的写入后订单对象（`resp.data`）
- `data.middlePlatformResponse`：中台原始响应（便于排查）

**海典同步库留痕（双表，部署前需在该库执行 `db/mcp_order_create_log.sql`，与 `haidian.datasource` 一致）**：

| 表 | 说明 |
|----|------|
| `mcp_order_create_request_log`（表A） | 用户传入的原始下单数据（结构化为整段 JSON；自然语言为 `requestJson`）。在通过基本校验后**尽早写入**，不因后续药品匹配失败而丢失。 |
| `mcp_order_create_order_log`（表B） | 我方生成的待发中台数据、中台响应、调用成败；若在中台调用前失败（未配置中台、解析失败、患者未匹配、药品未匹配等），也会插入一条 `call_success = 0` 的记录，`error_message` 与 `generated_order_data` 中的 `phase` 字段标明阶段。 |

若接口已返回 200 但库中仍无记录：请确认 **海典同步库** 已建表、`haidian.datasource` 连接正常，并查看应用日志中是否有 `写入 mcp_order_create_request_log 失败` / `写入 mcp_order_create_order_log 失败` 的 **ERROR**。

### 6.5 返回示例

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "pendingId": "PD-2026-03-17-58179780",
    "orderId": "PD-2026-03-17-58179780",
    "order": { "pendingId": "PD-2026-03-17-58179780", "patientName": "张三", "storeId": "MD001" },
    "middlePlatformResponse": { "status": true, "msg": "ok", "data": { "pendingId": "PD-2026-03-17-58179780" } }
  }
}
```

### 6.6 用户对话示例

- 用户：「帮我下单：患者张三，电话 138xxxx，需要阿莫西林 2 盒，通窍鼻炎颗粒 8 盒。」  
- 模型先抽取为结构化参数，调用：  
  `core_order_create`，`arguments.items = [{"drugName":"阿莫西林胶囊","qty":2,"spec":"0.5g*11片*2板"},{"drugName":"通窍鼻炎颗粒","qty":8}]`。  
- 接口返回 `data.orderId` 与 `data.order` 后，模型可据此回复用户下单结果。  

---

**文档结束**
