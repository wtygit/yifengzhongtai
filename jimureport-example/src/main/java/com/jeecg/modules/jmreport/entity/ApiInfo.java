package com.jeecg.modules.jmreport.entity;

import java.util.Date;

/**
 * API基本信息
 */
public class ApiInfo {
    private String id;
    private String apiName;
    private String apiPath;
    private String description;
    private String createBy;
    private Date createTime;
    private Date updateTime;
    private Integer status;
    // API类型：table-表类型，sql-SQL类型
    private String apiType;
    // SQL类型API相关字段
    private String sqlContent; // SQL内容
    private String sqlDatabaseName; // SQL使用的数据源名称
    // 分页相关字段
    private Integer needPagination; // 是否需要分页：1-需要，0-不需要
    private Integer pageSize; // 默认每页记录数

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getApiName() {
        return apiName;
    }

    public void setApiName(String apiName) {
        this.apiName = apiName;
    }

    public String getApiPath() {
        return apiPath;
    }

    public void setApiPath(String apiPath) {
        this.apiPath = apiPath;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getNeedPagination() {
        return needPagination;
    }

    public void setNeedPagination(Integer needPagination) {
        this.needPagination = needPagination;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getApiType() {
        return apiType;
    }

    public void setApiType(String apiType) {
        this.apiType = apiType;
    }

    public String getSqlContent() {
        return sqlContent;
    }

    public void setSqlContent(String sqlContent) {
        this.sqlContent = sqlContent;
    }

    public String getSqlDatabaseName() {
        return sqlDatabaseName;
    }

    public void setSqlDatabaseName(String sqlDatabaseName) {
        this.sqlDatabaseName = sqlDatabaseName;
    }
}
