# MCP 接口对接指南（交付给第三方）

> 适用对象：第三方系统重新对接我方 MCP 接口  
> 交付目标：第三方拿到本文 + `mcp-api-test.http` 即可开始联调

---

## 1. 交付内容（你会收到哪些文件）

- **`README_对接指南.md`**：对接说明（本文）
- **`mcp-api-test.http`**：接口调用示例（可直接运行）

可选（如我方另行提供）：

- **`openapi.yaml/json`**：OpenAPI/Swagger 接口定义
- **错误码/枚举字典文档**：当业务枚举较多时会单独提供

---

## 2. 环境信息

请使用我方提供的环境地址（按实际替换）：

- **测试环境 Base URL**：`<TO_BE_FILLED>`
- **生产环境 Base URL**：`<TO_BE_FILLED>`
- **接口前缀（如有）**：`<TO_BE_FILLED，例如 /api/mcp>`

通用约定：

- **字符集**：UTF-8
- **Content-Type**：`application/json`（除非某些接口另行说明）
- **时间格式**：`<TO_BE_FILLED，例如 ISO8601 或 yyyy-MM-dd HH:mm:ss>`

网络要求（如适用）：

- **IP 白名单**：`<TO_BE_FILLED：是/否；如是，请第三方提供出口 IP>`
- **HTTPS/TLS**：建议 TLS1.2+

---

## 3. 鉴权方式

> 以 `mcp-api-test.http` 中的实际配置为准。若第三方使用其他工具（Postman/代码），按本文说明设置请求头/参数即可。

- **鉴权类型**：`<TO_BE_FILLED：Bearer Token / API Key / OAuth2 / 自定义签名>`
- **鉴权位置**：`<TO_BE_FILLED：Header / Query / Body>`

常见示例（如 Bearer Token）：

- Header：`Authorization: Bearer <access_token>`

如需通过接口获取 Token（按实际替换）：

- **获取 Token 接口**：`<TO_BE_FILLED：POST /oauth/token 或 /mcp/auth/token>`
- **请求参数**：`<TO_BE_FILLED：client_id/client_secret/grant_type 等>`
- **响应字段**：`access_token`、`expires_in`、`refresh_token（如有）`

安全建议：

- 测试账号/测试密钥建议**单独私发**，避免写入文档或示例文件的 Git 历史
- 如存在签名/验签（timestamp/nonce/sign），请以我方提供的签名规则文档为准

---

## 4. 如何使用 `mcp-api-test.http`

### 4.1 运行工具

以下任意一种均可：

- JetBrains 系列（IntelliJ IDEA / WebStorm 等）的 HTTP Client
- VSCode 的 REST Client 插件

### 4.2 必要配置

请先在 `mcp-api-test.http` 顶部或变量区按说明配置：

- `baseUrl`：环境地址
- `token`：访问令牌（如需要）
- 其他变量（如 `appId`/`secret`/`tenantId`/`storeId` 等）：以文件中定义为准

### 4.3 推荐执行顺序

- 先跑通鉴权（如需）
- 再按业务顺序调用（创建/查询/审核/回调等）

---

## 5. 接口清单与说明

> 以 `mcp-api-test.http` 中包含的接口为准。第三方对接时，请将“需要对接的接口列表”与我方确认（或以双方约定的业务范围为准）。

建议第三方在联调阶段至少提供：

- **请求示例**（含 Header/Body）
- **响应原文**（含 HTTP 状态码）
- **requestId/traceId**（如响应中包含）

---

## 6. 错误码与排障（通用）

HTTP 状态码建议理解方式：

- `200`：请求到达服务端，成功/失败以业务响应字段为准
- `400`：参数校验失败（缺字段、格式不对）
- `401/403`：鉴权失败/权限不足（token 过期、签名错误等）
- `429`：限流
- `500`：服务端异常

当出现问题时，请第三方反馈以下信息便于快速定位：

- 接口名称 + URL + 请求时间
- 完整的请求头（敏感信息可打码）与请求体
- 完整的响应体与 HTTP 状态码
- `requestId/traceId`（如有）

---

## 7. 回调/Webhook（如适用）

如对接范围包含回调/异步通知，我方会另行提供或在此处补充：

- 回调事件类型
- 回调数据结构
- 回调验签规则
- 失败重试策略与幂等字段

当前状态：`<TO_BE_FILLED：是否需要回调；如需要请向我方索取回调说明>`

---

## 8. 联系方式

- 技术对接人：`<TO_BE_FILLED>`
- 联系方式：`<TO_BE_FILLED：企业微信/邮箱/电话>`
- 支持时间：`<TO_BE_FILLED>`

