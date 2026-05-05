package com.jeecg.modules.jmreport.service;

import com.jeecg.modules.jmreport.entity.ApiInfo;
import com.jeecg.modules.jmreport.entity.ApiTable;
import com.jeecg.modules.jmreport.entity.ApiField;
import com.jeecg.modules.jmreport.entity.ApiParam;
import java.util.List;
import java.util.Map;

/**
 * API生成器服务接口
 */
public interface ApiGeneratorService {
    // ========================== API信息相关 ==========================
    
    /**
     * 获取API列表
     */
    List<ApiInfo> getApiList();
    
    /**
     * 获取API列表（支持模糊查询）
     */
    List<ApiInfo> getApiList(String apiName, String apiPath);
    
    /**
     * 获取API详情
     */
    ApiInfo getApiById(String apiId);
    
    /**
     * 根据API ID获取API表信息
     */
    List<ApiTable> getApiTablesByApiId(String apiId);
    
    /**
     * 根据API ID获取API字段信息
     */
    List<ApiField> getApiFieldsByApiId(String apiId);
    
    /**
     * 保存API信息
     * @return Map包含结果信息：{code: 200成功, 500失败, 409路径重复, msg: 消息}
     */
    Map<String, Object> saveApi(ApiInfo apiInfo, List<ApiTable> apiTables, List<ApiField> apiFields);
    
    /**
     * 保存API信息（包含参数）
     * @return Map包含结果信息：{code: 200成功, 500失败, 409路径重复, msg: 消息}
     */
    Map<String, Object> saveApi(ApiInfo apiInfo, List<ApiTable> apiTables, List<ApiField> apiFields, List<ApiParam> apiParams);
    
    /**
     * 根据API ID获取API参数信息
     */
    List<ApiParam> getApiParamsByApiId(String apiId);
    
    /**
     * 删除API
     */
    boolean deleteApi(String apiId);
    
    // ========================== 数据库表相关 ==========================
    
    /**
     * 获取数据库列表
     */
    List<Map<String, Object>> getDatabaseList();
    
    /**
     * 获取数据库表列表
     */
    List<Map<String, Object>> getTableList();
    
    /**
     * 根据数据库名称获取表列表
     */
    List<Map<String, Object>> getTableListByDatabase(String databaseName);
    
    /**
     * 获取表的字段信息
     */
    List<Map<String, Object>> getTableFields(String databaseName, String tableName);
    
    /**
     * 执行SQL查询
     */
    List<Map<String, Object>> executeQuery(String sql);

    /**
     * 在指定数据源上执行SQL（查询/增删改/DDL）
     * @param databaseName 数据源名称（对应报表工作台数据源 name；传 default/空则走默认数据源）
     * @param sql 要执行的SQL
     * @return 返回结构：{type: query|update|execute, data?: List<Map>, updateCount?: int}
     */
    Map<String, Object> executeSql(String databaseName, String sql);
    
    boolean isCrossDatabase(List<ApiTable> apiTables);
    
    List<Map<String, Object>> executeCrossDatabaseQuery(List<ApiTable> apiTables, List<ApiField> apiFields, Map<String, Object> requestParams);

    /**
     * 根据数据源名称（报表工作台中的名称，如 本地1）解析出实际 MySQL 库名（如 nas_system）。
     * 用于生成 SQL 时使用真实库名，以便用默认连接执行时能查到正确数据。
     * @param datasourceName 数据源名称，可为 null/空
     * @return 实际库名，解析失败或为空时返回原 datasourceName
     */
    String getActualDatabaseName(String datasourceName);
}
