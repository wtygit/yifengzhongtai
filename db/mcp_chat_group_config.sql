-- MCP 群名称统一配置 + 订单表扩展字段（在海典同步库执行，与 mcp_order_create_request_log 同库）

-- 群配置：维护群名称与分词（JSON 数组），用于下单时解析 groupTokens 及运营查询
CREATE TABLE IF NOT EXISTS `mcp_chat_group_config` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `group_name` varchar(256) NOT NULL COMMENT '群名称（与下单传入 groupName 精确匹配）',
  `chat_id` varchar(128) DEFAULT NULL COMMENT '可选：IM 群稳定 ID',
  `segment_words` text NOT NULL COMMENT '分词 JSON 数组，如 ["一丰","恒瑞","艾瑞卡","省医店"]',
  `store_code` varchar(64) DEFAULT NULL COMMENT '可选：关联门店编码',
  `config_remark` varchar(500) DEFAULT NULL COMMENT '配置备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_name` (`group_name`(191)),
  KEY `idx_chat_id` (`chat_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 群名称与分词配置';

-- 订单表 A 扩展：冗余字段便于按分词检索（与 user_request_data 同步）
ALTER TABLE `mcp_order_create_request_log`
  ADD COLUMN `group_name` varchar(256) DEFAULT NULL COMMENT '群名称' AFTER `user_request_data`,
  ADD COLUMN `order_context_remark` varchar(1000) DEFAULT NULL COMMENT '下单备注（业务侧）' AFTER `group_name`,
  ADD COLUMN `user_group_nickname` varchar(128) DEFAULT NULL COMMENT '用户在群昵称' AFTER `order_context_remark`,
  ADD COLUMN `group_tokens_json` text DEFAULT NULL COMMENT '分词 JSON 数组' AFTER `user_group_nickname`,
  ADD COLUMN `group_tokens_search` varchar(512) DEFAULT NULL COMMENT '检索用 |词1|词2|' AFTER `group_tokens_json`,
  ADD KEY `idx_mcp_order_group_name` (`group_name`(191)),
  ADD KEY `idx_mcp_order_group_tokens_search` (`group_tokens_search`(191));
