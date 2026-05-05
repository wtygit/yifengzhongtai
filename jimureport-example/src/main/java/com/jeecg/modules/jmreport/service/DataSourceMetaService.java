package com.jeecg.modules.jmreport.service;

import java.util.List;
import java.util.Map;

/**
 * 数据源元信息服务：数据源列表、表列表、字段列表
 */
public interface DataSourceMetaService {

    /**
     * 列出已配置的数据源（来自 jimureport 内置表 jimu_report_data_source）
     */
    List<Map<String, Object>> listDataSources();

    /**
     * 列出指定数据源的表
     *
     * @param dataSourceId jimu_report_data_source.id
     */
    List<Map<String, Object>> listTables(String dataSourceId);

    /**
     * 列出指定数据源的字段
     *
     * @param dataSourceId jimu_report_data_source.id
     * @param tableName    表名（不含库名）
     */
    List<Map<String, Object>> listColumns(String dataSourceId, String tableName);

    /**
     * 根据数据源ID解析出对应的 JdbcTemplate，用于在该数据源所在数据库中执行 SQL。
     * 注意：仅支持 JDBC 类型数据源，FILES 等类型会返回 null。
     */
    org.springframework.jdbc.core.JdbcTemplate resolveJdbcTemplate(String dataSourceId);
}

