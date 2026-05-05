package com.jeecg.modules.jmreport.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jeecg.modules.jmreport.service.McpCoreQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * MCP 规范接口：基于 JSON-RPC 2.0，支持 initialize、tools/list、tools/call，
 * 便于客户按标准 MCP 协议接入（握手 → 发现工具 → 调用工具）。
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
public class McpJsonRpcController {

    private static final String JSONRPC = "2.0";
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "haidian-mcp";
    // MCP 对外版本：工具定义/返回结构变化时请递增，便于客户判断是否需要刷新 tools/list
    private static final String SERVER_VERSION = "1.7";

    @Autowired
    private McpCoreQueryService mcpCoreQueryService;

    /**
     * 复用 Spring 全局配置的 ObjectMapper，避免 Java 时间类型等序列化问题
     */
    @Autowired
    private ObjectMapper objectMapper;

        /**
     * MCP 统一 JSON-RPC 2.0 入口（发现地址：{baseUrl}/mcp/rpc）
     * 请求体示例：{"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
     */
    @PostMapping("/rpc")
    public Map<String, Object> handleJsonRpc(@RequestBody Map<String, Object> request) {
        String jsonrpc = (String) request.get("jsonrpc");
        Object id = request.get("id");
        String method = (String) request.get("method");
        Map<String, Object> params = getMap(request, "params");

        if (!"2.0".equals(jsonrpc)) {
            return error(id, -32600, "Invalid Request: jsonrpc must be 2.0");
        }
        if (method == null || method.isEmpty()) {
            return error(id, -32600, "Invalid Request: method is required");
        }

        try {
            switch (method) {
                case "initialize":
                    return response(id, buildInitializeResult(params));
                case "tools/list":
                    return response(id, buildToolsListResult());
                case "tools/call":
                    return response(id, handleToolsCall(params));
                case "initialized":
                    return response(id, Collections.emptyMap());
                default:
                    return error(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            log.error("MCP JSON-RPC 处理异常, method={}", method, e);
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private Map<String, Object> buildInitializeResult(Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", true)));
        result.put("serverInfo", Map.of("name", SERVER_NAME, "version", SERVER_VERSION));
        return result;
    }

    private Map<String, Object> buildToolsListResult() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(toolDef("core_order_query",
                "根据订单号查询海典核心订单表 corecmsorder，返回订单状态、支付、物流和患者信息等详情。",
                Map.of("orderId", "订单号，对应 corecmsorder.orderId"),
                List.of("orderId")));
        tools.add(toolDef("core_insurance_query",
                "根据患者标识（userId、手机号、身份证至少其一）查询患者档案（corecmsusership + corecmsuser），并补充各地区医保 OCR 表记录摘要。",
                Map.of("userId", "海典用户ID（可选）", "mobile", "手机号（可选）", "idCard", "身份证号（可选）"),
                List.of()));
        tools.add(toolDef("core_drug_query",
                "根据药品名称/拼音模糊或条码精准查询海典同步库 t_ware_base 药品基础信息；keyword 与 barCode 至少传一个。",
                Map.of("keyword", "药品名称、拼音码等（模糊）", "barCode", "药品条码（精准）"),
                List.of()));
        tools.add(toolDef("core_visit_strategy_query",
                "回访策略查询（当前占位实现，固定返回暂无数据）。",
                Map.of("businessId", "业务标识，如订单号、患者ID（可选）"),
                List.of()));
        tools.add(orderCreateToolDef());
        tools.add(toolDef("core_order_query_by_group_token",
                "按群分词查询待审核订单（audit_status=0）。groupToken 须为群配置 segment_words 中的完整词之一。",
                Map.of("groupToken", "分词，如「恒瑞」；不可含 | % _ 反斜杠"),
                List.of("groupToken")));
        tools.add(toolDef("core_profile_create",
                "建档：写入 ocrsichuanyibao。仅 11 位手机号即可；无手机号时需姓名 + 身份证。",
                Map.of("name", "用户姓名（可选）", "idCard", "18 位身份证号（可选）", "mobile", "11 位手机号（可选，单独即可）", "phone", "同 mobile"),
                List.of()));
        tools.add(shipmentCreateToolDef());
        return Map.of("tools", tools);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsCall(Map<String, Object> params) {
        String name = (String) params.get("name");
        Map<String, Object> arguments = params.get("arguments") instanceof Map
                ? (Map<String, Object>) params.get("arguments") : new HashMap<>();

        if (name == null || name.isEmpty()) {
            return Map.of("content", List.of(Map.of("type", "text", "text", "{\"code\":400,\"msg\":\"tool name is required\"}")));
        }

        Map<String, Object> bizResult;
        try {
            switch (name) {
                case "core_order_query":
                    bizResult = mcpCoreQueryService.queryOrderByOrderId(getStr(arguments, "orderId"));
                    break;
                case "core_insurance_query":
                    bizResult = mcpCoreQueryService.queryInsurance(
                            getLong(arguments, "userId"),
                            getStr(arguments, "mobile"),
                            getStr(arguments, "idCard"));
                    break;
                case "core_drug_query":
                    bizResult = mcpCoreQueryService.queryDrug(getStr(arguments, "keyword"), getStr(arguments, "barCode"));
                    break;
                case "core_visit_strategy_query":
                    bizResult = mcpCoreQueryService.queryVisitStrategy(getStr(arguments, "businessId"));
                    break;
                case "core_order_create":
                    if (arguments == null) {
                        arguments = new HashMap<>();
                    }
                    boolean structOrder = arguments.containsKey("chatInfo")
                            || arguments.containsKey("items")
                            || hasOrderCreateText(arguments, "patientName", "patient_name", "patientPhone", "patient_phone",
                            "mobile", "phone",
                            "patientIdCard", "patient_id_card", "idCard", "id_card",
                            "patientEducation", "patient_education",
                            "groupName", "group_name", "orderRemark", "order_remark",
                            "userGroupNickname", "user_group_nickname", "groupNickname", "group_nickname",
                            "requestJson", "request_json");
                    if (structOrder) {
                        bizResult = mcpCoreQueryService.createOrder(arguments);
                    } else {
                        bizResult = mcpCoreQueryService.createOrderPlaceholder(getStr(arguments, "requestJson"));
                    }
                    break;
                case "core_order_query_by_group_token":
                    bizResult = mcpCoreQueryService.queryOrdersByGroupToken(getStr(arguments, "groupToken"));
                    break;
                case "core_profile_create": {
                    String mob = getStr(arguments, "mobile");
                    if (!StringUtils.hasText(mob)) {
                        mob = getStr(arguments, "phone");
                    }
                    bizResult = mcpCoreQueryService.createProfile(
                            getStr(arguments, "name"),
                            getStr(arguments, "idCard"),
                            mob);
                    break;
                }
                case "core_shipment_create":
                    bizResult = mcpCoreQueryService.createShipment(arguments);
                    break;
                default:
                    bizResult = Map.of("code", 404, "msg", "unknown tool: " + name, "data", (Object) null);
            }
        } catch (Exception e) {
            log.error("MCP tools/call 执行异常, name={}", name, e);
            bizResult = Map.of("code", 500, "msg", e.getMessage(), "data", (Object) null);
        }

        String text;
        try {
            text = objectMapper.writeValueAsString(bizResult);
        } catch (Exception e) {
            log.error("MCP tools/call 结果序列化异常, name={}, bizResult={}", name, bizResult, e);
            text = "{\"code\":500,\"msg\":\"serialize error\"}";
        }
        return Map.of("content", List.of(Map.of("type", "text", "text", text)));
    }

    private Map<String, Object> toolDef(String name, String description, Map<String, String> propDescs, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : propDescs.entrySet()) {
            properties.put(e.getKey(), Map.of("type", "string", "description", e.getValue()));
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", schema
        );
    }

    /** 下单/发货共用的药品行 JSON Schema properties */
    private static Map<String, Object> drugLineItemSchemaProperties() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("drugName", Map.of("type", "string", "description", "药品名"));
        itemProps.put("name", Map.of("type", "string", "description", "药品名（别名）"));
        itemProps.put("qty", Map.of("type", "integer", "description", "数量"));
        itemProps.put("quantity", Map.of("type", "integer", "description", "数量（别名）"));
        itemProps.put("spec", Map.of("type", "string", "description", "规格"));
        itemProps.put("barCode", Map.of("type", "string", "description", "条码（可选）"));
        itemProps.put("wareId", Map.of("type", "string", "description", "药品ID（可选）"));
        return itemProps;
    }

    /**
     * 下单工具：群信息走 chatInfo；items 与下单接口一致，支持多药品；短时间同手机号重复提交由服务端合并更新（见返回 merged）。
     */
    private Map<String, Object> orderCreateToolDef() {
        Map<String, Object> chatInfoProps = new LinkedHashMap<>();
        chatInfoProps.put("senderName", Map.of("type", "string", "description", "用户在群昵称（可选）"));
        chatInfoProps.put("remark", Map.of("type", "string", "description", "下单备注（可选）"));
        chatInfoProps.put("roomName", Map.of("type", "string", "description", "群名称（可选，与群配置 group_name 一致时可分词检索）"));

        Map<String, Object> itemProps = drugLineItemSchemaProperties();

        Map<String, Object> chatInfoSchema = new LinkedHashMap<>();
        chatInfoSchema.put("type", "object");
        chatInfoSchema.put("description", "群上下文（可选）");
        chatInfoSchema.put("properties", chatInfoProps);

        Map<String, Object> itemsSchema = new LinkedHashMap<>();
        itemsSchema.put("description", "药品列表（可选）；支持 JSON 数组，或字符串 \"[]\"；每项含 drugName/name、qty/quantity、spec、barCode、wareId");
        itemsSchema.put("oneOf", List.of(
                Map.of("type", "string"),
                Map.of("type", "array", "items", Map.of("type", "object", "properties", itemProps))
        ));

        Map<String, Object> schemaProps = new LinkedHashMap<>();
        schemaProps.put("chatInfo", chatInfoSchema);
        schemaProps.put("patientPhone", Map.of("type", "string", "description", "患者手机号（可选）"));
        schemaProps.put("patientName", Map.of("type", "string", "description", "患者姓名（可选）"));
        schemaProps.put("patientIdCard", Map.of("type", "string", "description", "患者身份证号（可选）"));
        schemaProps.put("patientEducation", Map.of("type", "string", "description", "用药教育/嘱托（可选）"));
        schemaProps.put("items", itemsSchema);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", schemaProps);
        return Map.of(
                "name", "core_order_create",
                "description", "下单：chatInfo 传群上下文（可选）；patientPhone/patientName 等可选；items 支持多药品。勿顶层传 groupName/orderRemark（请放 chatInfo）。成功返回 data 含 pendingId；重复提交可能 merged=true。",
                "inputSchema", schema
        );
    }

    /**
     * 发货登记：收件人、电话、地址、邮寄方式必填（可用同义字段）；可选多药品 items（或 drugs、goodsList），与下单 items 字段一致。
     * 成功返回 data：shipmentId、merged、itemCount、message；短时间同手机号待发货单会合并更新。
     */
    private Map<String, Object> shipmentCreateToolDef() {
        Map<String, Object> itemProps = drugLineItemSchemaProperties();
        Map<String, Object> itemsArray = new LinkedHashMap<>();
        itemsArray.put("type", "array");
        itemsArray.put("description", "药品明细（可选，多行）；与 core_order_create 的 items 元素字段一致");
        itemsArray.put("items", Map.of("type", "object", "properties", itemProps));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("recipientName", Map.of("type", "string", "description", "收件人姓名（可与 name 同义）"));
        props.put("name", Map.of("type", "string", "description", "收件人姓名（recipientName 别名）"));
        props.put("recipientPhone", Map.of("type", "string", "description", "电话（可与 phone、mobile 同义）"));
        props.put("phone", Map.of("type", "string", "description", "电话"));
        props.put("mobile", Map.of("type", "string", "description", "手机号"));
        props.put("address", Map.of("type", "string", "description", "收货地址"));
        props.put("shipMethod", Map.of("type", "string", "description", "邮寄方式（可与 mailMethod、mailingMethod 同义）"));
        props.put("mailMethod", Map.of("type", "string", "description", "邮寄方式（别名）"));
        props.put("mailingMethod", Map.of("type", "string", "description", "邮寄方式（别名）"));
        props.put("remark", Map.of("type", "string", "description", "备注（可选）"));
        props.put("items", itemsArray);
        props.put("drugs", Map.of("type", "array", "description", "药品明细（items 的别名）", "items", Map.of("type", "object", "properties", itemProps)));
        props.put("goodsList", Map.of("type", "array", "description", "药品明细（items 的别名）", "items", Map.of("type", "object", "properties", itemProps)));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        // 不设 required：服务端接受 recipientName|name、recipientPhone|phone|mobile、shipMethod|mailMethod 等同义字段
        return Map.of(
                "name", "core_shipment_create",
                "description", "发货登记：写入 mcp_shipment_request_log。须提供收件人姓名、电话、地址、邮寄方式（可用 name/phone/mobile/mailMethod 等同义字段）。可选 items/drugs/goodsList 多药品（字段同下单）。返回 shipmentId、merged、itemCount。",
                "inputSchema", schema
        );
    }

    private static String getStr(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean hasOrderCreateText(Map<String, Object> args, String... keys) {
        if (args == null) {
            return false;
        }
        for (String k : keys) {
            if (StringUtils.hasText(getStr(args, k))) {
                return true;
            }
        }
        return false;
    }

    private static Long getLong(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return (v instanceof Map) ? (Map<String, Object>) v : new HashMap<>();
    }

    private static Map<String, Object> response(Object id, Map<String, Object> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", JSONRPC);
        if (id != null) out.put("id", id);
        out.put("result", result);
        return out;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", JSONRPC);
        if (id != null) out.put("id", id);
        out.put("error", Map.of("code", code, "message", message));
        return out;
    }
}
