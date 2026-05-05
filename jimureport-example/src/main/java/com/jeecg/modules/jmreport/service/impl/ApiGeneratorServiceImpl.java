package com.jeecg.modules.jmreport.service.impl;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import cn.dev33.satoken.stp.StpUtil;

import com.jeecg.modules.jmreport.controller.DynamicApiRegistrar;
import com.jeecg.modules.jmreport.entity.ApiField;
import com.jeecg.modules.jmreport.entity.ApiInfo;
import com.jeecg.modules.jmreport.entity.ApiTable;
import com.jeecg.modules.jmreport.entity.ApiParam;
import com.jeecg.modules.jmreport.mapper.ApiFieldMapper;
import com.jeecg.modules.jmreport.mapper.ApiInfoMapper;
import com.jeecg.modules.jmreport.mapper.ApiTableMapper;
import com.jeecg.modules.jmreport.mapper.ApiParamMapper;
import com.jeecg.modules.jmreport.service.ApiGeneratorService;

/**
 * API生成器服务实现
 */
@Service
public class ApiGeneratorServiceImpl implements ApiGeneratorService {

    private static final Logger logger = LoggerFactory.getLogger(ApiGeneratorServiceImpl.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private ApiInfoMapper apiInfoMapper;
    
    @Autowired
    private ApiTableMapper apiTableMapper;
    
    @Autowired
    private ApiFieldMapper apiFieldMapper;
    
    @Autowired
    private ApiParamMapper apiParamMapper;
    
    @Autowired
    @Qualifier("dataSource")
    private DataSource dataSource;
    
    @Autowired
    private RestTemplate restTemplate;
    


    // ========================== API信息相关 ==========================
    
    @Override
    public List<ApiInfo> getApiList() {
        try {
            return apiInfoMapper.selectList();
        } catch (Exception e) {
            // 如果表不存在或其他异常，返回空列表而不是抛出异常
            String errorMessage = e.getMessage() != null ? e.getMessage() : "未知错误"; 
            System.out.println("获取API列表失败: " + errorMessage);
            return new ArrayList<>();
        }
    }
    
    @Override
    public List<ApiInfo> getApiList(String apiName, String apiPath) {
        try {
            List<ApiInfo> apiList = apiInfoMapper.selectListByCondition(apiName, apiPath);
            logger.info("查询API列表，条件：apiName={}, apiPath={}, 结果数量：{}", apiName, apiPath, apiList != null ? apiList.size() : 0);
            if (apiList != null && !apiList.isEmpty()) {
                for (ApiInfo api : apiList) {
                    logger.info("API信息：id={}, name={}, path={}, type={}, status={}", 
                        api.getId(), api.getApiName(), api.getApiPath(), api.getApiType(), api.getStatus());
                }
            }
            return apiList;
        } catch (Exception e) {
            // 如果表不存在或其他异常，返回空列表而不是抛出异常
            String errorMessage = e.getMessage() != null ? e.getMessage() : "未知错误"; 
            logger.error("获取API列表失败: {}", errorMessage, e);
            return new ArrayList<>();
        }
    }
    
    @Override
    public ApiInfo getApiById(String apiId) {
        return apiInfoMapper.selectById(apiId);
    }
    
    @Override
    public List<ApiTable> getApiTablesByApiId(String apiId) {
        try {
            return apiTableMapper.selectByApiId(apiId);
        } catch (Exception e) {
            // 如果表不存在或其他异常，返回空列表而不是抛出异常
            System.out.println("获取API表信息失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    @Override
    public List<ApiField> getApiFieldsByApiId(String apiId) {
        try {
            return apiFieldMapper.selectByApiId(apiId);
        } catch (Exception e) {
            // 如果表不存在或其他异常，返回空列表而不是抛出异常
            System.out.println("获取API字段信息失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    @Override
    public List<ApiParam> getApiParamsByApiId(String apiId) {
        try {
            return apiParamMapper.selectByApiId(apiId);
        } catch (Exception e) {
            System.out.println("获取API参数信息失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveApi(ApiInfo apiInfo, List<ApiTable> apiTables, List<ApiField> apiFields) {
        return saveApi(apiInfo, apiTables, apiFields, null);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveApi(ApiInfo apiInfo, List<ApiTable> apiTables, List<ApiField> apiFields, List<ApiParam> apiParams) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String apiId = apiInfo.getId();
            String apiPath = apiInfo.getApiPath();
            
            // 检查api_path是否已经存在
            List<ApiInfo> existingApis = apiInfoMapper.selectListByCondition(null, apiPath);
            if (!existingApis.isEmpty()) {
                // 如果api_path已经存在，检查是否是当前API的更新操作
                if (apiId == null || "".equals(apiId) || !existingApis.get(0).getId().equals(apiId)) {
                    // 如果是新增操作或更新的不是同一个API，则返回路径重复错误
                    result.put("code", 409);
                    result.put("msg", "API路径已存在：" + apiPath);
                    return result;
                }
            }
            
            // 判断API类型：如果是表类型API，需要更新表和字段；如果是SQL类型API，不需要表和字段
            String apiType = apiInfo.getApiType();
            if (apiType == null || apiType.isEmpty()) {
                // 如果apiType为空，检查是否有sqlContent来判断类型
                if (apiInfo.getSqlContent() != null && !apiInfo.getSqlContent().trim().isEmpty()) {
                    apiType = "sql";
                } else {
                    apiType = "table";
                }
            }
            
            // 如果是新增API
            if (apiId == null || "".equals(apiId)) {
                apiId = UUID.randomUUID().toString().replace("-", "");
                apiInfo.setId(apiId);
                Date now = new Date();
                apiInfo.setCreateTime(now);
                apiInfo.setUpdateTime(now); // 设置update_time字段
                apiInfo.setStatus(1); // 默认为启用状态
                apiInfoMapper.insert(apiInfo);
                
                // 只有表类型API才需要保存表和字段
                if ("table".equals(apiType)) {
                    // 保存API关联的表（增加空值检查）
                    if (apiTables != null && !apiTables.isEmpty()) {
                        for (ApiTable apiTable : apiTables) {
                            apiTable.setId(UUID.randomUUID().toString().replace("-", ""));
                            apiTable.setApiId(apiId);
                            apiTableMapper.insert(apiTable);
                        }
                    }
                    
                    // 保存API返回的字段（增加空值检查）
                    if (apiFields != null && !apiFields.isEmpty()) {
                        for (ApiField apiField : apiFields) {
                            apiField.setId(UUID.randomUUID().toString().replace("-", ""));
                            apiField.setApiId(apiId);
                            apiFieldMapper.insert(apiField);
                        }
                    }
                }
            } else {
                // 如果是更新API
                apiInfo.setUpdateTime(new Date());
                apiInfoMapper.updateById(apiInfo);
                
                // 只有表类型API才需要更新表和字段
                if ("table".equals(apiType)) {
                    // 检查是否传递了表和字段数据
                    boolean hasTables = apiTables != null && !apiTables.isEmpty();
                    boolean hasFields = apiFields != null && !apiFields.isEmpty();
                    
                    if (hasTables && hasFields) {
                        // 如果传递了表和字段，先删除旧的关联数据，然后保存新的
                        apiTableMapper.deleteByApiId(apiId);
                        apiFieldMapper.deleteByApiId(apiId);
                        
                        // 保存API关联的表
                        for (ApiTable apiTable : apiTables) {
                            apiTable.setId(UUID.randomUUID().toString().replace("-", ""));
                            apiTable.setApiId(apiId);
                            apiTableMapper.insert(apiTable);
                        }
                        
                        // 保存API返回的字段
                        for (ApiField apiField : apiFields) {
                            apiField.setId(UUID.randomUUID().toString().replace("-", ""));
                            apiField.setApiId(apiId);
                            apiFieldMapper.insert(apiField);
                        }
                        
                        logger.info("更新表类型API成功，apiId: {}, 表数量: {}, 字段数量: {}", 
                            apiId, apiTables.size(), apiFields.size());
                    } else {
                        // 如果没有传递表和字段，保留原有数据，只记录警告日志
                        logger.warn("更新表类型API时，表或字段数据为空，保留原有数据，apiId: {}, apiTables: {}, apiFields: {}", 
                            apiId, 
                            apiTables != null ? apiTables.size() : 0, 
                            apiFields != null ? apiFields.size() : 0);
                    }
                } else {
                    // SQL类型API不需要表和字段，但需要删除旧的关联数据（如果有）
                    apiTableMapper.deleteByApiId(apiId);
                    apiFieldMapper.deleteByApiId(apiId);
                }
            }
            
            // 保存API参数（增加空值检查）
            if (apiParams != null && !apiParams.isEmpty()) {
                for (ApiParam apiParam : apiParams) {
                    apiParam.setId(UUID.randomUUID().toString().replace("-", ""));
                    apiParam.setApiId(apiId);
                    apiParamMapper.insert(apiParam);
                }
            }
            
            result.put("code", 200);
            result.put("msg", "保存成功");
        } catch (Exception e) {
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "保存失败：" + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteApi(String apiId) {
        boolean result = false;
        
        try {
            // 删除API基本信息
            apiInfoMapper.deleteById(apiId);
            
            // 删除API关联的表
            apiTableMapper.deleteByApiId(apiId);
            
            // 删除API返回的字段
            apiFieldMapper.deleteByApiId(apiId);
            
            // 删除API参数
            apiParamMapper.deleteByApiId(apiId);
            
            result = true;
        } catch (Exception e) {
            e.printStackTrace();
            result = false;
        }
        
        return result;
    }
    
    // ========================== 数据库表相关 ==========================
    
    @Override
    public List<Map<String, Object>> getDatabaseList() {
        List<Map<String, Object>> databaseList = new ArrayList<>();
        
        try {
            // 调用报表工作台的getDataSourceByPage接口获取数据源列表
            String url = "http://localhost:39001/jmreport/getDataSourceByPage?pageSize=100&pageNo=1";
            
            // 创建请求实体
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            // 添加认证Token
            if (StpUtil.isLogin()) {
                headers.set("satoken", StpUtil.getTokenInfo().getTokenValue());
            }
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            // 发送GET请求
            ResponseEntity<Map> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                entity, 
                Map.class
            );
            
            // 处理响应
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null && "200".equals(String.valueOf(responseBody.get("code")))) {
                Map<String, Object> result = (Map<String, Object>) responseBody.get("result");
                if (result != null) {
                    List<Map<String, Object>> dataSources = (List<Map<String, Object>>) result.get("records");
                    if (dataSources != null) {
                        for (Map<String, Object> dataSource : dataSources) {
                            Map<String, Object> databaseMap = new HashMap<>();
                            // 使用数据源名称作为数据库名称
                            databaseMap.put("databaseName", dataSource.get("name"));
                            // 存储数据源ID，方便后续获取详细配置
                            databaseMap.put("dataSourceId", dataSource.get("id"));
                            databaseList.add(databaseMap);
                        }
                    }
                }
            }
            
            logger.info("从报表工作台获取数据源列表成功，共{}个数据源", databaseList.size());
        } catch (Exception e) {
            logger.error("调用报表工作台getDataSourceByPage接口失败: {}", e.getMessage(), e);
            
            // 如果获取报表工作台数据源失败，回退到默认数据源获取方式
            try (Connection conn = dataSource.getConnection()) {
                DatabaseMetaData metaData = conn.getMetaData();
                ResultSet rs = metaData.getCatalogs();
                
                while (rs.next()) {
                    Map<String, Object> databaseMap = new HashMap<>();
                    String databaseName = rs.getString("TABLE_CAT");
                    databaseMap.put("databaseName", databaseName);
                    databaseList.add(databaseMap);
                }
            } catch (SQLException ex) {
                logger.error("获取默认数据库列表失败: {}", ex.getMessage(), ex);
            }
        }
        
        return databaseList;
    }
    
    @Override
    public List<Map<String, Object>> getTableList() {
        List<Map<String, Object>> tableList = new ArrayList<>();
        
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet rs = metaData.getTables(conn.getCatalog(), null, null, new String[]{"TABLE"});
            
            while (rs.next()) {
                Map<String, Object> tableMap = new HashMap<>();
                tableMap.put("tableName", rs.getString("TABLE_NAME"));
                tableMap.put("tableComment", rs.getString("REMARKS"));
                tableList.add(tableMap);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        
        return tableList;
    }
    
    @Override
    public List<Map<String, Object>> getTableListByDatabase(String databaseName) {
        List<Map<String, Object>> tableList = new ArrayList<>();
        
        try {
            // 获取对应的数据源
            DataSource currentDataSource = getDataSourceFromReportWorkbench(databaseName);
            
            if (currentDataSource == null) {
                // 如果获取数据源失败，使用默认数据源
                currentDataSource = dataSource;
            }
            
            try (Connection conn = currentDataSource.getConnection()) {
                DatabaseMetaData metaData = conn.getMetaData();
                // 获取当前数据库名称
                String currentDatabaseName = conn.getCatalog();
                ResultSet rs = metaData.getTables(currentDatabaseName, null, null, new String[]{"TABLE"});
                
                while (rs.next()) {
                    Map<String, Object> tableMap = new HashMap<>();
                    tableMap.put("tableName", rs.getString("TABLE_NAME"));
                    tableMap.put("tableComment", rs.getString("REMARKS"));
                    tableList.add(tableMap);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return tableList;
    }
    
    @Override
    public List<Map<String, Object>> getTableFields(String databaseName, String tableName) {
        List<Map<String, Object>> fieldList = new ArrayList<>();
        
        try {
            // 获取对应的数据源
            DataSource currentDataSource = getDataSourceFromReportWorkbench(databaseName);
            
            if (currentDataSource == null) {
                // 如果获取数据源失败，使用默认数据源
                currentDataSource = dataSource;
            }
            
            try (Connection conn = currentDataSource.getConnection()) {
                DatabaseMetaData metaData = conn.getMetaData();
                // 获取当前数据库名称
                String currentDatabaseName = conn.getCatalog();
                ResultSet rs = metaData.getColumns(currentDatabaseName, null, tableName, null);
                
                while (rs.next()) {
                    Map<String, Object> fieldMap = new HashMap<>();
                    fieldMap.put("fieldName", rs.getString("COLUMN_NAME"));
                    fieldMap.put("dataType", rs.getString("TYPE_NAME"));
                    fieldMap.put("columnSize", rs.getInt("COLUMN_SIZE"));
                    fieldMap.put("description", rs.getString("REMARKS"));
                    fieldMap.put("nullable", rs.getInt("NULLABLE"));
                    fieldList.add(fieldMap);
                }
                
                // 获取主键信息
                ResultSet primaryKeys = metaData.getPrimaryKeys(currentDatabaseName, null, tableName);
                while (primaryKeys.next()) {
                    String primaryKey = primaryKeys.getString("COLUMN_NAME");
                    // 标记主键
                    for (Map<String, Object> fieldMap : fieldList) {
                        if (primaryKey.equals(fieldMap.get("fieldName"))) {
                            fieldMap.put("isPrimary", 1);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return fieldList;
    }
    
    @Override
    public List<Map<String, Object>> executeQuery(String sql) {
        return jdbcTemplate.queryForList(sql);
    }

    @Override
    public Map<String, Object> executeSql(String databaseName, String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new IllegalArgumentException("sql不能为空");
        }
        String trimmed = sql.trim();
        JdbcTemplate jdbc = getJdbcTemplateByDatabaseName(databaseName);

        Map<String, Object> result = new HashMap<>();
        String lower = trimmed.toLowerCase();

        // 查询类SQL：select / with / show / desc / describe
        if (lower.startsWith("select")
                || lower.startsWith("with")
                || lower.startsWith("show")
                || lower.startsWith("desc")
                || lower.startsWith("describe")) {
            List<Map<String, Object>> data = jdbc.queryForList(trimmed);
            result.put("type", "query");
            result.put("data", data);
            return result;
        }

        // 常见增删改：insert / update / delete / replace
        if (lower.startsWith("insert")
                || lower.startsWith("update")
                || lower.startsWith("delete")
                || lower.startsWith("replace")) {
            int updateCount = jdbc.update(trimmed);
            result.put("type", "update");
            result.put("updateCount", updateCount);
            return result;
        }

        // 其他（DDL/混合语句等）：执行即可
        jdbc.execute(trimmed);
        result.put("type", "execute");
        result.put("updateCount", 0);
        return result;
    }
    
    /**
     * 检查API中的表是否来自不同数据库
     * @param apiTables API关联的表列表
     * @return true表示跨数据库，false表示同一数据库
     */
    public boolean isCrossDatabase(List<ApiTable> apiTables) {
        if (apiTables == null || apiTables.size() <= 1) {
            return false;
        }
        
        // 获取所有表的信息
        Set<String> fullTableNames = new HashSet<>();
        Set<String> databaseNames = new HashSet<>();
        
        for (ApiTable table : apiTables) {
            String databaseName = table.getDatabaseName();
            String tableName = table.getTableName();
            
            // 优先使用databaseName字段
            if (databaseName != null && !databaseName.trim().isEmpty()) {
                databaseNames.add(databaseName.trim());
            } else if (tableName != null && tableName.contains(".")) {
                // 如果表名包含点号，假设是database.table格式
                int dotIndex = tableName.indexOf(".");
                if (dotIndex > 0) {
                    databaseNames.add(tableName.substring(0, dotIndex).trim());
                    fullTableNames.add(tableName.trim());
                } else {
                    fullTableNames.add(tableName.trim());
                }
            } else {
                // 如果没有databaseName且表名不含点号，使用完整表名
                fullTableNames.add(tableName.trim());
            }
        }
        
        // 检查逻辑：
        // 1. 如果有多个不同的数据库名，说明是跨数据库查询
        // 2. 如果没有明确的数据库名，但有多个表，默认视为跨数据库查询
        // 3. 这是为了兼容用户没有在表名前加数据库前缀的情况
        if (databaseNames.size() > 1) {
            return true;
        } else if (databaseNames.isEmpty() && fullTableNames.size() > 1) {
            // 如果没有明确的数据库名，但有多个表，默认视为跨数据库查询
            return true;
        }
        
        // 新增逻辑：如果有明确的数据库名，并且表数量大于1，也视为跨数据库查询
        // 这是为了确保当用户使用外部数据库时，多个表的查询会被视为跨数据库查询
        if (databaseNames.size() == 1 && fullTableNames.size() > 1) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 根据数据库名获取对应的JdbcTemplate
     * @param databaseName 数据库名
     * @return 对应的JdbcTemplate
     */
    public JdbcTemplate getJdbcTemplateByDatabaseName(String databaseName) {
        if (databaseName == null || databaseName.trim().isEmpty() || "default".equals(databaseName)) {
            // 使用默认JdbcTemplate
            return jdbcTemplate;
        }
        
        // 从报表工作台数据源中获取对应的数据源
        DataSource dataSource = getDataSourceFromReportWorkbench(databaseName);
        if (dataSource != null) {
            return new JdbcTemplate(dataSource);
        }
        
        // 如果找不到对应的数据源，不应该使用默认的JdbcTemplate
        // 因为默认的JdbcTemplate可能连接到错误的数据库
        throw new RuntimeException("无法获取数据库'" + databaseName + "'的数据源配置");
    }
    
    /**
     * 从报表工作台中获取数据源
     * @param databaseName 数据库名
     * @return 对应的数据源
     */
    private DataSource getDataSourceFromReportWorkbench(String databaseName) {
        try {
            logger.info("开始获取数据源: {}", databaseName);
            
            // 首先获取数据源ID
            String dataSourceId = getDataSourceIdByName(databaseName);
            logger.info("获取到数据源ID: {}", dataSourceId);
            
            if (dataSourceId == null) {
                logger.error("未找到名称为{}的数据源", databaseName);
                return null;
            }
            
            // 调用报表工作台的getDataSourceById接口获取数据源详细配置
            String url = "http://localhost:39001/jmreport/getDataSourceById?id=" + dataSourceId;
            
            // 创建请求实体
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            
            // 添加认证Token
            if (StpUtil.isLogin()) {
                headers.set("satoken", StpUtil.getTokenInfo().getTokenValue());
                logger.info("已添加认证Token");
            } else {
                logger.warn("未登录，无法添加认证Token");
            }
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            // 发送GET请求
            logger.info("正在请求数据源配置: {}", url);
            ResponseEntity<Map> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                entity, 
                Map.class
            );
            
            // 处理响应
            Map<String, Object> responseBody = response.getBody();
            logger.info("数据源配置请求响应: {}", responseBody);
            
            if (responseBody != null) {
                String code = String.valueOf(responseBody.get("code"));
                String msg = String.valueOf(responseBody.get("msg"));
                logger.info("数据源配置请求状态: {}, 消息: {}", code, msg);
                
                if ("200".equals(code)) {
                    Map<String, Object> dataSourceConfig = (Map<String, Object>) responseBody.get("result");
                    if (dataSourceConfig != null) {
                        logger.info("获取到数据源配置: {}", dataSourceConfig);
                        
                        // 根据配置创建数据源
                        DriverManagerDataSource dataSource = new DriverManagerDataSource();
                        dataSource.setDriverClassName((String) dataSourceConfig.get("dbDriver"));
                        dataSource.setUrl((String) dataSourceConfig.get("dbUrl"));
                        dataSource.setUsername((String) dataSourceConfig.get("dbUsername"));
                        dataSource.setPassword((String) dataSourceConfig.get("dbPassword"));
                        
                        logger.info("从报表工作台获取数据源配置成功，数据源名称: {}", databaseName);
                        return dataSource;
                    } else {
                        logger.error("数据源配置为空");
                    }
                } else {
                    logger.error("获取数据源配置失败，状态码: {}, 消息: {}", code, msg);
                }
            } else {
                logger.error("数据源配置请求响应为空");
            }
        } catch (Exception e) {
            logger.error("调用报表工作台getDataSourceById接口失败: {}，数据库名: {}", e.getMessage(), databaseName, e);
        }
        
        logger.error("无法从报表工作台获取数据源配置，数据库名: {}", databaseName);
        return null;
    }
    
    /**
     * 执行跨数据库查询
     * @param apiTables API关联的表列表
     * @param apiFields API返回的字段列表
     * @param requestParams 请求参数
     * @return 联合查询结果
     */
    @Override
    public List<Map<String, Object>> executeCrossDatabaseQuery(List<ApiTable> apiTables, List<ApiField> apiFields, Map<String, Object> requestParams) {
        // 1. 按表分组字段
        Map<String, List<ApiField>> fieldsByTable = new HashMap<>();
        
        // 按表名分组字段
        for (ApiField field : apiFields) {
            String tableName = field.getTableName();
            fieldsByTable.computeIfAbsent(tableName, k -> new ArrayList<>()).add(field);
        }
        
        // 2. 为每张表单独执行查询
        Map<String, List<Map<String, Object>>> resultsByTable = new HashMap<>();
        
        for (ApiTable table : apiTables) {
            String tableName = table.getTableName();
            String databaseName = table.getDatabaseName();
            if (databaseName == null || databaseName.trim().isEmpty()) {
                databaseName = "default";
            }
            
            // 获取该表的字段
            List<ApiField> fields = fieldsByTable.getOrDefault(tableName, new ArrayList<>());
            
            // 生成该表的查询SQL
            String sql = generateSqlForSingleTable(databaseName, table, fields, requestParams);
            
            // 获取该数据库对应的JdbcTemplate
            JdbcTemplate jdbcTemplate = getJdbcTemplateByDatabaseName(databaseName);
            
            // 执行查询
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
            
            // 保存结果
            resultsByTable.put(tableName, result);
        }
        
        // 3. 联合所有表的查询结果
        return combineResultsByTable(resultsByTable, apiTables, apiFields);
    }
    
    /**
     * 为单张表生成查询SQL
     * @param databaseName 数据库名
     * @param table 表对象
     * @param apiFields 该表的字段列表
     * @param requestParams 请求参数
     * @return 查询SQL
     */
    private String generateSqlForSingleTable(String databaseName, ApiTable table, List<ApiField> apiFields, Map<String, Object> requestParams) {
        // 这里需要根据实际的SQL生成逻辑来实现
        // 为单张表生成完整的SQL查询
        StringBuilder sqlBuilder = new StringBuilder("SELECT ");
        
        // 添加字段
        for (int i = 0; i < apiFields.size(); i++) {
            ApiField field = apiFields.get(i);
            if (i > 0) {
                sqlBuilder.append(", ");
            }
            
            // 提取不带表名前缀的字段名
            String fullFieldName = field.getFieldName();
            String simpleFieldName = fullFieldName;
            if (simpleFieldName.contains(".")) {
                simpleFieldName = simpleFieldName.substring(simpleFieldName.lastIndexOf(".") + 1);
            }
            
            // 在SQL中使用简单字段名
            sqlBuilder.append(simpleFieldName);
            
            // 添加别名
            if (field.getAlias() != null && !field.getAlias().isEmpty()) {
                sqlBuilder.append(" AS ").append(field.getAlias());
            } else {
                // 如果没有别名，确保查询结果中的字段名与filterFields方法预期的一致
                // 这里不需要额外处理，因为JDBC会返回简单字段名
            }
        }
        
        // 添加表
        sqlBuilder.append(" FROM ").append(table.getTableName());
        
        // 添加查询条件
        if (requestParams != null && !requestParams.isEmpty()) {
            // 只添加与当前表相关的查询条件
            sqlBuilder.append(" WHERE 1=1");
            for (Map.Entry<String, Object> entry : requestParams.entrySet()) {
                String paramName = entry.getKey();
                Object paramValue = entry.getValue();
                
                // 简单实现：如果参数名以表名开头，则认为是该表的字段
                if (paramName.startsWith(table.getTableName() + ".")) {
                    String fieldName = paramName.substring(table.getTableName().length() + 1);
                    sqlBuilder.append(" AND ").append(fieldName).append(" = '").append(paramValue).append("'");
                } else {
                    // 如果参数名不是表名开头，也尝试作为该表的字段
                    sqlBuilder.append(" AND ").append(paramName).append(" = '").append(paramValue).append("'");
                }
            }
        }
        
        return sqlBuilder.toString();
    }
    
    /**
     * 联合多张表的查询结果
     * @param resultsByTable 各个表的查询结果
     * @param apiTables API关联的表列表
     * @param apiFields 所有字段列表
     * @return 联合后的结果
     */
    private List<Map<String, Object>> combineResultsByTable(Map<String, List<Map<String, Object>>> resultsByTable, List<ApiTable> apiTables, List<ApiField> apiFields) {
        // 处理单表情况
        if (apiTables.size() == 1) {
            String tableName = apiTables.get(0).getTableName();
            List<Map<String, Object>> results = resultsByTable.get(tableName);
            if (results != null) {
                // 确保只返回API指定的字段
                return filterFields(results, apiFields);
            }
            return new ArrayList<>();
        }
        
        // 检查是否所有表的JOIN条件都是空或者"1=1"，如果是则执行数据合并查询而不是关联查询
        boolean isDataMergeMode = true;
        for (int i = 1; i < apiTables.size(); i++) {
            ApiTable table = apiTables.get(i);
            String joinCondition = table.getJoinCondition();
            if (joinCondition != null && !joinCondition.trim().isEmpty() && !"1=1".equals(joinCondition.trim())) {
                isDataMergeMode = false;
                break;
            }
        }
        
        // 如果是数据合并模式，将所有表的数据合并到一起
        if (isDataMergeMode) {
            List<Map<String, Object>> mergedResults = new ArrayList<>();
            
            // 遍历所有表，将每个表的所有记录添加到结果中
            for (ApiTable table : apiTables) {
                String tableName = table.getTableName();
                List<Map<String, Object>> tableResults = resultsByTable.get(tableName);
                if (tableResults != null) {
                    mergedResults.addAll(tableResults);
                }
            }
            
            // 确保只返回API指定的字段
            return filterFields(mergedResults, apiFields);
        }
        
        // 处理多表情况（关联查询模式）
        List<Map<String, Object>> combinedResults = new ArrayList<>();
        
        // 1. 构建表之间的关联关系
        Map<String, Map<String, String>> tableRelations = new HashMap<>();
        
        // 分析API表中的关联关系
        for (ApiTable table : apiTables) {
            if (table.getJoinCondition() != null && !table.getJoinCondition().isEmpty()) {
                String joinCondition = table.getJoinCondition().trim();
                
                // 特殊处理：如果JOIN条件是"1=1"，视为没有明确的JOIN条件
                if ("1=1".equals(joinCondition)) {
                    continue;
                }
                
                // 解析JOIN条件，获取关联字段
                // 简单解析：假设格式为 "table1.field1 = table2.field2"
                String[] parts = joinCondition.split("=");
                if (parts.length == 2) {
                    String leftPart = parts[0].trim();
                    String rightPart = parts[1].trim();
                    
                    // 验证：确保关联条件包含有效的表名和字段名
                    // 有效的格式应该是 "table.field = table.field"
                    if (leftPart.contains(".") && rightPart.contains(".")) {
                        // 存储关联关系
                        Map<String, String> relation = new HashMap<>();
                        relation.put("left", leftPart);
                        relation.put("right", rightPart);
                        tableRelations.put(table.getTableName(), relation);
                    }
                }
            }
        }
        
        // 2. 根据关联关系进行关联查询
        // 实现：使用主表和其他表进行关联
        if (apiTables.size() >= 2 && !tableRelations.isEmpty()) {
            ApiTable mainTable = apiTables.get(0);
            String mainTableName = mainTable.getTableName();
            
            // 获取主表数据
            List<Map<String, Object>> mainResults = resultsByTable.get(mainTableName);
            if (mainResults == null) {
                return new ArrayList<>();
            }
            
            // 对每个主表记录，关联其他表的数据
            for (Map<String, Object> mainRow : mainResults) {
                // 创建联合结果记录
                Map<String, Object> combinedRow = new HashMap<>(mainRow);
                
                // 关联其他表
                boolean hasRelatedData = true;
                for (int i = 1; i < apiTables.size(); i++) {
                    ApiTable joinTable = apiTables.get(i);
                    String joinTableName = joinTable.getTableName();
                    
                    // 获取关联关系
                    Map<String, String> relation = tableRelations.get(joinTableName);
                    if (relation == null) {
                        continue;
                    }
                    
                    // 解析关联字段
                    String leftField = relation.get("left");
                    String rightField = relation.get("right");
                    
                    // 提取表名和字段名
                    String[] leftParts = leftField.split("\\.");
                    String[] rightParts = rightField.split("\\.");
                    
                    // 确定关联的主表字段和关联表字段
                    String mainFieldName = "";
                    String joinFieldName = "";
                    
                    if (leftParts.length >= 2 && leftParts[0].equals(mainTableName)) {
                        mainFieldName = leftParts[1];
                    } else if (rightParts.length >= 2 && rightParts[0].equals(mainTableName)) {
                        mainFieldName = rightParts[1];
                    } else {
                        // 如果找不到主表字段，使用第一个字段作为关联字段
                        mainFieldName = leftParts[leftParts.length - 1];
                    }
                    
                    if (leftParts.length >= 2 && leftParts[0].equals(joinTableName)) {
                        joinFieldName = leftParts[1];
                    } else if (rightParts.length >= 2 && rightParts[0].equals(joinTableName)) {
                        joinFieldName = rightParts[1];
                    } else {
                        // 如果找不到关联表字段，使用第二个字段作为关联字段
                        joinFieldName = rightParts[rightParts.length - 1];
                    }
                    
                    // 获取关联表数据
                    List<Map<String, Object>> joinResults = resultsByTable.get(joinTableName);
                    if (joinResults == null) {
                        hasRelatedData = false;
                        break;
                    }
                    
                    // 查找匹配的关联表记录（比较时统一转字符串，避免 Integer/Long/String 导致匹配失败）
                    Object mainValue = mainRow.get(mainFieldName);
                    boolean foundMatch = false;
                    String mainValueStr = mainValue != null ? mainValue.toString().trim() : null;
                    
                    if (mainValueStr != null && !mainValueStr.isEmpty()) {
                        for (Map<String, Object> joinRow : joinResults) {
                            Object joinValue = joinRow.get(joinFieldName);
                            String joinValueStr = joinValue != null ? joinValue.toString().trim() : null;
                            if (joinValueStr != null && mainValueStr.equals(joinValueStr)) {
                                // 合并关联表数据，避免覆盖主表中的字段
                                for (Map.Entry<String, Object> joinEntry : joinRow.entrySet()) {
                                    String joinKey = joinEntry.getKey();
                                    // 只有当主表中没有相同的字段名时，才添加关联表的字段
                                    // 这是为了避免字段名冲突，特别是当不同表中有相同名称的字段时
                                    if (!combinedRow.containsKey(joinKey)) {
                                        combinedRow.put(joinKey, joinEntry.getValue());
                                    }
                                }
                                foundMatch = true;
                                break;
                            }
                        }
                    }
                    
                    if (!foundMatch) {
                        hasRelatedData = false;
                        break;
                    }
                }
                
                if (hasRelatedData) {
                    combinedResults.add(combinedRow);
                }
            }
        } 
        // 3. 如果没有明确的JOIN条件，尝试基于相同字段进行关联
        else if (apiTables.size() >= 2) {
            // 找出所有表的共同字段
            Set<String> commonFields = new HashSet<>();
            boolean firstTable = true;
            
            // 收集所有表的字段名
            Map<String, Set<String>> tableFields = new HashMap<>();
            for (ApiTable table : apiTables) {
                String tableName = table.getTableName();
                List<Map<String, Object>> tableResults = resultsByTable.get(tableName);
                if (tableResults != null && !tableResults.isEmpty()) {
                    Set<String> fields = tableResults.get(0).keySet();
                    tableFields.put(tableName, fields);
                    
                    // 找出共同字段
                    if (firstTable) {
                        commonFields.addAll(fields);
                        firstTable = false;
                    } else {
                        commonFields.retainAll(fields);
                    }
                }
            }
            
            // 如果有共同字段，基于共同字段进行关联
            if (!commonFields.isEmpty()) {
                // 选择第一个表作为主表
                ApiTable mainTable = apiTables.get(0);
                String mainTableName = mainTable.getTableName();
                List<Map<String, Object>> mainResults = resultsByTable.get(mainTableName);
                
                if (mainResults != null) {
                    // 选择第一个共同字段作为关联字段
                    String joinField = commonFields.iterator().next();
                    
                    for (Map<String, Object> mainRow : mainResults) {
                        Map<String, Object> combinedRow = new HashMap<>(mainRow);
                        boolean hasRelatedData = true;
                        
                        // 关联其他表
                        for (int i = 1; i < apiTables.size(); i++) {
                            ApiTable joinTable = apiTables.get(i);
                            String joinTableName = joinTable.getTableName();
                            List<Map<String, Object>> joinResults = resultsByTable.get(joinTableName);
                            
                            if (joinResults != null) {
                                Object mainValue = mainRow.get(joinField);
                                boolean foundMatch = false;
                                
                                if (mainValue != null) {
                                    for (Map<String, Object> joinRow : joinResults) {
                                        Object joinValue = joinRow.get(joinField);
                                        if (joinValue != null && mainValue.equals(joinValue)) {
                                            // 合并关联表数据，避免覆盖主表中的字段
                                            for (Map.Entry<String, Object> joinEntry : joinRow.entrySet()) {
                                                String joinKey = joinEntry.getKey();
                                                if (!combinedRow.containsKey(joinKey)) {
                                                    combinedRow.put(joinKey, joinEntry.getValue());
                                                }
                                            }
                                            foundMatch = true;
                                            break;
                                        }
                                    }
                                }
                                
                                if (!foundMatch) {
                                    hasRelatedData = false;
                                    break;
                                }
                            }
                        }
                        
                        if (hasRelatedData) {
                            combinedResults.add(combinedRow);
                        }
                    }
                }
            } 
            // 如果没有共同字段，使用笛卡尔积关联（需要谨慎使用，可能导致性能问题）
            else {
                // 选择第一个表作为主表
                ApiTable mainTable = apiTables.get(0);
                String mainTableName = mainTable.getTableName();
                List<Map<String, Object>> mainResults = resultsByTable.get(mainTableName);
                
                if (mainResults != null) {
                    for (Map<String, Object> mainRow : mainResults) {
                        Map<String, Object> combinedRow = new HashMap<>(mainRow);
                        
                        // 关联其他表（笛卡尔积）
                        boolean hasRelatedData = true;
                        for (int i = 1; i < apiTables.size(); i++) {
                            ApiTable joinTable = apiTables.get(i);
                            String joinTableName = joinTable.getTableName();
                            List<Map<String, Object>> joinResults = resultsByTable.get(joinTableName);
                            
                            if (joinResults != null && !joinResults.isEmpty()) {
                                // 只取第一条记录
                                Map<String, Object> joinRow = joinResults.get(0);
                                // 合并关联表数据，避免覆盖主表中的字段
                                for (Map.Entry<String, Object> joinEntry : joinRow.entrySet()) {
                                    String joinKey = joinEntry.getKey();
                                    if (!combinedRow.containsKey(joinKey)) {
                                        combinedRow.put(joinKey, joinEntry.getValue());
                                    }
                                }
                            }
                        }
                        
                        combinedResults.add(combinedRow);
                    }
                }
            }
        }
        
        // 确保只返回API指定的字段
        return filterFields(combinedResults, apiFields);
    }
    
    /**
     * 过滤字段，只返回API指定的字段
     * @param results 原始结果
     * @param apiFields API字段列表
     * @return 过滤后的结果
     */
    private List<Map<String, Object>> filterFields(List<Map<String, Object>> results, List<ApiField> apiFields) {
        if (results == null || results.isEmpty() || apiFields == null || apiFields.isEmpty()) {
            return results;
        }
        
        List<Map<String, Object>> finalResults = new ArrayList<>();
        
        for (Map<String, Object> row : results) {
            Map<String, Object> finalRow = new HashMap<>();
            
            for (ApiField field : apiFields) {
                String fullFieldName = field.getFieldName();
                String alias = field.getAlias();
                
                // 提取不带表名前缀的字段名
                String simpleFieldName = fullFieldName;
                if (simpleFieldName.contains(".")) {
                    simpleFieldName = simpleFieldName.substring(simpleFieldName.lastIndexOf(".") + 1);
                }
                
                // 尝试获取字段值
                Object value = null;
                // 尝试直接使用完整字段名
                if (row.containsKey(fullFieldName)) {
                    value = row.get(fullFieldName);
                } 
                // 尝试使用简单字段名
                else if (row.containsKey(simpleFieldName)) {
                    value = row.get(simpleFieldName);
                }
                // 尝试使用别名
                else if (alias != null && row.containsKey(alias)) {
                    value = row.get(alias);
                }
                
                // 使用别名作为键，如果别名不存在则使用简单字段名
                String key = alias != null && !alias.isEmpty() ? alias : simpleFieldName;
                // 确保键不为空
                if (key == null || key.isEmpty()) {
                    key = "field_" + field.getId(); // 使用字段ID作为备用键
                }
                finalRow.put(key, value);
            }
            
            finalResults.add(finalRow);
        }
        
        return finalResults;
    }
    
    /**
     * 根据数据源名称获取数据源ID
     * @param dataSourceName 数据源名称
     * @return 数据源ID
     */
    private String getDataSourceIdByName(String dataSourceName) {
        try {
            // 调用报表工作台的getDataSourceByPage接口获取所有数据源
            String url = "http://localhost:39001/jmreport/getDataSourceByPage?pageSize=100&pageNo=1";
            
            // 创建请求实体
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            // 添加认证Token
            if (StpUtil.isLogin()) {
                headers.set("satoken", StpUtil.getTokenInfo().getTokenValue());
            }
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            // 发送GET请求
            ResponseEntity<Map> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                entity, 
                Map.class
            );
            
            // 处理响应
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null && "200".equals(String.valueOf(responseBody.get("code")))) {
                Map<String, Object> result = (Map<String, Object>) responseBody.get("result");
                if (result != null) {
                    List<Map<String, Object>> dataSources = (List<Map<String, Object>>) result.get("records");
                    if (dataSources != null) {
                        for (Map<String, Object> dataSource : dataSources) {
                            if (dataSourceName.equals(dataSource.get("name"))) {
                                return String.valueOf(dataSource.get("id"));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("根据数据源名称获取数据源ID失败: {}", e.getMessage(), e);
        }
        
        return null;
    }

    @Override
    public String getActualDatabaseName(String datasourceName) {
        if (datasourceName == null || datasourceName.trim().isEmpty() || "default".equals(datasourceName.trim())) {
            return datasourceName;
        }
        try {
            String dataSourceId = getDataSourceIdByName(datasourceName);
            if (dataSourceId == null) {
                return datasourceName;
            }
            String url = "http://localhost:39001/jmreport/getDataSourceById?id=" + dataSourceId;
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            if (StpUtil.isLogin()) {
                headers.set("satoken", StpUtil.getTokenInfo().getTokenValue());
            }
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = response.getBody();
            if (body == null || !"200".equals(String.valueOf(body.get("code")))) {
                return datasourceName;
            }
            Map<String, Object> config = (Map<String, Object>) body.get("result");
            if (config == null) {
                return datasourceName;
            }
            String dbUrl = (String) config.get("dbUrl");
            if (dbUrl == null || dbUrl.trim().isEmpty()) {
                return datasourceName;
            }
            // jdbc:mysql://host:port/dbname 或 jdbc:mysql://host:port/dbname?params
            int lastSlash = dbUrl.lastIndexOf('/');
            int q = dbUrl.indexOf('?', lastSlash);
            if (lastSlash >= 0 && lastSlash < dbUrl.length() - 1) {
                String dbName = q > lastSlash ? dbUrl.substring(lastSlash + 1, q).trim() : dbUrl.substring(lastSlash + 1).trim();
                if (!dbName.isEmpty()) {
                    return dbName;
                }
            }
        } catch (Exception e) {
            logger.warn("解析数据源实际库名失败，datasourceName={}，将使用原名称: {}", datasourceName, e.getMessage());
        }
        return datasourceName;
    }

}
