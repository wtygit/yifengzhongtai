# 修复SQL错误：审核字段缺失

## ❌ 错误信息

```
1054 - Unknown column 'r.audit_status' in 'field list'
```

## 🔍 问题原因

表 `mcp_order_create_request_log` 还没有添加审核状态相关字段：
- `audit_status` - 审核状态
- `audit_time` - 审核时间  
- `audit_remark` - 审核备注

## ✅ 解决方案

### 步骤1：执行字段添加脚本

**在海典同步库中执行**（不是jimureport主库）：

```sql
-- 添加审核状态字段
ALTER TABLE `mcp_order_create_request_log` 
ADD COLUMN `audit_status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '审核状态：0待审核 1已通过 2已驳回' AFTER `user_request_data`;

-- 添加审核时间字段
ALTER TABLE `mcp_order_create_request_log` 
ADD COLUMN `audit_time` datetime DEFAULT NULL COMMENT '审核时间' AFTER `audit_status`;

-- 添加审核备注字段
ALTER TABLE `mcp_order_create_request_log` 
ADD COLUMN `audit_remark` varchar(500) DEFAULT NULL COMMENT '审核备注' AFTER `audit_time`;

-- 添加索引
ALTER TABLE `mcp_order_create_request_log` 
ADD INDEX `idx_audit_status` (`audit_status`);
```

**或者直接执行文件**：`db/fix_missing_audit_fields.sql`

### 步骤2：验证字段是否添加成功

执行以下SQL检查：

```sql
DESC `mcp_order_create_request_log`;
```

应该能看到以下字段：
- `audit_status` (tinyint)
- `audit_time` (datetime)
- `audit_remark` (varchar)

### 步骤3：重新执行报表SQL

字段添加成功后，再执行报表查询SQL：
- MySQL 8.0+：`db/jimureport_mcp_order_request_view.sql`
- MySQL 5.7：`db/jimureport_mcp_order_request_view_mysql57.sql`

## 📝 完整操作流程

### 方式1：在积木报表SQL编辑器中执行

1. 进入积木报表工作台
2. 选择**海典同步库数据源**
3. 打开SQL编辑器
4. 执行 `db/fix_missing_audit_fields.sql` 中的SQL
5. 如果报错"字段已存在"，可以忽略（说明已经添加过了）
6. 验证：执行 `DESC mcp_order_create_request_log;` 查看字段
7. 然后执行报表查询SQL

### 方式2：直接在数据库中执行

1. 连接到海典同步库（与 `haidian.datasource` 配置一致）
2. 执行 `db/fix_missing_audit_fields.sql` 文件中的SQL
3. 验证字段是否添加成功
4. 在积木报表中使用查询SQL

## ⚠️ 重要提示

1. **必须在海典同步库执行**，不是jimureport主库
2. **如果字段已存在**，ALTER语句会报错，但可以忽略
3. **执行顺序**：先添加字段 → 再执行查询SQL

## 🔧 检查当前表结构

如果想先查看当前表结构，执行：

```sql
-- 查看表结构
DESC `mcp_order_create_request_log`;

-- 或查看所有字段
SHOW COLUMNS FROM `mcp_order_create_request_log`;

-- 检查是否有审核字段
SELECT COLUMN_NAME 
FROM INFORMATION_SCHEMA.COLUMNS 
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'mcp_order_create_request_log'
  AND COLUMN_NAME LIKE 'audit%';
```

如果上面的查询返回0行，说明字段不存在，需要执行添加字段的SQL。

## 📋 字段说明

| 字段名 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| audit_status | tinyint(4) | 0 | 审核状态：0待审核 1已通过 2已驳回 |
| audit_time | datetime | NULL | 审核时间 |
| audit_remark | varchar(500) | NULL | 审核备注 |

## ✅ 验证成功标志

执行 `DESC mcp_order_create_request_log;` 后，应该看到：

```
+----------------+--------------+------+-----+---------+----------------+
| Field          | Type         | Null | Key | Default | Extra          |
+----------------+--------------+------+-----+---------+----------------+
| ...            | ...          | ...  | ... | ...     | ...            |
| user_request_data | longtext   | YES  |     | NULL    |                |
| audit_status   | tinyint(4)   | NO   | MUL | 0       |                |
| audit_time     | datetime     | YES  |     | NULL    |                |
| audit_remark   | varchar(500) | YES  |     | NULL    |                |
| create_time    | datetime     | NO   | MUL | CURRENT_TIMESTAMP |      |
+----------------+--------------+------+-----+---------+----------------+
```

如果看到 `audit_status`、`audit_time`、`audit_remark` 这三个字段，说明添加成功！
