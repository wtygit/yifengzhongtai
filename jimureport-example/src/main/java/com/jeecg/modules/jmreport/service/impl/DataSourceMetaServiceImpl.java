package com.jeecg.modules.jmreport.service.impl;

import com.jeecg.modules.jmreport.service.DataSourceMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.URI;
import java.util.*;

/**
 * 数据源元信息服务：数据源列表、表列表、字段列表。
 * 对于外部数据源（如海典），需使用其自身连接查询 information_schema，主库连接无法访问。
 */
@Service
public class DataSourceMetaServiceImpl implements DataSourceMetaService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceMetaServiceImpl.class);
    private static final String DS_TABLE = "jimu_report_data_source";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 海典同步库的 JdbcTemplate（如已配置）。
     * 当 jimu_report_data_source 中的 db_url 指向海典 RDS 时，优先复用该 Bean，避免密码加密带来的直连失败。
     */
    @Autowired(required = false)
    @Qualifier("haidianJdbcTemplate")
    private JdbcTemplate haidianJdbcTemplate;

    @Override
    public List<Map<String, Object>> listDataSources() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, db_type, db_url, type FROM " + DS_TABLE + " ORDER BY update_time DESC"
        );
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String id = asStr(r.get("id"));
            String name = asStr(r.get("name"));
            String dbType = asStr(r.get("db_type"));
            String dbUrl = asStr(r.get("db_url"));
            String type = asStr(r.get("type"));
            String schema = parseSchemaFromJdbcUrl(dbUrl);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("dbType", dbType);
            m.put("dbUrl", dbUrl);
            m.put("schema", schema);
            m.put("type", type);
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> listTables(String dataSourceId) {
        JdbcTemplate targetJdbc = getJdbcTemplateForDataSource(dataSourceId);
        if (targetJdbc == null) {
            return Collections.emptyList();
        }
        String schema = getSchemaByDataSourceId(dataSourceId);
        if (schema == null || schema.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return targetJdbc.queryForList(
                    "SELECT table_name AS tableName, table_comment AS tableComment " +
                            "FROM information_schema.tables " +
                            "WHERE table_schema = ? AND table_type = 'BASE TABLE' " +
                            "ORDER BY table_name ASC",
                    schema
            );
        } catch (Exception e) {
            log.warn("查询数据源表列表失败，dataSourceId={}，schema={}，错误：{}", dataSourceId, schema, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Map<String, Object>> listColumns(String dataSourceId, String tableName) {
        JdbcTemplate targetJdbc = getJdbcTemplateForDataSource(dataSourceId);
        if (targetJdbc == null) {
            return Collections.emptyList();
        }
        String schema = getSchemaByDataSourceId(dataSourceId);
        if (schema == null || schema.isEmpty()) {
            return Collections.emptyList();
        }
        if (!isSafeName(tableName)) {
            return Collections.emptyList();
        }
        try {
            return targetJdbc.queryForList(
                    "SELECT column_name AS columnName, data_type AS dataType, column_type AS columnType, " +
                            "column_comment AS columnComment, is_nullable AS isNullable " +
                            "FROM information_schema.columns " +
                            "WHERE table_schema = ? AND table_name = ? " +
                            "ORDER BY ordinal_position ASC",
                    schema, tableName
            );
        } catch (Exception e) {
            log.warn("查询表字段失败，dataSourceId={}，tableName={}，错误：{}", dataSourceId, tableName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public JdbcTemplate resolveJdbcTemplate(String dataSourceId) {
        // 这里直接复用内部的获取逻辑：
        // - 本地 MySQL（127.0.0.1/localhost）复用主库 jdbcTemplate，避免加密密码导致的直连失败；
        // - 海典等远程库按原有规则处理；
        // - 其它 JDBC 类型按 db_url/db_username/db_password 新建连接。
        return getJdbcTemplateForDataSource(dataSourceId);
    }

    /**
     * 根据数据源ID获取其 JDBC 连接并创建 JdbcTemplate。
     * 外部数据源（如海典）与主库不在同一 MySQL 实例，必须使用其自身连接查询。
     * 对于海典同步数据源，优先复用配置类中已定义的 haidianJdbcTemplate。
     */
    private JdbcTemplate getJdbcTemplateForDataSource(String dataSourceId) {
        if (dataSourceId == null || dataSourceId.trim().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT db_url, db_driver, db_username, db_password FROM " + DS_TABLE + " WHERE id = ? LIMIT 1",
                dataSourceId
        );
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        String dbUrl = asStr(r.get("db_url"));
        String dbDriver = asStr(r.get("db_driver"));
        String dbUsername = asStr(r.get("db_username"));
        String dbPassword = asStr(r.get("db_password"));

        if (dbUrl == null || dbUrl.trim().isEmpty()) {
            log.warn("数据源 db_url 为空，dataSourceId={}", dataSourceId);
            return null;
        }
        String urlLower = dbUrl.toLowerCase(Locale.ROOT);
        // 同一 MySQL 实例上的库（127.0.0.1 或 localhost）均可通过主库连接查 information_schema，无需新建连接，也避免密码加密带来的直连失败
        if (urlLower.contains("://127.0.0.1:3306/") || urlLower.contains("://localhost:3306/")) {
            log.debug("检测到本地 MySQL 数据源，复用主库 jdbcTemplate 查询 information_schema，dataSourceId={}", dataSourceId);
            return jdbcTemplate;
        }
        // 如果是海典同步数据源并且项目中已配置 haidianJdbcTemplate，则直接复用，避免加密密码解析问题
        if (dbUrl.contains("rm-bp19ohrgc6111ynzh1o.mysql.rds.aliyuncs.com") && haidianJdbcTemplate != null) {
            log.debug("检测到海典同步数据源，复用 haidianJdbcTemplate，dataSourceId={}", dataSourceId);
            return haidianJdbcTemplate;
        }
        // 排除非 JDBC 类型（如 FILES）
        if (!dbUrl.startsWith("jdbc:")) {
            log.debug("数据源非 JDBC 类型，跳过，dataSourceId={}", dataSourceId);
            return null;
        }
        try {
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setUrl(dbUrl);
            ds.setDriverClassName(dbDriver != null && !dbDriver.isEmpty() ? dbDriver : "com.mysql.cj.jdbc.Driver");
            ds.setUsername(dbUsername);
            ds.setPassword(dbPassword != null ? dbPassword : "");
            return new JdbcTemplate(ds);
        } catch (Exception e) {
            log.warn("创建数据源连接失败，dataSourceId={}，错误：{}", dataSourceId, e.getMessage());
            return null;
        }
    }

    private String getSchemaByDataSourceId(String dataSourceId) {
        if (dataSourceId == null || dataSourceId.trim().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT db_url FROM " + DS_TABLE + " WHERE id = ? LIMIT 1",
                dataSourceId
        );
        if (rows.isEmpty()) {
            return null;
        }
        return parseSchemaFromJdbcUrl(asStr(rows.get(0).get("db_url")));
    }

    private static boolean isSafeName(String name) {
        // 兼容海典/历史库中带连字符的表名（如 sale90-hydee）
        return name != null && name.matches("^[a-zA-Z0-9_\\-]+$");
    }

    private static String asStr(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 解析 JDBC URL 中的 schema/dbName：
     * jdbc:mysql://host:3306/schema?...
     */
    static String parseSchemaFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            return "";
        }
        String url = jdbcUrl.trim();
        if (!url.startsWith("jdbc:")) {
            return "";
        }
        try {
            // 去掉 jdbc: 前缀，交给 URI 解析
            URI uri = URI.create(url.substring(5));
            String path = uri.getPath(); // /schema
            if (path == null || path.length() <= 1) {
                return "";
            }
            String schema = path.startsWith("/") ? path.substring(1) : path;
            // schema 里可能包含额外路径，取第一段
            int slash = schema.indexOf('/');
            if (slash > 0) {
                schema = schema.substring(0, slash);
            }
            return schema;
        } catch (Exception ignored) {
            // 兼容少数非标准 JDBC URL：简单截取 / 后到 ? 前
            int idx = url.indexOf("://");
            if (idx < 0) return "";
            int slash = url.indexOf('/', idx + 3);
            if (slash < 0) return "";
            int q = url.indexOf('?', slash);
            String schema = (q > 0) ? url.substring(slash + 1, q) : url.substring(slash + 1);
            return schema;
        }
    }
}

