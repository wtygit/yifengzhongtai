package com.jeecg.modules.jmreport.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 新待审核订单等事件推送到订阅了 /topic/mcp-order-audit/pending 的客户端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpOrderAuditRealtimePublisher {

    public static final String TOPIC_PENDING = "/topic/mcp-order-audit/pending";

    private final SimpMessagingTemplate messagingTemplate;

    public void publishNewPendingOrder(String pendingId, String requestSource,
                                       String patientName, String patientPhone,
                                       String groupName, String orderRemark) {
        if (pendingId == null || pendingId.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "NEW_PENDING_ORDER");
        payload.put("pendingId", pendingId);
        payload.put("requestSource", requestSource != null ? requestSource : "");
        payload.put("patientName", patientName != null ? patientName : "");
        payload.put("patientPhone", patientPhone != null ? patientPhone : "");
        payload.put("groupName", groupName != null ? groupName : "");
        payload.put("orderRemark", orderRemark != null ? orderRemark : "");
        payload.put("timestamp", System.currentTimeMillis());
        try {
            messagingTemplate.convertAndSend(TOPIC_PENDING, payload);
        } catch (Exception e) {
            log.warn("WebSocket 推送新订单失败 pendingId={}", pendingId, e);
        }
    }

    public void publishMergedPendingOrder(String pendingId, String requestSource,
                                          String patientName, String patientPhone) {
        if (pendingId == null || pendingId.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "MERGED_PENDING_ORDER");
        payload.put("pendingId", pendingId);
        payload.put("requestSource", requestSource != null ? requestSource : "");
        payload.put("patientName", patientName != null ? patientName : "");
        payload.put("patientPhone", patientPhone != null ? patientPhone : "");
        payload.put("timestamp", System.currentTimeMillis());
        try {
            messagingTemplate.convertAndSend(TOPIC_PENDING, payload);
        } catch (Exception e) {
            log.warn("WebSocket 推送合并订单失败 pendingId={}", pendingId, e);
        }
    }

    /**
     * MCP 发货登记：新单（与 NEW_PENDING_ORDER 区分字段，前端独立弹窗 + new 提示音）
     */
    public void publishNewShipment(String shipmentId, String recipientName, String recipientPhone,
                                   String address, String shipMethod) {
        if (shipmentId == null || shipmentId.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "NEW_SHIPMENT");
        payload.put("shipmentId", shipmentId);
        payload.put("recipientName", recipientName != null ? recipientName : "");
        payload.put("recipientPhone", recipientPhone != null ? recipientPhone : "");
        payload.put("address", address != null ? address : "");
        payload.put("shipMethod", shipMethod != null ? shipMethod : "");
        payload.put("timestamp", System.currentTimeMillis());
        try {
            messagingTemplate.convertAndSend(TOPIC_PENDING, payload);
        } catch (Exception e) {
            log.warn("WebSocket 推送新发货登记失败 shipmentId={}", shipmentId, e);
        }
    }

    /**
     * MCP 发货登记：短时间同手机号重复提交已合并更新（与 MERGED_PENDING_ORDER 一致用 merged 提示音）
     */
    public void publishMergedShipment(String shipmentId, String recipientName, String recipientPhone) {
        if (shipmentId == null || shipmentId.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "MERGED_SHIPMENT");
        payload.put("shipmentId", shipmentId);
        payload.put("recipientName", recipientName != null ? recipientName : "");
        payload.put("recipientPhone", recipientPhone != null ? recipientPhone : "");
        payload.put("timestamp", System.currentTimeMillis());
        try {
            messagingTemplate.convertAndSend(TOPIC_PENDING, payload);
        } catch (Exception e) {
            log.warn("WebSocket 推送合并发货登记失败 shipmentId={}", shipmentId, e);
        }
    }
}
