-- 本地报表库（spring.datasource）：缓存海典 antis_yifengdata_hub.corecmsstore，供门店号登录与下拉
-- 应用启动时也会自动 CREATE TABLE IF NOT EXISTS

CREATE TABLE IF NOT EXISTS jm_mcp_store_login (
  store_id VARCHAR(64) NOT NULL COMMENT '门店编号，对应小程序 corecmsstore，与登录用户名一致',
  store_name VARCHAR(512) DEFAULT NULL,
  login_password VARCHAR(256) DEFAULT NULL COMMENT '门店单独密码（为空则使用默认门店密码）',
  synced_at DATETIME DEFAULT NULL,
  PRIMARY KEY (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS jm_mcp_login_config (
  config_key VARCHAR(64) NOT NULL,
  config_value VARCHAR(512) DEFAULT NULL,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 管理员在页面「/mcp/store-login-admin」维护：admin_username / admin_password / store_default_password

-- 示例：可在库里直接改密码
-- UPDATE jm_mcp_login_config SET config_value='新管理员密码' WHERE config_key='admin_password';
-- UPDATE jm_mcp_login_config SET config_value='新门店默认密码' WHERE config_key='store_default_password';
-- UPDATE jm_mcp_store_login SET login_password='某门店专属密码' WHERE store_id='10387';
