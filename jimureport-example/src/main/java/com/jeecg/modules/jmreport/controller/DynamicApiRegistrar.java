package com.jeecg.modules.jmreport.controller;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jeecg.modules.jmreport.entity.ApiField;
import com.jeecg.modules.jmreport.entity.ApiInfo;
import com.jeecg.modules.jmreport.entity.ApiTable;
import com.jeecg.modules.jmreport.entity.ApiParam;
import com.jeecg.modules.jmreport.mapper.ApiFieldMapper;
import com.jeecg.modules.jmreport.mapper.ApiInfoMapper;
import com.jeecg.modules.jmreport.mapper.ApiTableMapper;
import com.jeecg.modules.jmreport.service.ApiGeneratorService;

/**
 * 动态API注册器
 */
@Component
public class DynamicApiRegistrar implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(DynamicApiRegistrar.class);
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;
    
    @Autowired
    private ApiGeneratorService apiGeneratorService;
    
    @Autowired
    private ApiTableMapper apiTableMapper;
    
    @Autowired
    private ApiFieldMapper apiFieldMapper;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 注册所有API
        registerAllApis();
    }

    /**
     * 注册所有API
     */
    public void registerAllApis() {
        try {
            List<ApiInfo> apiList = apiGeneratorService.getApiList();
            for (ApiInfo apiInfo : apiList) {
                // 只注册启用状态的API，且 api_path 不能为空
                if (apiInfo.getStatus() == 1) {
                    if (apiInfo.getApiPath() == null || apiInfo.getApiPath().trim().isEmpty()) {
                        logger.warn("跳过注册：api_path 为空，apiId={}", apiInfo.getId());
                        continue;
                    }
                    registerApi(apiInfo);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 注销单个API（根据API路径）
     */
    public void unregisterApi(String apiPath) {
        try {
            if (apiPath == null || apiPath.trim().isEmpty()) {
                logger.debug("注销API跳过：apiPath 为空");
                return;
            }
            // 确保API路径以"/"开头
            if (!apiPath.startsWith("/")) {
                apiPath = "/" + apiPath;
            }
            
            // 查找所有已注册的映射
            Map<RequestMappingInfo, HandlerMethod> handlerMethods = requestMappingHandlerMapping.getHandlerMethods();
            List<RequestMappingInfo> toRemove = new ArrayList<>();
            
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
                RequestMappingInfo mappingInfo = entry.getKey();
                HandlerMethod handlerMethod = entry.getValue();
                
                // 检查Handler是否是DynamicApiHandler
                if (handlerMethod != null && handlerMethod.getBeanType() == DynamicApiHandler.class) {
                    // 检查路径是否匹配
                    if (mappingInfo.getPatternsCondition() != null) {
                        Set<String> patterns = mappingInfo.getPatternsCondition().getPatterns();
                        if (patterns.contains(apiPath)) {
                            // 检查方法是否包含GET或POST（动态API支持GET和POST）
                            if (mappingInfo.getMethodsCondition() != null) {
                                Set<RequestMethod> methods = mappingInfo.getMethodsCondition().getMethods();
                                if (methods.contains(RequestMethod.GET) || methods.contains(RequestMethod.POST)) {
                                    toRemove.add(mappingInfo);
                                    logger.debug("找到需要注销的API映射：{}，方法：{}", apiPath, methods);
                                }
                            } else {
                                // 如果没有指定方法，也认为是动态API（默认支持所有方法）
                                toRemove.add(mappingInfo);
                                logger.debug("找到需要注销的API映射（无方法限制）：{}", apiPath);
                            }
                        }
                    }
                }
            }
            
            // 注销找到的映射
            for (RequestMappingInfo mappingInfo : toRemove) {
                try {
                    requestMappingHandlerMapping.unregisterMapping(mappingInfo);
                    logger.info("成功注销API映射：{}", apiPath);
                } catch (Exception e) {
                    logger.warn("注销单个映射失败：{}，错误：{}", apiPath, e.getMessage());
                }
            }
            
            if (toRemove.isEmpty()) {
                logger.debug("未找到需要注销的API映射：{}", apiPath);
            }
        } catch (Exception e) {
            logger.warn("注销API映射失败：{}，错误：{}", apiPath, e.getMessage(), e);
            // 注销失败不影响主流程
        }
    }
    
    /**
     * 注销单个API（根据API ID）
     */
    public void unregisterApiById(String apiId) {
        try {
            ApiInfo apiInfo = apiGeneratorService.getApiById(apiId);
            if (apiInfo != null && apiInfo.getApiPath() != null) {
                unregisterApi(apiInfo.getApiPath());
            }
        } catch (Exception e) {
            logger.warn("根据API ID注销映射失败：{}，错误：{}", apiId, e.getMessage());
        }
    }

    /**
     * 注册单个API
     */
    public void registerApi(ApiInfo apiInfo) throws Exception {
        if (apiInfo == null) {
            logger.warn("注册API跳过：apiInfo 为空");
            return;
        }
        String path = apiInfo.getApiPath();
        if (path == null || path.trim().isEmpty()) {
            logger.warn("注册API跳过：api_path 为空，apiId={}", apiInfo.getId());
            return;
        }
        // 先注销相同路径的旧映射（如果存在）
        unregisterApi(path);
        
        // 创建API处理方法
        Method handlerMethod = DynamicApiHandler.class.getMethod("handleApiRequest", Map.class);
        
        // 构建请求映射信息
        String apiPath = path;
        // 确保API路径以"/"开头
        if (!apiPath.startsWith("/")) {
            apiPath = "/" + apiPath;
        }
        RequestMappingInfo.Builder mappingInfoBuilder = RequestMappingInfo
                .paths(apiPath)
                .methods(RequestMethod.GET, RequestMethod.POST)
                .produces("application/json"); // 明确指定返回JSON格式
        
        // 创建一个虚拟的Handler对象，用于处理API请求
        DynamicApiHandler handler = new DynamicApiHandler(apiInfo.getId(), this);
        
        // 注册API
        requestMappingHandlerMapping.registerMapping(
                mappingInfoBuilder.build(),
                handler,
                handlerMethod
        );
        
        logger.info("成功注册API：{}", apiInfo.getApiPath());
    }

    /**
     * 生成SQL查询语句
     */// 生成SQL查询语句
    public String generateSql(List<ApiTable> apiTables, List<ApiField> apiFields, Map<String, Object> requestParams) {
        return generateSql(apiTables, apiFields, requestParams, false, 0, 0);
    }

    // 生成带分页的SQL查询语句
    public String generateSql(List<ApiTable> apiTables, List<ApiField> apiFields, Map<String, Object> requestParams, boolean needPagination, int page, int pageSize) {
        logger.info("开始生成SQL查询语句，API表数量：{}", apiTables != null ? apiTables.size() : 0);
        logger.info("API表列表：{}", apiTables);
        logger.info("API字段列表：{}", apiFields);
        logger.info("请求参数：{}", requestParams);
        
        // 检查输入参数
        if (apiTables == null || apiTables.isEmpty()) {
            logger.error("API表列表为空，无法生成SQL");
            throw new IllegalArgumentException("API表列表不能为空");
        }
        if (apiFields == null || apiFields.isEmpty()) {
            logger.error("API字段列表为空，无法生成SQL");
            throw new IllegalArgumentException("API字段列表不能为空");
        }
        
        // 检查是否所有表的JOIN条件都是空或者"1=1"，如果是则执行数据合并查询而不是关联查询
        boolean isDataMergeMode = true;
        for (int i = 1; i < apiTables.size(); i++) {
            ApiTable table = apiTables.get(i);
            String joinCondition = table.getJoinCondition();
            logger.info("表 {} 的JOIN条件：{}", table.getTableName(), joinCondition);
            if (joinCondition != null && !joinCondition.trim().isEmpty() && !"1=1".equals(joinCondition.trim())) {
                isDataMergeMode = false;
                logger.info("表 {} 的JOIN条件不是空或1=1，切换为关联查询模式", table.getTableName());
                break;
            }
        }
        logger.info("是否为数据合并模式：{}", isDataMergeMode);
        
        // 如果是数据合并模式，使用UNION ALL将所有表的数据合并
        if (isDataMergeMode) {
            StringBuilder sqlBuilder = new StringBuilder();
            
            // 为每个表创建一个SELECT语句，然后使用UNION ALL合并
            for (int tableIndex = 0; tableIndex < apiTables.size(); tableIndex++) {
                ApiTable table = apiTables.get(tableIndex);
                String tableName = table.getTableName();
                String databaseName = table.getDatabaseName();
                String sqlDbName = resolveDatabaseName(databaseName);
                
                // 获取该表的字段
                List<ApiField> tableFields = new ArrayList<>();
                for (ApiField field : apiFields) {
                    if (field.getTableName().equals(tableName) && 
                        (databaseName == null || databaseName.trim().isEmpty() || Objects.equals(field.getDatabaseName(), databaseName))) {
                        tableFields.add(field);
                    }
                }
                
                // 只有当表有选中字段时才添加到查询中
                if (!tableFields.isEmpty()) {
                    // 添加UNION ALL分隔符（第一个查询不需要）
                    if (tableIndex > 0) {
                        sqlBuilder.append(" UNION ALL ");
                    }
                    
                    // 构建SELECT部分
                    sqlBuilder.append("SELECT ");
                    
                    // 为每个字段构建SELECT项，确保所有查询返回相同数量和类型的列
                    // 首先获取所有API字段，对于当前表没有的字段，使用NULL填充
                    for (int i = 0; i < apiFields.size(); i++) {
                        ApiField apiField = apiFields.get(i);
                        String fieldName = apiField.getFieldName();
                        String apiFieldTableName = apiField.getTableName();
                        String apiFieldDatabaseName = apiField.getDatabaseName();
                        
                        if (apiFieldTableName.equals(tableName) && 
                            (apiFieldDatabaseName == null || apiFieldDatabaseName.trim().isEmpty() || 
                             databaseName == null || databaseName.trim().isEmpty() || 
                             Objects.equals(apiFieldDatabaseName, databaseName))) {
                            // 当前表有这个字段，正常查询（SQL 中使用实际库名）
                            if (sqlDbName != null && !sqlDbName.trim().isEmpty()) {
                                sqlBuilder.append(sqlDbName).append(".");
                            }
                            sqlBuilder.append(tableName).append(".");
                            
                            // 提取不带表名前缀的字段名
                            String simpleFieldName = fieldName;
                            if (simpleFieldName.contains(".")) {
                                simpleFieldName = simpleFieldName.substring(simpleFieldName.lastIndexOf(".") + 1);
                            }
                            sqlBuilder.append(simpleFieldName);
                        } else {
                            // 当前表没有这个字段，使用NULL填充
                            sqlBuilder.append("NULL");
                        }
                        
                        // 处理字段别名
                        String alias = apiField.getAlias();
                        if (alias != null && !alias.trim().isEmpty()) {
                            sqlBuilder.append(" AS ").append(alias);
                        } else {
                            // 使用简单字段名作为别名
                            String simpleFieldName = fieldName;
                            if (simpleFieldName.contains(".")) {
                                simpleFieldName = simpleFieldName.substring(simpleFieldName.lastIndexOf(".") + 1);
                            }
                            sqlBuilder.append(" AS ").append(simpleFieldName);
                        }
                        
                        if (i < apiFields.size() - 1) {
                            sqlBuilder.append(", ");
                        }
                    }
                    
                    // 构建FROM部分（使用实际库名）
                    sqlBuilder.append(" FROM ");
                    if (sqlDbName != null && !sqlDbName.trim().isEmpty()) {
                        sqlBuilder.append(sqlDbName).append(".");
                    }
                    sqlBuilder.append(tableName);
                }
            }
            
            // 添加分页支持
            if (needPagination) {
                int offset = (page - 1) * pageSize;
                sqlBuilder.append(" LIMIT ").append(pageSize).append(" OFFSET ").append(offset);
            }
            
            logger.info("生成的SQL查询语句：{}", sqlBuilder.toString());
        return sqlBuilder.toString();
        }
        
        // 传统的关联查询模式
        StringBuilder sqlBuilder = new StringBuilder();
        
        // 统计字段名出现次数，用于处理重复列名
        Map<String, Integer> fieldCountMap = new HashMap<>();
        Map<String, Integer> fieldUsageMap = new HashMap<>();
        
        // 第一次遍历：统计字段名出现次数
        for (ApiField field : apiFields) {
            String fieldName = field.getFieldName();
            fieldCountMap.put(fieldName, fieldCountMap.getOrDefault(fieldName, 0) + 1);
        }
        
        // 构建SELECT部分
        sqlBuilder.append("SELECT ");
        for (int i = 0; i < apiFields.size(); i++) {
            ApiField field = apiFields.get(i);
            String fieldName = field.getFieldName();
            String tableName = field.getTableName();
            String databaseName = field.getDatabaseName();
            String sqlDbName = resolveDatabaseName(databaseName);
            
            // 查找表的别名
            String tableAlias = getTableAlias(apiTables, tableName, databaseName);
            if (tableAlias != null && !tableAlias.trim().isEmpty()) {
                sqlBuilder.append(tableAlias).append(".");
            } else {
                // 如果没有别名，使用完整的数据库表名（SQL 中使用实际库名）
                if (sqlDbName != null && !sqlDbName.trim().isEmpty()) {
                    sqlBuilder.append(sqlDbName).append(".");
                }
                sqlBuilder.append(tableName).append(".");
            }
            
            sqlBuilder.append(fieldName);
            
            // 处理字段别名
            String finalAlias = field.getAlias();
            if (finalAlias == null || finalAlias.trim().isEmpty()) {
                // 如果没有指定别名，检查是否需要自动生成别名
                if (fieldCountMap.get(fieldName) > 1) {
                    // 重复字段，自动生成别名
                    String usageKey = tableName + "." + fieldName;
                    int usageCount = fieldUsageMap.getOrDefault(usageKey, 0) + 1;
                    fieldUsageMap.put(usageKey, usageCount);
                    if (usageCount > 1) {
                        // 同一个表中同名字段多次出现（理论上不会发生）
                        finalAlias = tableName + "_" + fieldName + "_" + usageCount;
                    } else {
                        // 不同表中的同名字段
                        finalAlias = tableName + "_" + fieldName;
                    }
                }
            }
            
            // 添加别名
            if (finalAlias != null && !finalAlias.trim().isEmpty()) {
                sqlBuilder.append(" AS ").append(finalAlias);
            }
            
            if (i < apiFields.size() - 1) {
                sqlBuilder.append(", ");
            }
        }
        
        // 构建FROM部分（使用实际库名）
        sqlBuilder.append(" FROM ");
        for (int i = 0; i < apiTables.size(); i++) {
            ApiTable table = apiTables.get(i);
            String databaseName = table.getDatabaseName();
            String sqlDbName = resolveDatabaseName(databaseName);
            String tableName = table.getTableName();
            String tableAlias = table.getAlias();
            
            if (i == 0) {
                // 第一个表作为主表
                if (sqlDbName != null && !sqlDbName.trim().isEmpty()) {
                    sqlBuilder.append(sqlDbName).append(".");
                }
                sqlBuilder.append(tableName);
                if (tableAlias != null && !tableAlias.trim().isEmpty()) {
                    sqlBuilder.append(" AS ").append(tableAlias);
                }
            } else {
                // 后续表作为JOIN表
                String joinType = table.getJoinType();
                if (joinType == null || joinType.trim().isEmpty()) {
                    joinType = "INNER JOIN";
                }
                sqlBuilder.append(" ").append(joinType).append(" ");
                if (sqlDbName != null && !sqlDbName.trim().isEmpty()) {
                    sqlBuilder.append(sqlDbName).append(".");
                }
                sqlBuilder.append(tableName);
                if (tableAlias != null && !tableAlias.trim().isEmpty()) {
                    sqlBuilder.append(" AS ").append(tableAlias);
                }
                // 确保有JOIN条件
                if (table.getJoinCondition() != null && !table.getJoinCondition().trim().isEmpty()) {
                    sqlBuilder.append(" ON ").append(table.getJoinCondition());
                } else {
                    // 如果没有JOIN条件，使用默认的主键关联
                    // 这里假设表之间有相同的主键字段名，如id
                    ApiTable mainTable = apiTables.get(0);
                    String mainTableReference;
                    if (mainTable.getAlias() != null && !mainTable.getAlias().trim().isEmpty()) {
                        // 使用主表别名
                        mainTableReference = mainTable.getAlias();
                    } else {
                        // 使用主表名（可能包含数据库前缀）
                        mainTableReference = mainTable.getDatabaseName() != null && !mainTable.getDatabaseName().trim().isEmpty() ? 
                            mainTable.getDatabaseName() + "." + mainTable.getTableName() : 
                            mainTable.getTableName();
                    }
                    
                    String currentTableReference;
                    if (table.getAlias() != null && !table.getAlias().trim().isEmpty()) {
                        // 使用当前表别名
                        currentTableReference = table.getAlias();
                    } else {
                        // 使用当前表名（可能包含数据库前缀）
                        currentTableReference = tableName;
                    }
                    
                    sqlBuilder.append(" ON ").append(mainTableReference).append(".id = ").append(currentTableReference).append(".id");
                }
            }
        }
        
        // 构建WHERE条件（简单实现，只支持基本的等于条件；排除分页参数，避免拼成非法列名）
        Map<String, Object> whereParams = requestParams;
        if (requestParams != null && !requestParams.isEmpty()) {
            whereParams = new HashMap<>(requestParams);
            whereParams.remove("page");
            whereParams.remove("pageSize");
        }
        if (whereParams != null && !whereParams.isEmpty()) {
            sqlBuilder.append(" WHERE ");
            int paramCount = 0;
            for (Map.Entry<String, Object> entry : whereParams.entrySet()) {
                if (paramCount > 0) {
                    sqlBuilder.append(" AND ");
                }
                sqlBuilder.append(entry.getKey()).append(" = '").append(entry.getValue()).append("'");
                paramCount++;
            }
        }
        
        // 添加分页支持
        if (needPagination) {
            int offset = (page - 1) * pageSize;
            sqlBuilder.append(" LIMIT ").append(pageSize).append(" OFFSET ").append(offset);
        }
        
        return sqlBuilder.toString();
    }
    
    /**
     * 生成查询总记录数的SQL语句
     */
    public String generateCountSql(List<ApiTable> apiTables, List<ApiField> apiFields, Map<String, Object> requestParams) {
        // 检查是否所有表的JOIN条件都是"1=1"，如果是则执行数据合并查询而不是关联查询
        boolean isDataMergeMode = true;
        for (int i = 1; i < apiTables.size(); i++) {
            ApiTable table = apiTables.get(i);
            String joinCondition = table.getJoinCondition();
            if (joinCondition == null || !"1=1".equals(joinCondition.trim())) {
                isDataMergeMode = false;
                break;
            }
        }
        
        // 如果是数据合并模式，使用子查询和UNION ALL来计算总记录数
        if (isDataMergeMode) {
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT COUNT(*) FROM (");
            
            // 为每个表创建一个SELECT语句，然后使用UNION ALL合并
            for (int tableIndex = 0; tableIndex < apiTables.size(); tableIndex++) {
                ApiTable table = apiTables.get(tableIndex);
                String tableName = table.getTableName();
                String databaseName = table.getDatabaseName();
                
                // 获取该表的字段
                List<ApiField> tableFields = new ArrayList<>();
                for (ApiField field : apiFields) {
                    if (field.getTableName().equals(tableName) && 
                        (databaseName == null || databaseName.trim().isEmpty() || Objects.equals(field.getDatabaseName(), databaseName))) {
                        tableFields.add(field);
                    }
                }
                
                // 只有当表有选中字段时才添加到查询中
                if (!tableFields.isEmpty()) {
                    // 添加UNION ALL分隔符（第一个查询不需要）
                    if (tableIndex > 0) {
                        sqlBuilder.append(" UNION ALL ");
                    }
                    
                    // 构建SELECT部分（只需要选择一个字段即可）
                    sqlBuilder.append("SELECT 1 FROM ");
                    if (databaseName != null && !databaseName.trim().isEmpty()) {
                        sqlBuilder.append(databaseName).append(".");
                    }
                    sqlBuilder.append(tableName);
                }
            }
            
            sqlBuilder.append(") AS combined_data");
            return sqlBuilder.toString();
        }
        
        // 传统的关联查询模式
        StringBuilder sqlBuilder = new StringBuilder();
        
        // 构建SELECT COUNT(*)部分
        sqlBuilder.append("SELECT COUNT(*)");
        
        // 构建FROM部分（使用实际库名）
        sqlBuilder.append(" FROM ");
        for (int i = 0; i < apiTables.size(); i++) {
            ApiTable table = apiTables.get(i);
            String databaseName = table.getDatabaseName();
            String sqlDbName = resolveDatabaseName(databaseName);
            String tableName = table.getTableName();
            String tableAlias = table.getAlias();
            
            if (i == 0) {
                // 第一个表作为主表
                if (sqlDbName != null && !sqlDbName.trim().isEmpty()) {
                    sqlBuilder.append(sqlDbName).append(".");
                }
                sqlBuilder.append(tableName);
                if (tableAlias != null && !tableAlias.trim().isEmpty()) {
                    sqlBuilder.append(" AS ").append(tableAlias);
                }
            } else {
                // 后续表作为JOIN表
                String joinType = table.getJoinType();
                if (joinType == null || joinType.trim().isEmpty()) {
                    joinType = "INNER JOIN";
                }
                sqlBuilder.append(" ").append(joinType).append(" ");
                if (sqlDbName != null && !sqlDbName.trim().isEmpty()) {
                    sqlBuilder.append(sqlDbName).append(".");
                }
                sqlBuilder.append(tableName);
                if (tableAlias != null && !tableAlias.trim().isEmpty()) {
                    sqlBuilder.append(" AS ").append(tableAlias);
                }
                // 确保有JOIN条件
                if (table.getJoinCondition() != null && !table.getJoinCondition().trim().isEmpty()) {
                    sqlBuilder.append(" ON ").append(table.getJoinCondition());
                } else {
                    // 如果没有JOIN条件，使用默认的主键关联
                    ApiTable mainTable = apiTables.get(0);
                    String mainSqlDbName = resolveDatabaseName(mainTable.getDatabaseName());
                    String mainTableFullName = mainSqlDbName != null && !mainSqlDbName.trim().isEmpty() ? 
                        mainSqlDbName + "." + mainTable.getTableName() : 
                        mainTable.getTableName();
                    String currentTableFullName = (sqlDbName != null && !sqlDbName.trim().isEmpty()) ? sqlDbName + "." + tableName : tableName;
                    sqlBuilder.append(" ON ").append(mainTableFullName).append(".id = ").append(currentTableFullName).append(".id");
                }
            }
        }
        
        // 构建WHERE条件（简单实现，只支持基本的等于条件）
        if (requestParams != null && !requestParams.isEmpty()) {
            // 移除分页参数
            Map<String, Object> filteredParams = new HashMap<>(requestParams);
            filteredParams.remove("page");
            filteredParams.remove("pageSize");
            
            if (!filteredParams.isEmpty()) {
                sqlBuilder.append(" WHERE ");
                int paramCount = 0;
                for (Map.Entry<String, Object> entry : filteredParams.entrySet()) {
                    if (paramCount > 0) {
                        sqlBuilder.append(" AND ");
                    }
                    sqlBuilder.append(entry.getKey()).append(" = '").append(entry.getValue()).append("'");
                    paramCount++;
                }
            }
        }
        
        return sqlBuilder.toString();
    }

    /**
     * 获取表的别名
     */
    private String getTableAlias(List<ApiTable> apiTables, String tableName, String databaseName) {
        for (ApiTable table : apiTables) {
            if (table.getTableName().equals(tableName) && 
                (databaseName == null || Objects.equals(table.getDatabaseName(), databaseName))) {
                return table.getAlias();
            }
        }
        return null;
    }

    /**
     * 解析为实际 MySQL 库名（报表数据源名 -> JDBC URL 中的库名），生成 SQL 时使用以便默认连接能查到数据
     */
    private String resolveDatabaseName(String datasourceName) {
        if (datasourceName == null || datasourceName.trim().isEmpty()) {
            return datasourceName;
        }
        String actual = apiGeneratorService.getActualDatabaseName(datasourceName);
        return (actual != null && !actual.trim().isEmpty()) ? actual : datasourceName;
    }

    /**
     * 动态API处理器
     */
    public static class DynamicApiHandler {
        private String apiId;
        private DynamicApiRegistrar registrar;

        public DynamicApiHandler(String apiId, DynamicApiRegistrar registrar) {
            this.apiId = apiId;
            this.registrar = registrar;
        }

        @org.springframework.web.bind.annotation.ResponseBody
        public Map<String, Object> handleApiRequest(@org.springframework.web.bind.annotation.RequestParam(required = false) Map<String, Object> requestParams) {
            Map<String, Object> result = new HashMap<>();
            
            try {
                // 获取API信息
                ApiInfo apiInfo = registrar.apiGeneratorService.getApiById(apiId);
                if (apiInfo == null || apiInfo.getStatus() != 1) {
                    result.put("code", 404);
                    result.put("msg", "API不存在或已禁用");
                    return result;
                }
                
                // 判断API类型：sql类型或table类型
                String apiType = apiInfo.getApiType();
                String sqlContent = apiInfo.getSqlContent();
                logger.info("API类型判断，apiId: {}, apiType: {}, sqlContent存在: {}", apiId, apiType, sqlContent != null && !sqlContent.trim().isEmpty());
                
                // 优先判断：如果有sqlContent且不为空，则认为是SQL类型
                if (sqlContent != null && !sqlContent.trim().isEmpty()) {
                    // 如果有SQL内容，强制识别为SQL类型（即使apiType不是sql）
                    apiType = "sql";
                    logger.info("检测到SQL内容，强制设置为SQL类型，apiId: {}", apiId);
                } else if (apiType == null || apiType.isEmpty()) {
                    // 如果没有SQL内容且apiType为空，默认为表类型
                    apiType = "table";
                    logger.info("无SQL内容且API类型为空，设置为表类型，apiId: {}", apiId);
                }
                // 如果apiType不为空且没有SQL内容，保持原apiType（可能是table类型）
                
                logger.info("最终API类型: {}, apiId: {}", apiType, apiId);
                
                if ("sql".equals(apiType)) {
                    // SQL类型API：直接执行SQL
                    // sqlContent已在上面定义，直接使用
                    String sqlDatabaseName = apiInfo.getSqlDatabaseName();
                    
                    logger.info("SQL类型API处理开始，apiId: {}, sqlContent: {}", apiId, sqlContent);
                    
                    if (sqlContent == null || sqlContent.trim().isEmpty()) {
                        logger.error("SQL内容为空，apiId: {}", apiId);
                        result.put("code", 500);
                        result.put("msg", "SQL内容为空");
                        return result;
                    }
                    
                    // 替换SQL中的参数占位符（如 #{phone}）
                    // 优先按SQL中出现的占位符进行替换，避免遗漏导致 bad SQL grammar
                    String finalSql = sqlContent;
                    logger.info("原始SQL: {}", finalSql);
                    logger.info("请求参数: {}", requestParams);
                    
                    // 从SQL中提取所有 #{paramName} 占位符
                    java.util.regex.Pattern placeholderPattern = java.util.regex.Pattern.compile("#\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");
                    java.util.regex.Matcher matcher = placeholderPattern.matcher(sqlContent);
                    Set<String> sqlParamNames = new HashSet<>();
                    while (matcher.find()) {
                        sqlParamNames.add(matcher.group(1));
                    }
                    
                    if (!sqlParamNames.isEmpty()) {
                        // 获取API定义的参数（含默认值）
                        List<ApiParam> apiParams = registrar.apiGeneratorService.getApiParamsByApiId(apiId);
                        Map<String, String> paramDefaults = new HashMap<>();
                        Set<String> validParamNames = new HashSet<>();
                        for (ApiParam param : apiParams) {
                            if (param.getParamName() != null && param.getParamName().matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                                validParamNames.add(param.getParamName());
                                if (param.getDefaultValue() != null && !param.getDefaultValue().isEmpty()) {
                                    paramDefaults.put(param.getParamName(), param.getDefaultValue());
                                }
                            }
                        }
                        // 若未定义参数列表，则SQL中出现的占位符均可接受
                        if (validParamNames.isEmpty()) {
                            validParamNames = sqlParamNames;
                        }
                        logger.info("SQL占位符: {}, 有效参数: {}", sqlParamNames, validParamNames);
                        
                        for (String paramName : sqlParamNames) {
                            if (!validParamNames.contains(paramName)) {
                                continue;
                            }
                            Object paramValue = null;
                            if (requestParams != null && requestParams.containsKey(paramName)) {
                                paramValue = requestParams.get(paramName);
                            }
                            if (paramValue == null || (paramValue instanceof String && ((String) paramValue).isEmpty())) {
                                paramValue = paramDefaults.get(paramName);
                            }
                            if (paramValue == null || (paramValue instanceof String && ((String) paramValue).isEmpty())) {
                                result.put("code", 400);
                                result.put("msg", "缺少必要参数: " + paramName + "，请在请求中传入该参数（如 ?" + paramName + "=值）或在API设计中配置默认值");
                                return result;
                            }
                            String replacement;
                            String valueStr = paramValue.toString();
                            valueStr = valueStr.replace("'", "''").replace("\\", "\\\\");
                            if (valueStr.matches("^-?\\d+(\\.\\d+)?$")) {
                                replacement = valueStr;
                            } else {
                                replacement = "'" + valueStr + "'";
                            }
                            finalSql = finalSql.replaceAll("#\\{" + java.util.regex.Pattern.quote(paramName) + "\\}", replacement);
                            logger.info("参数 {} 已替换", paramName);
                        }
                    }
                    
                    // 兼容：若SQL中无占位符，但请求带了额外参数，也做替换（向后兼容）
                    if ((requestParams != null && !requestParams.isEmpty()) && sqlParamNames.isEmpty()) {
                        List<ApiParam> apiParams = registrar.apiGeneratorService.getApiParamsByApiId(apiId);
                        Set<String> validParamNames = new HashSet<>();
                        for (ApiParam param : apiParams) {
                            if (param.getParamName() != null && param.getParamName().matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                                validParamNames.add(param.getParamName());
                            }
                        }
                        for (Map.Entry<String, Object> entry : requestParams.entrySet()) {
                            String paramName = entry.getKey();
                            if (!paramName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) continue;
                            if (!validParamNames.isEmpty() && !validParamNames.contains(paramName)) continue;
                            Object paramValue = entry.getValue();
                            String replacement = (paramValue == null) ? "NULL" : 
                                ("'" + paramValue.toString().replace("'", "''").replace("\\", "\\\\") + "'");
                            if (paramValue != null && paramValue.toString().matches("^-?\\d+(\\.\\d+)?$")) {
                                replacement = paramValue.toString();
                            }
                            finalSql = finalSql.replaceAll("#\\{" + java.util.regex.Pattern.quote(paramName) + "\\}", replacement);
                        }
                    }
                    
                    // 检查替换后的SQL是否为空
                    if (finalSql == null || finalSql.trim().isEmpty()) {
                        logger.error("参数替换后SQL为空，原始SQL: {}, 请求参数: {}", sqlContent, requestParams);
                        result.put("code", 500);
                        result.put("msg", "参数替换后SQL为空，请检查SQL内容和参数配置");
                        return result;
                    }
                    
                    logger.info("最终SQL: {}", finalSql);
                    
                    // 执行SQL
                    Map<String, Object> execResult = registrar.apiGeneratorService.executeSql(
                        sqlDatabaseName != null && !sqlDatabaseName.isEmpty() ? sqlDatabaseName : null, 
                        finalSql
                    );
                    
                    // 构建响应结果
                    result.put("code", 200);
                    result.put("msg", "请求成功");
                    
                    if ("query".equals(execResult.get("type"))) {
                        // 查询类型
                        List<Map<String, Object>> queryData = (List<Map<String, Object>>) execResult.get("data");
                        result.put("data", queryData != null ? queryData : new ArrayList<>());
                        result.put("total", queryData != null ? queryData.size() : 0);
                    } else {
                        // 更新类型或其他类型
                        result.put("data", new ArrayList<>());
                        result.put("total", 0);
                        result.put("updateCount", execResult.get("updateCount"));
                        result.put("execType", execResult.get("type"));
                    }
                } else {
                    // 表类型API：原有的表查询逻辑
                    logger.info("处理表类型API，apiId: {}, apiPath: {}", apiId, apiInfo.getApiPath());
                    
                    // 获取API关联的表
                    List<ApiTable> apiTables = registrar.apiTableMapper.selectByApiId(apiId);
                    logger.info("API关联的表数量: {}", apiTables != null ? apiTables.size() : 0);
                    
                    // 获取API返回的字段
                    List<ApiField> apiFields = registrar.apiFieldMapper.selectByApiId(apiId);
                    logger.info("API返回的字段数量: {}", apiFields != null ? apiFields.size() : 0);
                    
                    // 检查表类型API是否有必要的表或字段
                    if ((apiTables == null || apiTables.isEmpty()) && (apiFields == null || apiFields.isEmpty())) {
                        logger.error("表类型API缺少必要的表和字段配置，apiId: {}", apiId);
                        result.put("code", 500);
                        result.put("msg", "表类型API缺少必要的表和字段配置，请检查API配置");
                        return result;
                    }
                    
                    // 处理分页参数
                    boolean needPagination = apiInfo.getNeedPagination() != null && apiInfo.getNeedPagination() == 1;
                    int page = 1;
                    int pageSize = apiInfo.getPageSize() != null ? apiInfo.getPageSize() : 20;
                    
                    // 从请求参数中获取分页信息
                    if (requestParams != null) {
                        if (requestParams.get("page") != null) {
                            try {
                                page = Integer.parseInt(requestParams.get("page").toString());
                            } catch (NumberFormatException e) {
                                page = 1;
                            }
                        }
                        if (requestParams.get("pageSize") != null) {
                            try {
                                pageSize = Integer.parseInt(requestParams.get("pageSize").toString());
                            } catch (NumberFormatException e) {
                                pageSize = apiInfo.getPageSize() != null ? apiInfo.getPageSize() : 20;
                            }
                        }
                    }
                    
                    // 生成SQL查询
                    String sql = registrar.generateSql(apiTables, apiFields, requestParams, needPagination, page, pageSize);
                    logger.info("动态API [{}] 生成SQL: {}", apiInfo.getApiPath(), sql);
                    
                    // 检查是否是跨数据库查询
                    boolean isCrossDatabase = registrar.apiGeneratorService.isCrossDatabase(apiTables);
                    List<Map<String, Object>> queryResult;
                    int total;
                    
                    if (isCrossDatabase) {
                        // 跨库时优先用默认连接执行完整 JOIN SQL（同一 MySQL 实例下 库名.表名 可查）
                        try {
                            queryResult = registrar.apiGeneratorService.executeQuery(sql);
                            total = queryResult.size();
                            logger.info("动态API [{}] 使用默认连接执行跨库SQL成功，结果数: {}", apiInfo.getApiPath(), total);
                        } catch (Exception e) {
                            logger.warn("动态API [{}] 默认连接执行跨库SQL失败，回退到分库查询合并: {}", apiInfo.getApiPath(), e.getMessage());
                            queryResult = registrar.apiGeneratorService.executeCrossDatabaseQuery(apiTables, apiFields, requestParams);
                            total = queryResult.size();
                        }
                        if (needPagination) {
                            String countSql = registrar.generateCountSql(apiTables, apiFields, requestParams);
                            try {
                                List<Map<String, Object>> countResult = registrar.apiGeneratorService.executeQuery(countSql);
                                if (!countResult.isEmpty()) {
                                    Object countObj = countResult.get(0).values().iterator().next();
                                    if (countObj instanceof Number) {
                                        total = ((Number) countObj).intValue();
                                    } else if (countObj != null) {
                                        total = Integer.parseInt(countObj.toString());
                                    }
                                }
                            } catch (Exception ignored) { }
                        }
                    } else {
                        // 同一数据库查询
                        queryResult = registrar.apiGeneratorService.executeQuery(sql);
                        total = queryResult.size();
                        if (needPagination) {
                            String countSql = registrar.generateCountSql(apiTables, apiFields, requestParams);
                            List<Map<String, Object>> countResult = registrar.apiGeneratorService.executeQuery(countSql);
                            if (!countResult.isEmpty()) {
                                Map<String, Object> countMap = countResult.get(0);
                                Object countObj = countMap.values().iterator().next();
                                if (countObj instanceof Number) {
                                    total = ((Number) countObj).intValue();
                                } else if (countObj != null) {
                                    try {
                                        total = Integer.parseInt(countObj.toString());
                                    } catch (NumberFormatException e) {
                                        total = 0;
                                    }
                                }
                            }
                        }
                    }
                    
                    // 构建响应结果
                    result.put("code", 200);
                    result.put("data", queryResult);
                    result.put("msg", "请求成功");
                    result.put("total", queryResult.size());
                }
                
            } catch (Exception e) {
                result.put("code", 500);
                result.put("msg", "请求失败：" + e.getMessage());
                e.printStackTrace();
            }
            
            return result;
        }
    }
}
