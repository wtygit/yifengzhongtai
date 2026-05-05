package com.jeecg.modules.jmreport.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端 → 服务端 STOMP（双向通道）；心跳可订阅 /topic/mcp-order-audit/pong 仅作联调演示。
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class McpOrderAuditStompController {

    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/mcp-order-audit/ping")
    public void ping(@Payload(required = false) Map<String, Object> body) {
        log.debug("MCP 订单审核 WS 客户端 ping: {}", body);
        Map<String, Object> pong = new LinkedHashMap<>();
        pong.put("type", "PONG");
        pong.put("timestamp", System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/mcp-order-audit/pong", pong);
    }
}
