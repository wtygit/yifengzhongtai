package com.jeecg.modules.jmreport.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 从聊天记录中提取的实体信息（姓名、电话、地址）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedEntity {

    /**
     * 姓名（不存在时为 null）
     */
    private String name;
    /**
     * 电话号码（仅数字，不存在时为 null）
     */
    private String phone;
    /**
     * 地址（尽可能完整，不存在时为 null）
     */
    private String address;
}
