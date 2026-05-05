package com.jeecg.modules.jmreport.controller;

import com.jeecg.modules.jmreport.dto.ChatExtractRequest;
import com.jeecg.modules.jmreport.dto.ExtractedEntity;
import com.jeecg.modules.jmreport.service.ChatExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天记录信息提取接口（调用通义千问提取姓名、电话、地址）
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatExtractionController {

    private final ChatExtractionService chatExtractionService;

    /**
     * 从聊天记录中提取姓名、电话、地址
     *
     * @param request 聊天记录列表
     * @return code、msg、data（List&lt;ExtractedEntity&gt;）
     */
    @PostMapping("/extract")
    public Map<String, Object> extract(@RequestBody ChatExtractRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<ExtractedEntity> entities = chatExtractionService.extract(request);
            result.put("code", 200);
            result.put("msg", "success");
            result.put("data", entities);
        } catch (Exception e) {
            log.error("聊天记录信息提取失败", e);
            result.put("code", 500);
            result.put("msg", "提取失败：" + e.getMessage());
            result.put("data", List.of());
        }
        return result;
    }
}
