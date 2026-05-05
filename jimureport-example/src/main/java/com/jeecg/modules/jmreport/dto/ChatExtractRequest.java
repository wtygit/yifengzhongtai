package com.jeecg.modules.jmreport.dto;

import lombok.Data;

import java.util.List;

/**
 * 聊天记录信息提取请求
 */
@Data
public class ChatExtractRequest {

    /**
     * 聊天记录列表（每项一条消息）
     */
    private List<String> messages;
}
