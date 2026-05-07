package com.jeecg.modules.jmreport.dto.association;

import lombok.Data;

@Data
public class PreviewRequest {
    private AssociationConfigDto config;
    private Integer limit;
    /** 若填写则优先使用该 SELECT 做预览（须与「生成 SQL」结果一致或自行保证可执行） */
    private String customSql;
}

