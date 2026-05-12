package com.jeecg.modules.jmreport.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.jeecg.modules.jmreport.config.McpOauthProperties;
import com.jeecg.modules.jmreport.service.McpCoreQueryService;
import com.jeecg.modules.jmreport.service.McpOauthTokenService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 核心查询接口 Controller
 *
 * 对应工具：
 * - core_order_query
 * - core_insurance_query
 * - core_drug_query
 * - core_visit_strategy_query
 * - core_order_create
 * - core_profile_create（建档）
 * - core_shipment_create（发货登记）
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
public class McpCoreQueryController {

    @Autowired
    private McpCoreQueryService mcpCoreQueryService;
    @Autowired
    private McpOauthProperties oauthProperties;
    @Autowired
    private McpOauthTokenService mcpOauthTokenService;

    @Value("${jeecg.path.upload:/opt/upload}")
    private String jeecgUploadRoot;

    /** 与 spring.servlet.multipart.max-file-size 对齐（略小于配置即可），用于返回明确错误 */
    private static final long ORDER_AUDIT_SCREENSHOT_MAX_BYTES = 20L * 1024 * 1024;

    /**
     * 订单查询：core_order_query
     */
    @PostMapping("/core_order_query")
    public Map<String, Object> coreOrderQuery(@RequestBody CoreOrderQueryRequest request) {
        log.info("MCP core_order_query 请求，orderId={}", request != null ? request.getOrderId() : null);
        return mcpCoreQueryService.queryOrderByOrderId(request != null ? request.getOrderId() : null);
    }

    /**
     * 医保/患者档案查询：core_insurance_query
     */
    @PostMapping("/core_insurance_query")
    public Map<String, Object> coreInsuranceQuery(@RequestBody CoreInsuranceQueryRequest request) {
        Long userId = request != null ? request.getUserId() : null;
        String mobile = request != null ? request.getMobile() : null;
        String idCard = request != null ? request.getIdCard() : null;
        log.info("MCP core_insurance_query 请求，userId={}, mobile={}, idCard={}", userId, mobile, idCard);
        return mcpCoreQueryService.queryInsurance(userId, mobile, idCard);
    }

    /**
     * 药品查询：core_drug_query
     */
    @PostMapping("/core_drug_query")
    public Map<String, Object> coreDrugQuery(@RequestBody CoreDrugQueryRequest request) {
        String keyword = request != null ? request.getKeyword() : null;
        String barCode = request != null ? request.getBarCode() : null;
        log.info("MCP core_drug_query 请求，keyword={}, barCode={}", keyword, barCode);
        return mcpCoreQueryService.queryDrug(keyword, barCode);
    }

    /**
     * 回访策略查询：core_visit_strategy_query
     */
    @PostMapping("/core_visit_strategy_query")
    public Map<String, Object> coreVisitStrategyQuery(@RequestBody(required = false) CoreVisitStrategyRequest request) {
        String businessId = request != null ? request.getBusinessId() : null;
        log.info("MCP core_visit_strategy_query 请求，businessId={}", businessId);
        return mcpCoreQueryService.queryVisitStrategy(businessId);
    }

