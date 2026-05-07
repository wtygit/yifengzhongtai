package com.jeecg.modules.jmreport.service.impl;

import com.jeecg.modules.jmreport.satoken.config.SecurityConfig;
import com.jeecg.modules.jmreport.service.McpStoreLoginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class McpStoreLoginServiceImpl implements McpStoreLoginService {

    private static final String TABLE = "jm_mcp_store_login";
    private static final String CONFIG_TABLE = "jm_mcp_login_config";

    private static final String[] HAIDIAN_SYNC_SQL = new String[]{
            "SELECT id AS sid, storeName AS sname FROM corecmsstore ORDER BY id ASC LIMIT 3000",
            "SELECT id AS sid, name AS sname FROM corecmsstore ORDER BY id ASC LIMIT 3000",
            "SELECT storeId AS sid, storeName AS sname FROM corecmsstore ORDER BY storeId ASC LIMIT 3000"
    };

    @Autowired
    @Qualifier("jdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    @Qualifier("haidianJdbcTemplate")
    private JdbcTemplate haidianJdbcTemplate;

    @Autowired
    private SecurityConfig securityConfig;

    @Override
    public void ensureLocalTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS jm_mcp_store_login (
                  store_id VARCHAR(64) NOT NULL,
                  store_name VARCHAR(512) DEFAULT NULL,
                  login_password VARCHAR(256) DEFAULT NULL,
                  synced_at DATETIME DEFAULT NULL,
                  PRIMARY KEY (store_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS jm_mcp_login_config (
                  config_key VARCHAR(64) NOT NULL,
                  config_value VARCHAR(512) DEFAULT NULL,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (config_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);
        ensureColumnExists(TABLE, "login_password", "ALTER TABLE " + TABLE + " ADD COLUMN login_password VARCHAR(256) DEFAULT NULL");
        ensureColumnExists(TABLE, "synced_at", "ALTER TABLE " + TABLE + " ADD COLUMN synced_at DATETIME DEFAULT NULL");
        jdbcTemplate.update(
                "INSERT IGNORE INTO " + CONFIG_TABLE + " (config_key, config_value) VALUES (?, ?)",
                "admin_username",
                securityConfig != null && securityConfig.getUser() != null ? securityConfig.getUser().getName() : "admin");
        jdbcTemplate.update(
                "INSERT IGNORE INTO " + CONFIG_TABLE + " (config_key, config_value) VALUES (?, ?)",
                "admin_password",
                securityConfig != null && securityConfig.getUser() != null ? securityConfig.getUser().getPassword() : "123456");
        jdbcTemplate.update(
                "INSERT IGNORE INTO " + CONFIG_TABLE + " (config_key, config_value) VALUES (?, ?)",
                "store_default_password",
                securityConfig != null && StringUtils.hasText(securityConfig.getStoreDefaultPassword())
                        ? securityConfig.getStoreDefaultPassword() : "123456");
    }

    @Override
    public synchronized int syncFromHaidian() {
        ensureLocalTable();
        if (haidianJdbcTemplate == null) {
            log.warn("海典数据源未注入，跳过门店同步");
            return 0;
        }
        List<Map<String, Object>> rows = null;
        for (String sql : HAIDIAN_SYNC_SQL) {
            try {
                rows = haidianJdbcTemplate.queryForList(sql);
                break;
            } catch (Exception e) {
                log.debug("门店同步尝试 SQL 失败: {}", sql, e);
            }
        }
        if (rows == null || rows.isEmpty()) {
            log.warn("门店同步：海典 corecmsstore 未读到数据或连接失败");
            return 0;
        }
        String upsert = "INSERT INTO " + TABLE + " (store_id, store_name, synced_at) VALUES (?, ?, NOW()) "
                + "ON DUPLICATE KEY UPDATE store_name = VALUES(store_name), synced_at = VALUES(synced_at)";
        int n = 0;
        for (Map<String, Object> row : rows) {
            Object sid = row.get("sid");
            if (sid == null) {
                continue;
            }
            String storeId = String.valueOf(sid).trim();
            if (!StringUtils.hasText(storeId)) {
                continue;
            }
            Object sname = row.get("sname");
            String storeName = sname != null ? String.valueOf(sname) : null;
            jdbcTemplate.update(upsert, storeId, storeName);
            n++;
        }
        log.info("门店同步完成，写入/更新 {} 条（海典拉取 {} 行）", n, rows.size());
        return n;
    }

    @Override
    public boolean validateAdminLogin(String username, String password) {
        try {
            ensureLocalTable();
        } catch (Exception e) {
            log.warn("管理员登录：本地 jm_mcp 表初始化失败（{}），将仅用 application.yml 中的账号校验；积木报表 /jmreport/list 仍需要可用的 spring.datasource。", e.getMessage());
        }
        if (!StringUtils.hasText(username) || password == null) {
            return false;
        }
        String u = username.trim();
        String dbUser = getConfig("admin_username");
        String dbPwd = getConfig("admin_password");
        String fallbackUser = securityConfig != null && securityConfig.getUser() != null
                ? securityConfig.getUser().getName() : "admin";
        String fallbackPwd = securityConfig != null && securityConfig.getUser() != null
                ? securityConfig.getUser().getPassword() : "123456";
        String expectedUser = StringUtils.hasText(dbUser) ? dbUser : fallbackUser;
        String expectedPwd = StringUtils.hasText(dbPwd) ? dbPwd : fallbackPwd;
        return expectedUser.equals(u) && expectedPwd.equals(password);
    }

    @Override
    public boolean validateStoreLogin(String storeId, String password) {
        try {
            ensureLocalTable();
        } catch (Exception e) {
            log.warn("门店登录：本地表不可用（{}）", e.getMessage());
            return false;
        }
        if (!StringUtils.hasText(storeId) || password == null) {
            return false;
        }
        String id = storeId.trim();
        String dbDefault = getConfig("store_default_password");
        String expected = StringUtils.hasText(dbDefault)
                ? dbDefault
                : (securityConfig != null && StringUtils.hasText(securityConfig.getStoreDefaultPassword())
                        ? securityConfig.getStoreDefaultPassword() : "123456");
        List<Map<String, Object>> row;
        try {
            row = jdbcTemplate.queryForList(
                    "SELECT login_password FROM " + TABLE + " WHERE store_id = ? LIMIT 1",
                    id);
        } catch (Exception e) {
            // 兼容旧库结构，避免首次升级时因缺列导致登录 500
            log.warn("查询 login_password 失败，回退旧结构查询: {}", e.getMessage());
            row = jdbcTemplate.queryForList(
                    "SELECT store_id FROM " + TABLE + " WHERE store_id = ? LIMIT 1",
                    id);
        }
        if (row.isEmpty()) {
            return false;
        }
        Object custom = row.get(0).get("login_password");
        if (custom != null && StringUtils.hasText(String.valueOf(custom))) {
            expected = String.valueOf(custom).trim();
        }
        if (!expected.equals(password)) {
            return false;
        }
        // MySQL COUNT(1) 常映射为 Long，用 Long 避免类型转换异常导致登录 500
        Long c = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + TABLE + " WHERE store_id = ?",
                Long.class,
                id);
        return c != null && c > 0;
    }

    private void ensureColumnExists(String table, String column, String alterSql) {
        try {
            Long cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Long.class,
                    table,
                    column);
            if (cnt == null || cnt == 0) {
                jdbcTemplate.execute(alterSql);
            }
        } catch (Exception e) {
            log.warn("检查/补充字段失败 table={}, column={}, msg={}", table, column, e.getMessage());
        }
    }

    private String getConfig(String key) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT config_value FROM " + CONFIG_TABLE + " WHERE config_key = ? LIMIT 1", key);
            if (rows.isEmpty()) {
                return null;
            }
            Object v = rows.get(0).get("config_value");
            return v == null ? null : String.valueOf(v).trim();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<Map<String, Object>> listSyncedStores() {
        ensureLocalTable();
        List<Map<String, Object>> raw = jdbcTemplate.queryForList(
                "SELECT store_id AS id, store_name AS label FROM " + TABLE + " ORDER BY store_id ASC LIMIT 800");
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            Object id = row.get("id");
            if (id == null) {
                continue;
            }
            Object label = row.get("label");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(id).trim());
            m.put("label", label != null ? String.valueOf(label) : String.valueOf(id));
            list.add(m);
        }
        return list;
    }

    @Override
    public List<Map<String, Object>> listStoreAccountsForAdmin() {
        ensureLocalTable();
        List<Map<String, Object>> raw = jdbcTemplate.queryForList(
                "SELECT store_id, store_name, "
                        + "(login_password IS NOT NULL AND TRIM(login_password) <> '') AS custom_password "
                        + "FROM " + TABLE + " ORDER BY store_id ASC LIMIT 5000");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            Map<String, Object> m = new LinkedHashMap<>();
            Object sid = row.get("store_id");
            if (sid == null) {
                continue;
            }
            m.put("storeId", String.valueOf(sid).trim());
            Object nm = row.get("store_name");
            m.put("storeName", nm != null ? String.valueOf(nm) : "");
            Object cp = row.get("custom_password");
            boolean has = false;
            if (cp instanceof Number n) {
                has = n.intValue() != 0;
            } else if (cp instanceof Boolean b) {
                has = b;
            } else if (cp != null) {
                String s = String.valueOf(cp).trim();
                has = "1".equals(s) || "true".equalsIgnoreCase(s);
            }
            m.put("hasCustomPassword", has);
            out.add(m);
        }
        return out;
    }

    @Override
    public int setStoreLoginPassword(String storeId, String newPassword) {
        ensureLocalTable();
        if (!StringUtils.hasText(storeId)) {
            return 0;
        }
        String id = storeId.trim();
        if (!StringUtils.hasText(newPassword)) {
            return jdbcTemplate.update("UPDATE " + TABLE + " SET login_password = NULL WHERE store_id = ?", id);
        }
        return jdbcTemplate.update("UPDATE " + TABLE + " SET login_password = ? WHERE store_id = ?",
                newPassword.trim(), id);
    }

    @Override
    public void setStoreDefaultPassword(String newPassword) {
        ensureLocalTable();
        if (!StringUtils.hasText(newPassword)) {
            throw new IllegalArgumentException("门店默认密码不能为空");
        }
        upsertConfig("store_default_password", newPassword.trim());
    }

    @Override
    public boolean setAdminPassword(String currentPassword, String newPassword) {
        ensureLocalTable();
        if (currentPassword == null || newPassword == null) {
            return false;
        }
        if (!StringUtils.hasText(newPassword.trim())) {
            return false;
        }
        String cur = getConfig("admin_password");
        String fallback = securityConfig != null && securityConfig.getUser() != null
                ? securityConfig.getUser().getPassword() : "123456";
        String expected = StringUtils.hasText(cur) ? cur : fallback;
        if (!expected.equals(currentPassword)) {
            return false;
        }
        upsertConfig("admin_password", newPassword.trim());
        return true;
    }

    private void upsertConfig(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO " + CONFIG_TABLE + " (config_key, config_value) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)",
                key,
                value);
    }

    @Override
    public int upsertStoreAccount(String storeId, String storeName) {
        ensureLocalTable();
        if (!StringUtils.hasText(storeId)) {
            return 0;
        }
        String id = storeId.trim();
        String name = storeName != null ? storeName.trim() : null;
        return jdbcTemplate.update(
                "INSERT INTO " + TABLE + " (store_id, store_name, synced_at) VALUES (?, ?, NOW()) "
                        + "ON DUPLICATE KEY UPDATE store_name = COALESCE(VALUES(store_name), store_name), synced_at = VALUES(synced_at)",
                id,
                StringUtils.hasText(name) ? name : null);
    }

    @Override
    public String getStoreCustomPasswordPlain(String storeId) {
        ensureLocalTable();
        if (!StringUtils.hasText(storeId)) {
            return null;
        }
        String id = storeId.trim();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT login_password FROM " + TABLE + " WHERE store_id = ? LIMIT 1",
                    id);
            if (rows.isEmpty()) {
                return null;
            }
            Object p = rows.get(0).get("login_password");
            if (p == null) {
                return null;
            }
            String s = String.valueOf(p).trim();
            return StringUtils.hasText(s) ? s : null;
        } catch (Exception e) {
            log.warn("读取门店专属密码失败 storeId={}", id, e);
            return null;
        }
    }
}
