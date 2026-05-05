-- MCP 下单记录（双表方案）
-- 在海典同步库中执行（与 application 中 haidian.datasource 指向的库一致，如 antis_yifengdata_hub）

-- 表A：保存用户传入的下单原始数据
CREATE TABLE IF NOT EXISTS `mcp_order_create_request_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `pending_id` varchar(64) NOT NULL COMMENT '本服务生成的待下单单号（payload.pendingId）',
  `order_id` varchar(128) DEFAULT NULL COMMENT '对外订单标识（优先中台返回 orderId，否则 pendingId）',
  `request_source` varchar(20) NOT NULL DEFAULT 'structured' COMMENT '请求来源：structured(结构化)/natural(自然语言)',
  `user_request_data` longtext COMMENT '用户传入的原始下单数据（JSON 字符串）',
  `audit_status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '审核状态：0待审核 1已通过 2已驳回',
  `audit_time` datetime DEFAULT NULL COMMENT '审核时间',
  `audit_remark` varchar(500) DEFAULT NULL COMMENT '审核备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_pending_id` (`pending_id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_audit_status` (`audit_status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP下单-用户原始请求记录（表A）';

-- 表B：保存我方生成订单数据 + 中台响应 + 小程序回调数据
CREATE TABLE IF NOT EXISTS `mcp_order_create_order_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `pending_id` varchar(64) NOT NULL COMMENT '本服务生成的待下单单号（payload.pendingId）',
  `order_id` varchar(128) DEFAULT NULL COMMENT '对外订单标识（优先中台返回 orderId，否则 pendingId）',
  `order_status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '订单状态：1预下单 2已领单 3配送中 4待上传凭证 5完成 6驳回 7已退单',
  `status_update_time` datetime DEFAULT NULL COMMENT '订单状态更新时间',
  `receiver_name` varchar(100) DEFAULT NULL COMMENT '领单人名称（小程序回调）',
  `completion_images_json` longtext COMMENT '完成图片内容（JSON字符串，小程序回调）',
  `middle_platform_url` varchar(512) DEFAULT NULL COMMENT '实际调用的中台地址',
  `generated_order_data` longtext COMMENT '我方生成的订单请求数据（发往中台 payload，JSON）',
  `middle_response` longtext COMMENT '中台 /api/PendingReceiver/Receive 完整响应 JSON',
  `mini_callback_data` longtext COMMENT '小程序回调数据（发票信息、业务扩展字段等，JSON）',
  `call_success` tinyint(1) NOT NULL DEFAULT '1' COMMENT '1 调用成功 0 调用异常',
  `error_message` varchar(2000) DEFAULT NULL COMMENT '调用失败时的异常信息',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_pending_id` (`pending_id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_order_status` (`order_status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP下单-订单与回调记录（表B）';