    /**
     * 获取 MCP OAuth access_token（MCP 风格直连端点）。
     * 说明：该端点不需要 Bearer token，供客户端先换取 access_token。
     */
    @PostMapping("/core_oauth_token")
    public Map<String, Object> coreOauthToken(
            @RequestBody(required = false) CoreOauthTokenRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        if (!oauthProperties.isEnabled()) {
            return Map.of("error", "temporarily_disabled", "error_description", "OAuth is disabled");
        }
        String grantType = request != null ? request.getGrantType() : null;
        String clientId = request != null ? request.getClientId() : null;
        String clientSecret = request != null ? request.getClientSecret() : null;
        String scope = request != null ? request.getScope() : null;
        if (!"client_credentials".equals(grantType)) {
            return Map.of("error", "unsupported_grant_type", "error_description", "grant_type must be client_credentials");
        }
        String[] creds = parseBasicAuth(authorization);
        if (!StringUtils.hasText(clientId) && creds != null) {
            clientId = creds[0];
        }
        if (!StringUtils.hasText(clientSecret) && creds != null) {
            clientSecret = creds[1];
        }
        if (!oauthProperties.getClientId().equals(clientId) || !oauthProperties.getClientSecret().equals(clientSecret)) {
            return Map.of("error", "invalid_client", "error_description", "client authentication failed");
        }
        String actualScope = StringUtils.hasText(scope) ? scope : oauthProperties.getScope();
        String accessToken = mcpOauthTokenService.issueAccessToken(clientId, actualScope);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("access_token", accessToken);
        data.put("token_type", "Bearer");
        data.put("expires_in", oauthProperties.getAccessTokenTtlSeconds());
        data.put("scope", actualScope);
        return data;
    }

    private static String[] parseBasicAuth(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Basic ")) {
            return null;
        }
        try {
            String b64 = authorization.substring(6).trim();
            String plain = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            int idx = plain.indexOf(':');
            if (idx <= 0) {
                return null;
            }
            return new String[]{plain.substring(0, idx), plain.substring(idx + 1)};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 下单占位接口：core_order_create
     * 支持仅患者信息、无药品 items（落库待审核）；真正提交中台须在审核页补充药品后再「下单」。
     */
    @PostMapping("/core_order_create")
    public Map<String, Object> coreOrderCreate(@RequestBody CoreOrderCreateRequest request) {
        if (request == null) {
            return mcpCoreQueryService.createOrderPlaceholder(null);
        }
        // chatInfo 与顶层 groupName 等互斥：服务层只接受 chatInfo 结构，切勿在此处把 roomName 摊平成 groupName，
        // 否则 validateChatInfoOnlyPayload 会误报「请移除字段 groupName」。
        boolean structured = request.getItems() != null
                || StringUtils.hasText(request.getPatientName())
                || StringUtils.hasText(request.getPatientPhone())
                || StringUtils.hasText(request.getPatientIdCard())
                || StringUtils.hasText(request.getPatientEducation())
                || request.getChatInfo() != null
                || StringUtils.hasText(request.getGroupName())
                || StringUtils.hasText(request.getOrderRemark())
                || StringUtils.hasText(request.getUserGroupNickname())
                || StringUtils.hasText(request.getY3ImageInfo())
                || StringUtils.hasText(request.getDeliveryHospital())
                || StringUtils.hasText(request.getOrderCreateSource())
                || StringUtils.hasText(request.getRequestJson());
        if (structured) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("requestTriggerType", request.getRequestTriggerType());
            map.put("patientName", request.getPatientName());
            map.put("patientPhone", request.getPatientPhone());
            map.put("patientIdCard", request.getPatientIdCard());
            map.put("patientEducation", request.getPatientEducation());
            if (StringUtils.hasText(request.getDeliveryHospital())) {
                map.put("deliveryHospital", request.getDeliveryHospital());
            }
            if (StringUtils.hasText(request.getOrderCreateSource())) {
                map.put("orderCreateSource", request.getOrderCreateSource());
            }
            if (StringUtils.hasText(request.getGroupName())) {
                map.put("groupName", request.getGroupName());
            }
            if (StringUtils.hasText(request.getOrderRemark())) {
                map.put("orderRemark", request.getOrderRemark());
            }
            if (StringUtils.hasText(request.getUserGroupNickname())) {
                map.put("userGroupNickname", request.getUserGroupNickname());
            }
            if (request.getChatInfo() != null) {
                ChatInfo ci = request.getChatInfo();
                java.util.Map<String, Object> ciMap = new java.util.LinkedHashMap<>();
                ciMap.put("roomName", ci.getRoomName());
                ciMap.put("senderName", ci.getSenderName());
                ciMap.put("nickName", ci.getNickName());
                ciMap.put("remark", ci.getRemark());
                ciMap.put("storeId", ci.getStoreId());
                map.put("chatInfo", ciMap);
            }
            if (StringUtils.hasText(request.getY3ImageInfo())) {
                map.put("y3ImageInfo", request.getY3ImageInfo());
            }
            map.put("items", request.getItems() != null ? request.getItems() : java.util.List.of());
            map.put("requestJson", request.getRequestJson());
            log.info("MCP core_order_create 结构化请求，patientPhone={}", request.getPatientPhone());
            return mcpCoreQueryService.createOrder(map);
        }
        String requestJson = request.getRequestJson();
        log.info("MCP core_order_create 请求，requestJson={}", requestJson);
        return mcpCoreQueryService.createOrderPlaceholder(requestJson);
    }

