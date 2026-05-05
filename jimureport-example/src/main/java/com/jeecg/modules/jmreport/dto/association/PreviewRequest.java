package com.jeecg.modules.jmreport.dto.association;

import lombok.Data;

@Data
public class PreviewRequest {
    private AssociationConfigDto config;
    private Integer limit;
}

