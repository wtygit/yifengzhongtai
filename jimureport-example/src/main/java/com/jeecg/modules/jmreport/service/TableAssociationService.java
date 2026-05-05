package com.jeecg.modules.jmreport.service;

import com.jeecg.modules.jmreport.dto.association.AssociationConfigDto;

import java.util.Map;

public interface TableAssociationService {

    String generateSql(AssociationConfigDto config, boolean forPreview);

    Map<String, Object> validateSql(String sql);

    Map<String, Object> preview(AssociationConfigDto config, Integer limit);

    Map<String, Object> saveAsDataset(AssociationConfigDto config, String datasetName, String datasetParentId, String tableName);

    /**
     * 根据多表关联数据集ID执行查询，始终返回实时数据
     *
     * @param datasetId 多表关联数据集ID（jm_table_association.id / onl_drag_dataset_head.id）
     * @param limit     最大返回行数（可为空，为空时使用默认限制）
     */
    Map<String, Object> queryDataset(String datasetId, Integer limit);

    /**
     * 列出当前系统中已保存的多表关联数据集，用于在各工作台中复用。
     */
    java.util.List<java.util.Map<String, Object>> listDatasets();

    /**
     * 按数据源维度列出多表关联生成的物化表信息，
     * 用于在“数据源详情”中标识哪些表是通过多表关联模块创建的。
     *
     * @param dataSourceId jimu_report_data_source.id
     */
    java.util.List<java.util.Map<String, Object>> listDatasetsByDataSource(String dataSourceId);

    /**
     * 刷新所有多表关联数据集对应的物化表（按天全量重算）。
     */
    void refreshAllMaterializedTables();
}

