package com.jeecg.modules.jmreport.entity;

import java.util.List;

/**
 * API关联的数据库表
 */
public class ApiTable {
    private String id;
    private String apiId;
    private String databaseName;
    private String tableName;
    private String alias;
    private String joinType;
    private String joinCondition;
    
    // 非数据库字段，用于存储表的字段信息
    private List<ApiField> fields;

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

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getJoinType() {
        return joinType;
    }

    public void setJoinType(String joinType) {
        this.joinType = joinType;
    }

    public String getJoinCondition() {
        return joinCondition;
    }

    public void setJoinCondition(String joinCondition) {
        this.joinCondition = joinCondition;
    }

    public List<ApiField> getFields() {
        return fields;
    }

    public void setFields(List<ApiField> fields) {
        this.fields = fields;
    }
}
