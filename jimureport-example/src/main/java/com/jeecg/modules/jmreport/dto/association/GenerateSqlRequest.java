package com.jeecg.modules.jmreport.dto.association;

import lombok.Data;

@Data
public class GenerateSqlRequest {
    private AssociationConfigDto config;
    /** true: 生成用于预览的SQL（带 schema 前缀，便于主连接跨库查询）；false: 生成用于保存到数据集的SQL（不带 schema 前缀） */
    private Boolean forPreview;
}

