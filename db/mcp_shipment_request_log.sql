-- MCP 发货登记（海典同步库执行，与 mcp_order_create_request_log 同库）
-- 客户通过 core_shipment_create 提交：姓名、地址、电话、邮寄方式必填

CREATE TABLE IF NOT EXISTS `mcp_shipment_request_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shipment_id` varchar(64) NOT NULL COMMENT '发货业务单号 SH-日期-随机',
  `recipient_name` varchar(128) NOT NULL COMMENT '收件人姓名',
  `recipient_phone` varchar(32) NOT NULL COMMENT '收件人电话',
  `address` varchar(512) NOT NULL COMMENT '收货地址',
  `ship_method` varchar(128) NOT NULL COMMENT '邮寄方式',
  `ship_status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '0待发货 1已发货',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `raw_request_data` text COMMENT '客户原始请求JSON',
  `ship_time` datetime DEFAULT NULL COMMENT '点击发货时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shipment_id` (`shipment_id`),
  KEY `idx_ship_status` (`ship_status`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_recipient_phone` (`recipient_phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP发货登记';