    /**
     * 建档接口：core_profile_create（帮我建档）
     */
    @PostMapping("/core_profile_create")
    public Map<String, Object> coreProfileCreate(@RequestBody CoreProfileCreateRequest request) {
        String name = request != null ? request.getName() : null;
        String idCard = request != null ? request.getIdCard() : null;
        String mobile = request != null ? request.getMobile() : null;
        log.info("MCP core_profile_create 请求，name={}, idCard={}, mobile={}", name, idCard, mobile);
        return mcpCoreQueryService.createProfile(name, idCard, mobile);
    }

    /**
     * 小程序回调更新订单状态（更新海典同步库表 mcp_order_create_order_log）。
     */
    @PostMapping("/core_order_status_update")
    public Map<String, Object> coreOrderStatusUpdate(@RequestBody CoreOrderStatusUpdateRequest request) {
        String orderId = request != null ? request.getOrderId() : null;
        String pendingId = request != null ? request.getPendingId() : null;
        Integer statusCode = request != null ? request.getStatusCode() : null;
        String status = request != null ? request.getStatus() : null;
        String invoiceInfo = request != null ? request.getInvoiceInfo() : null;
        String callbackData = request != null ? request.getCallbackData() : null;
        String receiverName = request != null ? request.getReceiverName() : null;
        String completionImagesJson = request != null ? request.getCompletionImagesJson() : null;
        log.info("MCP core_order_status_update 请求，orderId={}, pendingId={}, statusCode={}, status={}", orderId, pendingId, statusCode, status);
        return mcpCoreQueryService.updateOrderStatus(
                orderId, pendingId, statusCode, status, invoiceInfo, callbackData, receiverName, completionImagesJson);
    }

    /**
     * 审核通过：从表A读取数据，调用中台接口，写入表B，更新表A状态
     */
    @PostMapping("/core_order_approve")
    public Map<String, Object> coreOrderApprove(@RequestBody CoreOrderAuditRequest request) {
        String pendingId = request != null ? request.getPendingId() : null;
        String auditRemark = request != null ? request.getAuditRemark() : null;
        log.info("MCP core_order_approve 请求，pendingId={}", pendingId);
        return mcpCoreQueryService.approveOrder(pendingId, auditRemark);
    }

    /**
     * 审核驳回：更新表A状态为已驳回
     */
    @PostMapping("/core_order_reject")
    public Map<String, Object> coreOrderReject(@RequestBody CoreOrderAuditRequest request) {
        String pendingId = request != null ? request.getPendingId() : null;
        String auditRemark = request != null ? request.getAuditRemark() : null;
        log.info("MCP core_order_reject 请求，pendingId={}, auditRemark={}", pendingId, auditRemark);
        return mcpCoreQueryService.rejectOrder(pendingId, auditRemark);
    }

