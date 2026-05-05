# SQL使用说明 - 重要提示

## ⚠️ MySQL版本问题

**如果你的MySQL版本是5.7，绝对不能使用 `jimureport_mcp_order_request_view.sql`（MySQL 8.0版本）！**

### 错误示例
```
1064 - You have an error in your SQL syntax; check the manual that corresponds to your MySQL server version for the right syntax to use near '(
    r.user_request_data,
    '$.items[*]' COLUMNS (
        drug_name VARCH' at line 39
```

这个错误说明你使用了MySQL 8.0版本的SQL，但数据库是MySQL 5.7。

## 解决方案

### 方案1：使用MySQL 5.7兼容版本（推荐）

**文件**：`db/jimureport_mcp_order_request_view_mysql57.sql`

**特点**：
- 使用子查询展开items数组
- 每个药品显示一行
- 完全兼容MySQL 5.7

**使用方法**：
1. 在积木报表中创建新报表
2. 选择海典同步库数据源
3. 复制 `jimureport_mcp_order_request_view_mysql57.sql` 中的SQL
4. 粘贴到SQL编辑器中执行

### 方案2：使用简化版本（最简单）

**文件**：`db/jimureport_mcp_order_request_view_simple.sql`

**特点**：
- 最简单，最兼容
- 每个订单显示一行
- 最多显示3个药品（drug_name_1, drug_name_2, drug_name_3）
- 如果超过3个药品，可通过 `user_request_data` 字段进一步解析

**适用场景**：
- MySQL 5.7
- 订单药品数量通常不超过3个
- 需要最简单的实现

## 如何确认MySQL版本

在数据库中执行：
```sql
SELECT VERSION();
```

- 如果显示 `5.7.x` → 使用MySQL 5.7版本的SQL
- 如果显示 `8.0.x` 或更高 → 可以使用MySQL 8.0版本的SQL

## SQL文件对照表

| 文件名 | MySQL版本 | 特点 | 推荐度 |
|--------|-----------|------|--------|
| `jimureport_mcp_order_request_view.sql` | 8.0+ | 使用JSON_TABLE，性能最好 | ⭐⭐⭐⭐⭐ (仅8.0+) |
| `jimureport_mcp_order_request_view_mysql57.sql` | 5.7+ | 使用子查询展开，功能完整 | ⭐⭐⭐⭐ (5.7推荐) |
| `jimureport_mcp_order_request_view_simple.sql` | 5.7+ | 最简单，每订单一行 | ⭐⭐⭐ (简单场景) |

## 常见问题

### Q: 我执行了MySQL 5.7版本的SQL，还是报错？

**A**: 可能的原因：
1. 表不存在：先执行 `db/mcp_order_create_log.sql` 创建表
2. 数据源错误：确保选择的是海典同步库数据源
3. JSON格式问题：检查 `user_request_data` 字段是否为有效的JSON

**解决方法**：
- 使用最简单的版本：`jimureport_mcp_order_request_view_simple.sql`
- 检查表是否存在：`SELECT COUNT(*) FROM mcp_order_create_request_log;`

### Q: 如何知道我的数据库是哪个版本？

**A**: 在积木报表的SQL编辑器中执行：
```sql
SELECT VERSION();
```

### Q: 我可以修改SQL吗？

**A**: 可以，但需要注意：
- MySQL 5.7不支持 `JSON_TABLE`
- 可以使用 `JSON_EXTRACT`、`JSON_UNQUOTE`、`JSON_LENGTH` 等函数
- 展开数组需要使用子查询或UNION

## 快速开始

1. **确认MySQL版本**：执行 `SELECT VERSION();`
2. **选择SQL文件**：
   - MySQL 8.0+ → `jimureport_mcp_order_request_view.sql`
   - MySQL 5.7 → `jimureport_mcp_order_request_view_mysql57.sql` 或 `jimureport_mcp_order_request_view_simple.sql`
3. **在积木报表中使用**：创建报表 → 选择海典同步库数据源 → 粘贴SQL → 执行
