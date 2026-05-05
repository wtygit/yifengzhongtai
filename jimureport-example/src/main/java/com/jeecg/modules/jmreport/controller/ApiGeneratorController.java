package com.jeecg.modules.jmreport.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.jeecg.modules.jmreport.entity.ApiInfo;
import com.jeecg.modules.jmreport.entity.ApiTable;
import com.jeecg.modules.jmreport.entity.ApiField;
import com.jeecg.modules.jmreport.entity.ApiParam;
import com.jeecg.modules.jmreport.service.ApiGeneratorService;
import com.jeecg.modules.jmreport.controller.DynamicApiRegistrar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import cn.dev33.satoken.stp.StpUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API生成器Controller
 */
@Slf4j
@Controller
public class ApiGeneratorController {



    /**
     * API列表页
     */
    @GetMapping("/api-generator/list")
    public String list(Model model) {
        // 检查用户是否登录
        if (StpUtil.isLogin()) {
            model.addAttribute("loginUser", StpUtil.getLoginIdAsString());
        } else {
            model.addAttribute("loginUser", "未登录用户");
        }
        return "api-generator/list";
    }

    /**
     * API设计页
     */
    @GetMapping("/api-generator/design")
    public String design(Model model, String apiId) {
        // 检查用户是否登录
        if (StpUtil.isLogin()) {
            model.addAttribute("loginUser", StpUtil.getLoginIdAsString());
        } else {
            model.addAttribute("loginUser", "未登录用户");
        }
        model.addAttribute("apiId", apiId);
        return "api-generator/design";
    }
    
    /**
     * SQL类型API设计页
     */
    @GetMapping("/api-generator/sql-design")
    public String sqlDesign(Model model, String apiId) {
        // 检查用户是否登录
        if (StpUtil.isLogin()) {
            model.addAttribute("loginUser", StpUtil.getLoginIdAsString());
        } else {
            model.addAttribute("loginUser", "未登录用户");
        }
        model.addAttribute("apiId", apiId);
        return "api-generator/sql-design";
    }
    
    // ========================== REST API接口 ==========================
    
    @Autowired
    private ApiGeneratorService apiGeneratorService;
    
    @Autowired
    private DynamicApiRegistrar dynamicApiRegistrar;
    