    /**
     * 获取订单审核列表（供前端页面使用）
     */
    @GetMapping("/order-audit-list")
    public Map<String, Object> getOrderAuditList(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String groupToken,
            @RequestParam(required = false) String pendingId,
            @RequestParam(required = false) String patientName,
            @RequestParam(required = false) String patientPhone,
            @RequestParam(required = false) String patientIdCard,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String createDateStart,
            @RequestParam(required = false) String createDateEnd,
            @RequestParam(required = false) String createTimeStart,
            @RequestParam(required = false) String createTimeEnd,
            @RequestParam(required = false) String requestTriggerType) {
        log.info("MCP order-audit-list 请求，status={}, groupToken={}, pendingId={}, patientName={}, patientPhone={}, patientIdCard={}, groupName={}, storeId={}, createDateStart={}, createDateEnd={}, createTimeStart={}, createTimeEnd={}, requestTriggerType={}",
                status, groupToken, pendingId, patientName, patientPhone, patientIdCard, groupName, storeId, createDateStart, createDateEnd, createTimeStart, createTimeEnd, requestTriggerType);
        return mcpCoreQueryService.getOrderAuditList(status, groupToken, pendingId, patientName, patientPhone, patientIdCard, groupName, storeId,
                createDateStart, createDateEnd, createTimeStart, createTimeEnd, requestTriggerType);
    }

    /**
     * 按群分词查询待审核订单（audit_status=0），groupToken 为配置表中的某一词，如「恒瑞」
     */
    @GetMapping("/order-query-by-group-token")
    public Map<String, Object> orderQueryByGroupToken(@RequestParam String groupToken) {
        return mcpCoreQueryService.queryOrdersByGroupToken(groupToken);
    }

    @GetMapping("/chat-group-config/list")
    public Map<String, Object> listChatGroupConfigs() {
        return mcpCoreQueryService.listChatGroupConfigs();
    }

    @GetMapping("/group-token-options")
    public Map<String, Object> listGroupTokenOptions() {
        return mcpCoreQueryService.listGroupTokenOptions();
    }

    /**
     * 审核页患教下拉：corecmsuser.userType in ('患教', '销售&患教')
     */
    @GetMapping("/patient-education-options")
    public Map<String, Object> listPatientEducationOptions() {
        return mcpCoreQueryService.listPatientEducationOptions();
    }

    /**
     * 海典库 hospitallist：按关键字模糊查医院名称（keyword 可空，返回前 100 条）
     */
    @GetMapping("/hospital-list")
    public Map<String, Object> searchHospitalList(@RequestParam(required = false) String keyword) {
        return mcpCoreQueryService.searchHospitalList(keyword);
    }

    /**
     * 小程序门店列表：优先本地 jm_mcp_store_login（由海典 corecmsstore 定时同步），否则直连海典；登录页 datalist 与 MCP 审核共用，无需鉴权。
     */
    @GetMapping("/mini-program-stores")
    public Map<String, Object> listMiniProgramStores() {
        return mcpCoreQueryService.listMiniProgramStores();
    }

    @PostMapping("/chat-group-config/save")
    public Map<String, Object> saveChatGroupConfig(@RequestBody java.util.Map<String, Object> body) {
        return mcpCoreQueryService.saveChatGroupConfig(body);
    }

    @DeleteMapping("/chat-group-config/{id}")
    public Map<String, Object> deleteChatGroupConfig(@PathVariable Long id) {
        return mcpCoreQueryService.deleteChatGroupConfig(id);
    }

    /**
     * 更新订单数据（保存用户修改的参数）
     */
    @PostMapping("/order-audit-update")
    public Map<String, Object> updateOrderData(@RequestBody Map<String, Object> request) {
        String pendingId = request != null ? (String) request.get("pendingId") : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> userRequestData = request != null ? (Map<String, Object>) request.get("userRequestData") : null;
        log.info("MCP order-audit-update 请求，pendingId={}", pendingId);
        return mcpCoreQueryService.updateOrderRequestData(pendingId, userRequestData);
    }

