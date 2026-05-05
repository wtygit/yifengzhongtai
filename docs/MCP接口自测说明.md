# 海典 MCP 接口自测说明

所有接口均为 **POST**，请求头：`Content-Type: application/json`，Body 为 JSON。

---

## 方式一：用 curl 自测（命令行）

先启动项目（如 `java -jar jimureport-example.jar` 或 IDE 运行），端口默认 **8080**（prod 为 8085）。  
把下面命令里的 `http://localhost:8080` 改成你的实际地址后执行。

### 1. 订单查询 core_order_query

```bash
curl -X POST http://localhost:8080/mcp/core_order_query -H "Content-Type: application/json" -d "{\"orderId\":\"001\"}"
```

### 2. 医保查询 core_insurance_query

```bash
# 按身份证
curl -X POST http://localhost:8080/mcp/core_insurance_query -H "Content-Type: application/json" -d "{\"idCard\":\"320************\"}"

# 按手机号
curl -X POST http://localhost:8080/mcp/core_insurance_query -H "Content-Type: application/json" -d "{\"mobile\":\"13800000000\"}"
```

### 3. 药品查询 core_drug_query

```bash
# 按关键字
curl -X POST http://localhost:8080/mcp/core_drug_query -H "Content-Type: application/json" -d "{\"keyword\":\"阿莫西林\"}"

# 按条码
curl -X POST http://localhost:8080/mcp/core_drug_query -H "Content-Type: application/json" -d "{\"barCode\":\"6945898600069\"}"
```

### 4. 回访策略 core_visit_strategy_query

```bash
curl -X POST http://localhost:8080/mcp/core_visit_strategy_query -H "Content-Type: application/json" -d "{\"businessId\":\"1551015496417349\"}"
```

### 5. 下单占位 core_order_create

```bash
curl -X POST http://localhost:8080/mcp/core_order_create -H "Content-Type: application/json" -d "{\"requestJson\":\"{\\\"patientId\\\":\\\"P001\\\",\\\"items\\\":[{\\\"wareId\\\":\\\"102478\\\",\\\"qty\\\":1}]}\"}"
```

---

## 方式二：用 VS Code / Cursor 的 .http 文件（推荐）

项目根目录已提供 `mcp-api-test.http`：

1. 用 VS Code 或 Cursor 打开该文件。
2. 若未安装 **REST Client** 扩展，先安装（搜索 "REST Client"）。
3. 文件里每个 `###` 上方会出现 **Send Request** 链接，点击即可发送对应请求并查看响应。

可修改第一行 `@BASE = http://localhost:8080` 为你的服务地址（如 `http://localhost:8085`）。

---

## 预期返回结构

统一为：`{ "code": 0 或 非0, "msg": "说明", "data": ... }`

| 接口 | code=0 时 data 含义 |
|------|----------------------|
| core_order_query | 单条订单对象；无订单时 code=404 |
| core_insurance_query | 数组，医保/患者列表 |
| core_drug_query | 数组，药品列表 |
| core_visit_strategy_query | 当前固定 null，msg 为「暂无数据支持」 |
| core_order_create | 下单结果（包含 orderId 与中台返回对象等；详见接口返回 data） |

---

## 常见问题

- **连接被拒绝**：确认服务已启动，且端口与 BASE 一致。
- **订单/医保/药品查不到数据**：确认海典数据源 `haidian.datasource` 已配置且库中有对应表和数据。
- **Windows 下 curl 引号报错**：可用 PowerShell：  
  `Invoke-RestMethod -Method Post -Uri "http://localhost:8080/mcp/core_order_query" -ContentType "application/json" -Body '{"orderId":"001"}'`