    /**
     * 获取数据库列表
     */
    @GetMapping("/api-generator/database/list")
    @ResponseBody
    public Map<String, Object> getDatabaseList() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "success");
        result.put("data", apiGeneratorService.getDatabaseList());
        return result;
    }

    /**
     * 获取数据库表列表
     */
    @GetMapping("/api-generator/table/list")
    @ResponseBody
    public Map<String, Object> getTableList() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "success");
        result.put("data", apiGeneratorService.getTableList());
        return result;
    }

    /**
     * 根据数据库名称获取表列表
     */
    @GetMapping("/api-generator/table/list-by-database")
    @ResponseBody
    public Map<String, Object> getTableListByDatabase(@RequestParam String databaseName) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "success");
        result.put("data", apiGeneratorService.getTableListByDatabase(databaseName));
        return result;
    }
    
    /**
     * 获取表的字段信息
     */
    @GetMapping("/api-generator/table/fields")
    @ResponseBody
    public Map<String, Object> getTableFields(@RequestParam String databaseName, @RequestParam String tableName) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "success");
        result.put("data", apiGeneratorService.getTableFields(databaseName, tableName));
        return result;
    }
    
    /**
     * 获取API列表
     */
    @GetMapping("/api-generator/api/list")
    @ResponseBody
    public Map<String, Object> getApiList(
            @RequestParam(required = false) String apiName,
            @RequestParam(required = false) String apiPath) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<ApiInfo> apiList = apiGeneratorService.getApiList(apiName, apiPath);
            log.info("获取API列表，查询条件：apiName={}, apiPath={}, 返回数量：{}", apiName, apiPath, apiList != null ? apiList.size() : 0);
            result.put("code", 200);
            result.put("msg", "success");
            result.put("data", apiList);
        } catch (Exception e) {
            log.error("获取API列表失败", e);
            result.put("code", 500);
            result.put("msg", "获取API列表失败：" + e.getMessage());
            result.put("data", new ArrayList<>());
        }
        return result;
    }
    
    /**
     * 保存API信息（兼容旧接口路径）
     */
    @PostMapping("/api/save")
    @ResponseBody
    public Map<String, Object> saveApiOld(@RequestBody Map<String, Object> data) {
        // 直接调用现有的saveApi方法
        return saveApi(data);
    }
    
    /**
     * 获取API详情
     */
    @GetMapping("/api-generator/api/{apiId}")
    @ResponseBody
    public Map<String, Object> getApiById(@PathVariable String apiId) {
        Map<String, Object> result = new HashMap<>();
        try {
            ApiInfo apiInfo = apiGeneratorService.getApiById(apiId);
            List<ApiTable> apiTables = apiGeneratorService.getApiTablesByApiId(apiId);
            List<ApiField> apiFields = apiGeneratorService.getApiFieldsByApiId(apiId);
            List<ApiParam> apiParams = apiGeneratorService.getApiParamsByApiId(apiId);
            
            Map<String, Object> data = new HashMap<>();
            data.put("apiInfo", apiInfo);
            data.put("apiTables", apiTables);
            data.put("apiFields", apiFields);
            data.put("apiParams", apiParams);
            
            result.put("code", 200);
            result.put("msg", "success");
            result.put("data", data);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "获取API信息失败：" + e.getMessage());
            
            // 返回空的结构，避免前端解析错误
            Map<String, Object> data = new HashMap<>();
            data.put("apiInfo", null);
            data.put("apiTables", null);
            data.put("apiFields", null);
            result.put("data", data);
        }
        return result;
    }
    
    /**
     * 保存API信息
     */
    @PostMapping("/api-generator/api/save")
    @ResponseBody
    public Map<String, Object> saveApi(@RequestBody Map<String, Object> data) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 转换API信息
            Map<String, Object> apiInfoMap = (Map<String, Object>) data.get("apiInfo");
            ApiInfo apiInfo = new ApiInfo();
            apiInfo.setId((String) apiInfoMap.get("id"));
            apiInfo.setApiName((String) apiInfoMap.get("apiName"));
            apiInfo.setApiPath((String) apiInfoMap.get("apiPath"));
            apiInfo.setDescription((String) apiInfoMap.get("description"));
            // 如果前端没有传递createBy字段，使用当前登录用户的信息
            String createBy = (String) apiInfoMap.get("createBy");
            if (createBy == null || createBy.isEmpty()) {
                // 从当前会话中获取登录用户
                if (StpUtil.isLogin()) {
                    createBy = StpUtil.getLoginIdAsString();
                } else {
                    // 如果没有登录用户，使用默认值
                    createBy = "system";
                }
            }
            apiInfo.setCreateBy(createBy);
            // 处理status字段
            Object statusObj = apiInfoMap.get("status");
            int status = 1;
            if (statusObj != null) {
                if (statusObj instanceof Integer) {
                    status = (Integer) statusObj;
                } else if (statusObj instanceof String) {
                    try {
                        status = Integer.parseInt((String) statusObj);
                    } catch (NumberFormatException e) {
                        status = 1; // 转换失败使用默认值
                    }
                }
            }
            apiInfo.setStatus(status);
            
            // 处理apiType字段
            String apiType = (String) apiInfoMap.get("apiType");
            if (apiType == null || apiType.isEmpty()) {
                apiType = "table"; // 默认为表类型
            }
            apiInfo.setApiType(apiType);
            
            // 处理SQL相关字段
            // SQL类型API：sqlContent和sqlDatabaseName都是必需的
            // 表类型API：sqlContent可选（用于保存参考SQL），sqlDatabaseName不需要
            if ("sql".equals(apiType)) {
                apiInfo.setSqlContent((String) apiInfoMap.get("sqlContent"));
                apiInfo.setSqlDatabaseName((String) apiInfoMap.get("sqlDatabaseName"));
            } else {
                // 表类型API也可以保存SQL内容（作为参考文档）
                apiInfo.setSqlContent((String) apiInfoMap.get("sqlContent"));
                apiInfo.setSqlDatabaseName(null); // 表类型API不需要sqlDatabaseName
            }
            
            // 处理needPagination字段
            Object needPaginationObj = apiInfoMap.get("needPagination");
            int needPagination = 0;
            if (needPaginationObj != null) {
                if (needPaginationObj instanceof Integer) {
                    needPagination = (Integer) needPaginationObj;
                } else if (needPaginationObj instanceof String) {
                    try {
                        needPagination = Integer.parseInt((String) needPaginationObj);
                    } catch (NumberFormatException e) {
                        needPagination = 0; // 转换失败使用默认值
                    }
                } else if (needPaginationObj instanceof Boolean) {
                    needPagination = (Boolean) needPaginationObj ? 1 : 0;
                }
            }
            apiInfo.setNeedPagination(needPagination);
            
            // 处理pageSize字段
            Object pageSizeObj = apiInfoMap.get("pageSize");
            int pageSize = 20;
            if (pageSizeObj != null) {
                if (pageSizeObj instanceof Integer) {
                    pageSize = (Integer) pageSizeObj;
                } else if (pageSizeObj instanceof String) {
                    try {
                        pageSize = Integer.parseInt((String) pageSizeObj);
                    } catch (NumberFormatException e) {
                        pageSize = 20; // 转换失败使用默认值
                    }
                }
            }
            apiInfo.setPageSize(pageSize);
            
            // 转换API表信息
            List<Map<String, Object>> apiTablesMap = (List<Map<String, Object>>) data.get("apiTables");
            List<ApiTable> apiTables = new ArrayList<>();
            if (apiTablesMap != null && !apiTablesMap.isEmpty()) {
                for (Map<String, Object> tableMap : apiTablesMap) {
                    ApiTable apiTable = new ApiTable();
                    apiTable.setId((String) tableMap.get("id"));
                    apiTable.setDatabaseName((String) tableMap.get("databaseName"));
                    apiTable.setTableName((String) tableMap.get("tableName"));
                    apiTable.setAlias((String) tableMap.get("alias"));
                    apiTable.setJoinType((String) tableMap.get("joinType"));
                    apiTable.setJoinCondition((String) tableMap.get("joinCondition"));
                    apiTables.add(apiTable);
                }
            }
            
            // 转换API字段信息
            List<Map<String, Object>> apiFieldsMap = (List<Map<String, Object>>) data.get("apiFields");
            List<ApiField> apiFields = new ArrayList<>();
            if (apiFieldsMap != null && !apiFieldsMap.isEmpty()) {
                for (Map<String, Object> fieldMap : apiFieldsMap) {
                    ApiField apiField = new ApiField();
                    apiField.setId((String) fieldMap.get("id"));
                    apiField.setDatabaseName((String) fieldMap.get("databaseName"));
                    apiField.setTableName((String) fieldMap.get("tableName"));
                    apiField.setFieldName((String) fieldMap.get("fieldName"));
                    apiField.setAlias((String) fieldMap.get("alias"));
                    apiField.setDataType((String) fieldMap.get("dataType"));
                    // 处理isPrimary字段
                    Object isPrimaryObj = fieldMap.get("isPrimary");
                    int isPrimary = 0;
                    if (isPrimaryObj != null) {
                        if (isPrimaryObj instanceof Integer) {
                            isPrimary = (Integer) isPrimaryObj;
                        } else if (isPrimaryObj instanceof String) {
                            try {
                                isPrimary = Integer.parseInt((String) isPrimaryObj);
                            } catch (NumberFormatException e) {
                                isPrimary = 0; // 转换失败使用默认值
                            }
                        } else if (isPrimaryObj instanceof Boolean) {
                            isPrimary = (Boolean) isPrimaryObj ? 1 : 0;
                        }
                    }
                    apiField.setIsPrimary(isPrimary);
                    
                    // 处理isRequired字段
                    Object isRequiredObj = fieldMap.get("isRequired");
                    int isRequired = 0;
                    if (isRequiredObj != null) {
                        if (isRequiredObj instanceof Integer) {
                            isRequired = (Integer) isRequiredObj;
                        } else if (isRequiredObj instanceof String) {
                            try {
                                isRequired = Integer.parseInt((String) isRequiredObj);
                            } catch (NumberFormatException e) {
                                isRequired = 0; // 转换失败使用默认值
                            }
                        } else if (isRequiredObj instanceof Boolean) {
                            isRequired = (Boolean) isRequiredObj ? 1 : 0;
                        }
                    }
                    apiField.setIsRequired(isRequired);
                    apiField.setDescription((String) fieldMap.get("description"));
                    apiFields.add(apiField);
                }
            }
            
            // 转换API参数信息
            List<Map<String, Object>> apiParamsMap = (List<Map<String, Object>>) data.get("apiParams");
            List<ApiParam> apiParams = new ArrayList<>();
            if (apiParamsMap != null && !apiParamsMap.isEmpty()) {
                for (Map<String, Object> paramMap : apiParamsMap) {
                    ApiParam apiParam = new ApiParam();
                    apiParam.setId((String) paramMap.get("id"));
                    apiParam.setParamName((String) paramMap.get("paramName"));
                    apiParam.setParamType((String) paramMap.get("paramType"));
                    if (apiParam.getParamType() == null || apiParam.getParamType().isEmpty()) {
                        apiParam.setParamType("query"); // 默认为query参数
                    }
                    apiParam.setDataType((String) paramMap.get("dataType"));
                    if (apiParam.getDataType() == null || apiParam.getDataType().isEmpty()) {
                        apiParam.setDataType("String"); // 默认为String类型
                    }
                    apiParam.setDefaultValue((String) paramMap.get("defaultValue"));
                    apiParam.setDescription((String) paramMap.get("description"));
                    apiParam.setValidateRule((String) paramMap.get("validateRule"));
                    apiParams.add(apiParam);
                }
            }
            
            // 如果是更新操作，先获取旧的API信息，用于注销旧路径映射
            String oldApiPath = null;
            if (apiInfo.getId() != null && !apiInfo.getId().isEmpty()) {
                try {
                    ApiInfo oldApiInfo = apiGeneratorService.getApiById(apiInfo.getId());
                    if (oldApiInfo != null) {
                        oldApiPath = oldApiInfo.getApiPath();
                    }
                } catch (Exception e) {
                    log.warn("获取旧API信息失败，apiId: {}", apiInfo.getId(), e);
                }
            }
            
            // 保存API信息
            log.info("开始保存API，apiName={}, apiPath={}, apiType={}, oldApiPath={}", 
                apiInfo.getApiName(), apiInfo.getApiPath(), apiInfo.getApiType(), oldApiPath);
            Map<String, Object> saveResult = apiGeneratorService.saveApi(apiInfo, apiTables, apiFields, apiParams);
            result.put("code", saveResult.get("code"));
            result.put("msg", saveResult.get("msg"));
            
            if ((int) saveResult.get("code") == 200) {
                log.info("API保存成功，id={}, apiName={}, apiPath={}, apiType={}", 
                    apiInfo.getId(), apiInfo.getApiName(), apiInfo.getApiPath(), apiInfo.getApiType());
                
                // 如果是更新操作且路径改变了，注销旧路径映射
                if (oldApiPath != null && !oldApiPath.equals(apiInfo.getApiPath())) {
                    try {
                        dynamicApiRegistrar.unregisterApi(oldApiPath);
                        log.info("API路径已更改，注销旧路径映射：{}", oldApiPath);
                    } catch (Exception e) {
                        log.warn("注销旧路径映射失败：{}", oldApiPath, e);
                        // 注销失败不影响保存结果
                    }
                }
                
                // 如果保存成功且API是启用状态，立即注册API
                if (apiInfo.getStatus() == 1) {
                    try {
                        dynamicApiRegistrar.registerApi(apiInfo);
                        log.info("API保存后立即注册成功：{}", apiInfo.getApiPath());
                    } catch (Exception e) {
                        log.error("API保存后注册失败：{}", apiInfo.getApiPath(), e);
                        // 注册失败不影响保存结果
                    }
                } else {
                    // 如果API被禁用，注销映射
                    try {
                        dynamicApiRegistrar.unregisterApi(apiInfo.getApiPath());
                        log.info("API已禁用，注销映射：{}", apiInfo.getApiPath());
                    } catch (Exception e) {
                        log.warn("注销已禁用API映射失败：{}", apiInfo.getApiPath(), e);
                    }
                }
            } else {
                log.error("API保存失败，apiName={}, apiPath={}, 错误信息：{}", 
                    apiInfo.getApiName(), apiInfo.getApiPath(), saveResult.get("msg"));
            }
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "保存失败：" + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }
    
    /**
     * 删除API
     */
    @DeleteMapping("/api-generator/api/delete/{apiId}")
    @ResponseBody
    public Map<String, Object> deleteApi(@PathVariable String apiId) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 先获取API信息，用于注销映射
            ApiInfo apiInfo = apiGeneratorService.getApiById(apiId);
            
            // 删除API
            boolean deleteResult = apiGeneratorService.deleteApi(apiId);
            
            if (deleteResult) {
                // 删除成功后，注销API映射
                if (apiInfo != null && apiInfo.getApiPath() != null) {
                    try {
                        dynamicApiRegistrar.unregisterApi(apiInfo.getApiPath());
                        log.info("API删除后注销映射成功：{}", apiInfo.getApiPath());
                    } catch (Exception e) {
                        log.warn("API删除后注销映射失败：{}", apiInfo.getApiPath(), e);
                        // 注销失败不影响删除结果
                    }
                }
                result.put("code", 200);
                result.put("msg", "删除成功");
            } else {
                result.put("code", 500);
                result.put("msg", "删除失败");
            }
        } catch (Exception e) {
            log.error("删除API失败：{}", apiId, e);
            result.put("code", 500);
            result.put("msg", "删除失败：" + e.getMessage());
        }
        return result;
    }
    
    /**
     * 执行SQL查询
     */
    @PostMapping("/api-generator/query/execute")
    @ResponseBody
    public List<Map<String, Object>> executeQuery(@RequestBody Map<String, String> data) {
        String sql = data.get("sql");
        return apiGeneratorService.executeQuery(sql);
    }

    /**
     * 在指定数据源上执行SQL（查询/增删改/DDL）
     * 入参：{ databaseName: "数据源名称", sql: "要执行的SQL" }
     */
    @PostMapping("/api-generator/sql/execute")
    @ResponseBody
    public Map<String, Object> executeSql(@RequestBody Map<String, String> data) {
        // API生成器属于管理操作，要求已登录
        StpUtil.checkLogin();
        String databaseName = data.get("databaseName");
        String sql = data.get("sql");
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> exec = apiGeneratorService.executeSql(databaseName, sql);
            result.put("code", 200);
            result.put("msg", "success");
            result.put("result", exec);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", e.getMessage());
        }
        return result;
    }
    
    /**
     * 获取API列表（用于调试）
     */
    @GetMapping("/api-generator/test/list")
    @ResponseBody
    public Map<String, Object> getApiListForTest() {
        Map<String, Object> result = new HashMap<>();
        List<ApiInfo> apiList = apiGeneratorService.getApiList();
        result.put("code", 200);
        result.put("data", apiList);
        result.put("count", apiList.size());
        return result;
    }
}