    /**
     * 审核页「聊天截图」本地上传：保存到 jeecg.path.upload/mcp-chat-screenshot，返回可访问 URL（同域 /mcp/uploaded/screenshots/…）。
     */
    @PostMapping(value = "/order-audit-upload-screenshot", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadOrderAuditScreenshot(@RequestPart("file") MultipartFile file) {
        Map<String, Object> err = new LinkedHashMap<>();
        if (file == null || file.isEmpty()) {
            err.put("code", 400);
            err.put("msg", "请选择图片文件");
            err.put("data", null);
            return err;
        }
        if (file.getSize() > ORDER_AUDIT_SCREENSHOT_MAX_BYTES) {
            err.put("code", 400);
            err.put("msg", "单张图片不能超过 20MB");
            err.put("data", null);
            return err;
        }
        String ct = file.getContentType() != null ? file.getContentType().toLowerCase(Locale.ROOT) : "";
        String ext = extFromImageContentType(ct);
        if (ext == null) {
            err.put("code", 400);
            err.put("msg", "仅支持 JPG、PNG、GIF、WebP 图片");
            err.put("data", null);
            return err;
        }
        String safeName = UUID.randomUUID().toString().replace("-", "") + ext;
        try {
            Path dir = Paths.get(jeecgUploadRoot, "mcp-chat-screenshot").toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path target = dir.resolve(safeName);
            file.transferTo(target.toFile());
            String urlPath = "/mcp/uploaded/screenshots/" + safeName;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", urlPath);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("code", 0);
            ok.put("msg", "ok");
            ok.put("data", data);
            log.info("MCP order-audit-upload-screenshot 成功，size={}, name={}", file.getSize(), safeName);
            return ok;
        } catch (Exception e) {
            log.warn("MCP order-audit-upload-screenshot 失败", e);
            err.put("code", 500);
            err.put("msg", "保存文件失败：" + e.getMessage());
            err.put("data", null);
            return err;
        }
    }

    private static String extFromImageContentType(String contentType) {
        if (contentType.startsWith("image/png")) {
            return ".png";
        }
        if (contentType.startsWith("image/gif")) {
            return ".gif";
        }
        if (contentType.startsWith("image/webp")) {
            return ".webp";
        }
        if (contentType.startsWith("image/jpeg") || contentType.startsWith("image/jpg")) {
            return ".jpg";
        }
        return null;
    }

    /**
     * 发货登记：客户推送姓名、地址、电话、邮寄方式（必填），与海典库 mcp_shipment_request_log 落库逻辑同下单接口风格。
     */
    @PostMapping("/core_shipment_create")
    public Map<String, Object> coreShipmentCreate(@RequestBody Map<String, Object> body) {
        log.info("MCP core_shipment_create 请求");
        return mcpCoreQueryService.createShipment(body);
    }

    @GetMapping("/shipment-audit-list")
    public Map<String, Object> shipmentAuditList(
            @RequestParam(required = false) String shipStatus,
            @RequestParam(required = false) String shipmentId,
            @RequestParam(required = false) String recipientPhone,
            @RequestParam(required = false) String nameKeyword,
            @RequestParam(required = false) String createDateStart,
            @RequestParam(required = false) String createDateEnd) {
        return mcpCoreQueryService.getShipmentAuditList(shipStatus, shipmentId, recipientPhone, nameKeyword,
                createDateStart, createDateEnd);
    }

    @PostMapping("/shipment-audit-update")
    public Map<String, Object> shipmentAuditUpdate(@RequestBody Map<String, Object> request) {
        String shipmentId = request != null ? (String) request.get("shipmentId") : null;
        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        if (request != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) request.get("fields");
            if (nested != null && !nested.isEmpty()) {
                fields.putAll(nested);
            } else {
                fields.putAll(request);
                fields.remove("shipmentId");
            }
        }
        log.info("MCP shipment-audit-update 请求，shipmentId={}", shipmentId);
        return mcpCoreQueryService.updateShipmentAuditData(shipmentId, fields);
    }

    @PostMapping("/shipment-audit-ship")
    public Map<String, Object> shipmentAuditShip(@RequestBody Map<String, Object> request) {
        String shipmentId = request != null ? (String) request.get("shipmentId") : null;
        log.info("MCP shipment-audit-ship 请求，shipmentId={}", shipmentId);
        return mcpCoreQueryService.markShipmentShipped(shipmentId);
    }

