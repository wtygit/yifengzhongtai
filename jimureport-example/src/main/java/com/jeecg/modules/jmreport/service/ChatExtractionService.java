package com.jeecg.modules.jmreport.service;

import com.jeecg.modules.jmreport.dto.ChatExtractRequest;
import com.jeecg.modules.jmreport.dto.ExtractedEntity;

import java.util.List;

/**
 * 聊天记录信息提取服务（调用通义千问提取姓名、电话、地址）
 */
public interface ChatExtractionService {

    /**
     * 从聊天记录中提取姓名、电话、地址
     *
     * @param request 包含 messages 的请求
     * @return 提取出的实体列表
     */
    List<ExtractedEntity> extract(ChatExtractRequest request);
}
