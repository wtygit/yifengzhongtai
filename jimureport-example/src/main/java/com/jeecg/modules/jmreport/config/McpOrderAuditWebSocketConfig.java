package com.jeecg.modules.jmreport.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * MCP 订单审核页：STOMP over SockJS，服务端向 /topic 推送新单等事件；客户端可向 /app 发消息（如心跳）。
 */
@Configuration
@EnableWebSocketMessageBroker
public class McpOrderAuditWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/mcp-order-audit")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
