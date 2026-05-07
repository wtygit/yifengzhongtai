package com.jeecg.modules.jmreport.dto.association;

import lombok.Data;

@Data
public class SaveAssociationRequest {
    private AssociationConfigDto config;
    /** 保存到大屏数据集的名称（onl_drag_dataset_head.name） */
    private String datasetName;
    /** 数据集父节点ID（可为空，默认挂到“示例数据集”或根节点） */
    private String datasetParentId;
    /** 物化表名（保存到目标数据源中的实际表名） */
    private String tableName;
    /** 若填写则保存该 SELECT 为数据集 SQL（须通过校验）；物化表列从查询元数据推断 */
    private String customSql;
}

