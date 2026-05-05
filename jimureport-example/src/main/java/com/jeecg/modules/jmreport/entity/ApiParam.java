package com.jeecg.modules.jmreport.entity;

/**
 * API的查询参数
 */
public class ApiParam {
    private String id;
    private String apiId;
    private String paramName;
    private String paramType;
    private String dataType;
    private String defaultValue;
    private String description;
    private String validateRule;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getApiId() {
        return apiId;
    }

    public void setApiId(String apiId) {
        this.apiId = apiId;
    }

    public String getParamName() {
        return paramName;
    }

    public void setParamName(String paramName) {
        this.paramName = paramName;
    }

    public String getParamType() {
        return paramType;
    }

    public void setParamType(String paramType) {
        this.paramType = paramType;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getValidateRule() {
        return validateRule;
    }

    public void setValidateRule(String validateRule) {
        this.validateRule = validateRule;
    }
}