    /**
     * 库存信息列表（海典库）
     */
    @GetMapping("/stock-info-list")
    public Map<String, Object> stockInfoList(
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "30") Integer limit,
            @RequestParam(required = false) String wareName,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String produceBatchNo,
            @RequestParam(required = false) String productEntName,
            @RequestParam(required = false) String approvalNo,
            @RequestParam(required = false) String packageSpec,
            @RequestParam(required = false, defaultValue = "0") Integer minCode,
            @RequestParam(required = false, defaultValue = "0") Integer maxCode) {
        return mcpCoreQueryService.getStockInfoList(page, limit, wareName, storeId, produceBatchNo,
                productEntName, approvalNo, packageSpec, minCode, maxCode);
    }

    /**
     * 手动触发库存快照同步到海典库 stock_info_list（立即执行一次）。
     */
    @PostMapping("/stock-info-sync")
    public Map<String, Object> stockInfoSync() {
        log.info("MCP stock-info-sync 手动触发库存同步");
        return mcpCoreQueryService.syncStockInfoListDaily();
    }

    /**
     * core_order_query 请求体
     */
    @Data
    public static class CoreOrderQueryRequest {
        private String orderId;
    }

    /**
     * core_insurance_query 请求体
     */
    @Data
    public static class CoreInsuranceQueryRequest {
        private Long userId;
        private String mobile;
        private String idCard;
    }

    /**
     * core_drug_query 请求体
     */
    @Data
    public static class CoreDrugQueryRequest {
        /**
         * 关键字（药品名称 / 拼音码等，支持模糊匹配）
         */
        private String keyword;
        /**
         * 条码（精准匹配）
         */
        private String barCode;
    }

    /**
     * core_visit_strategy_query 请求体
     */
    @Data
    public static class CoreVisitStrategyRequest {
        /**
         * 业务标识，例如订单号、患者ID 等（可选）
         */
        private String businessId;
    }

    @Data
    public static class CoreOauthTokenRequest {
        @JsonAlias("grant_type")
        private String grantType;
        @JsonAlias("client_id")
        private String clientId;
        @JsonAlias("client_secret")
        private String clientSecret;
        private String scope;
    }

    /**
     * core_order_create 请求体
     */
    @Data
    public static class CoreOrderCreateRequest {
        /**
         * 业务侧下单请求 JSON 字符串（原样回传）
         */
        @JsonAlias({"request_json"})
        private String requestJson;

        // 结构化入参（推荐）；直连 JSON 常见 snake_case，需别名否则绑定不到、落库患者为空
        @JsonAlias({"patient_name"})
        private String patientName;
        @JsonAlias({"patient_phone", "mobile", "phone"})
        private String patientPhone;
        @JsonAlias({"patient_id_card", "id_card", "idCard"})
        private String patientIdCard;
        @JsonAlias({"patient_education"})
        private String patientEducation;
        /** 群名称（与 mcp_chat_group_config.group_name 一致时可按配置分词） */
        @JsonAlias({"group_name"})
        private String groupName;
        /** 下单备注（业务侧） */
        @JsonAlias({"order_remark"})
        private String orderRemark;
        /** 用户在群内的昵称 */
        @JsonAlias({"user_group_nickname", "groupNickname", "group_nickname"})
        private String userGroupNickname;
        /** 兼容：群信息对象（roomName=群名称，remark=备注，senderName=群昵称） */
        @JsonAlias({"chat_info"})
        private ChatInfo chatInfo;
        /** 触发方式：phone / idcard（身份证触发一般是私聊单） */
        @JsonAlias({"request_trigger_type", "triggerType", "trigger_type"})
        private String requestTriggerType;
        /** 药品明细，可选；兼容数组或字符串 "[]"（上游部分通道会把 JSON 数组转成字符串） */
        private Object items;
        /** 聊天截图（图片URL或base64，支持单个URL或JSON数组格式） */
        @JsonAlias({"y3_image_info", "y3PicUrl", "chat_screenshot", "chatScreenshot"})
        private String y3ImageInfo;
        /** 送货医院（审核页手建等） */
        @JsonAlias({"delivery_hospital"})
        private String deliveryHospital;
        /**
         * 订单创建来源：如 mcp_order_audit_manual 表示审核页「手动新增」，用于列表展示手建标识
         */
        @JsonAlias({"order_create_source"})
        private String orderCreateSource;
    }

    @Data
    public static class ChatInfo {
        private String senderName;
        /** 私聊场景昵称（优先展示/保存）；群场景可与 senderName 等价 */
        private String nickName;
        /** 私聊单门店号（用于身份证单门店归属） */
        private String storeId;
        private String remark;
        private String roomName;
    }

    /**
     * core_profile_create 请求体（建档）
     */
    @Data
    public static class CoreProfileCreateRequest {
        /** 用户姓名 */
        private String name;
        /** 18位身份证号 */
        private String idCard;
        /** 11位手机号（单独提供即可建档）；可与 phone 同义 */
        @JsonAlias("phone")
        private String mobile;
    }

    /**
     * core_order_status_update 请求体
     */
    @Data
    public static class CoreOrderStatusUpdateRequest {
        private String orderId;
        private String pendingId;
        /**
         * 订单状态码：1预下单 2下单成功 3退单（推荐）
         */
        private Integer statusCode;
        /**
         * 兼容：订单状态中文：预下单 / 下单成功 / 退单
         */
        private String status;
        /**
         * 小程序回传的发票信息/抬头等（建议传 JSON 字符串）
         */
        private String invoiceInfo;
        /**
         * 小程序回调扩展数据（建议传 JSON 字符串）
         */
        private String callbackData;
        /**
         * 领单人名称
         */
        private String receiverName;
        /**
         * 完成图片内容（JSON 字符串）
         */
        private String completionImagesJson;
    }

    /**
     * 审核请求体
     */
    @Data
    public static class CoreOrderAuditRequest {
        /**
         * 待审核订单的pendingId
         */
        private String pendingId;
        /**
         * 审核备注（通过时可选，驳回时必填）
         */
        private String auditRemark;
    }

    /**
     * 测试海典数据源连接（调试用）
     */
    @GetMapping("/test-haidian-db")
    public Map<String, Object> testHaidianDb() {
        return mcpCoreQueryService.testHaidianDbConnection();
    }

    /**
     * 创建患者信息接口
     * 将患者信息写入海典数据库 corecmsuser 表
     */
    @PostMapping("/create-patient")
    public Map<String, Object> createPatient(@RequestBody CreatePatientRequest request) {
        String name = request != null ? request.getName() : null;
        String phone = request != null ? request.getPhone() : null;
        String idCard = request != null ? request.getIdCard() : null;
        String gender = request != null ? request.getGender() : null;
        Integer age = request != null ? request.getAge() : null;
        String address = request != null ? request.getAddress() : null;
        String remark = request != null ? request.getRemark() : null;
        log.info("MCP create-patient 请求，name={}, phone={}, idCard={}", name, phone, idCard);
        return mcpCoreQueryService.createPatient(name, phone, idCard, gender, age, address, remark);
    }

    /**
     * create-patient 请求体
     */
    @Data
    public static class CreatePatientRequest {
        /**
         * 患者姓名（必填）
         */
        private String name;
        
        /**
         * 手机号（必填）
         */
        private String phone;
        
        /**
         * 身份证号（可选）
         */
        private String idCard;
        
        /**
         * 性别（可选）：男/女
         */
        private String gender;
        
        /**
         * 年龄（可选）
         */
        private Integer age;
        
        /**
         * 地址（可选）
         */
        private String address;
        
        /**
         * 备注（可选）
         */
        private String remark;
    }
}

