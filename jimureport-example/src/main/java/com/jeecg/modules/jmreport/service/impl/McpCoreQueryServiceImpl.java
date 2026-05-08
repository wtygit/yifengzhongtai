package com.jeecg.modules.jmreport.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jeecg.modules.jmreport.service.AiSqlService;
import com.jeecg.modules.jmreport.service.GroupNameTokenService;
import com.jeecg.modules.jmreport.service.McpCoreQueryService;
import com.jeecg.modules.jmreport.service.McpStoreLoginService;
import com.jeecg.modules.jmreport.websocket.McpOrderAuditRealtimePublisher;
import cn.dev33.satoken.stp.StpUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 核心查询服务实现
 */
@Slf4j
@Service
public class McpCoreQueryServiceImpl implements McpCoreQueryService {
    private static final int DUPLICATE_SUBMIT_WINDOW_MINUTES = 5;

    /**
     * 原始接入留痕：请求一进服务即落库，不参与待审核列表、不参与短时合并（与业务 pending 区分）。
     */
    private static final int AUDIT_STATUS_RAW_INGEST = 8;

    /** 群分词下拉：仅扫冗余列时的最大行数（避免拉 longtext） */
    private static final int GROUP_TOKEN_OPTIONS_FAST_SCAN_LIMIT = 2500;

    /** 无冗余分词字段时，对 user_request_data 全量解析的最大行数 */
    private static final int GROUP_TOKEN_OPTIONS_FULL_JSON_LIMIT = 1200;

    /** 有冗余列时，对仍缺分词的老数据回退解析 user_request_data 的最大行数 */
    private static final int GROUP_TOKEN_OPTIONS_LEGACY_JSON_LIMIT = 400;

    /** 群分词选项接口结果缓存（毫秒），减轻审核页反复打开时的库压 */
    private static final long GROUP_TOKEN_OPTIONS_CACHE_TTL_MS = 60_000L;

    private final Object groupTokenOptionsCacheLock = new Object();
    private volatile long groupTokenOptionsCacheExpiresAtMs;
    private volatile List<Map<String, Object>> groupTokenOptionsCachedList;

    /** 海典库 hospitallist：懒加载表名与「医院名称」列（0=未探测，1=成功，-1=失败） */
    private final Object hospitallistMetaLock = new Object();
    private volatile int hospitallistMetaState;
    private volatile String hospitallistTableNameCache;
    private volatile String hospitallistNameColumnCache;

    private static final Pattern MCP_SAFE_SQL_IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * 海典同步数据源 JdbcTemplate（MCP 订单/患者/药品查询 + MCP 下单双表落库）
     */
    @Autowired
    @Qualifier("haidianJdbcTemplate")
    private JdbcTemplate haidianJdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired(required = false)
    private McpOrderAuditRealtimePublisher mcpOrderAuditRealtimePublisher;

    @Autowired(required = false)
    private GroupNameTokenService groupNameTokenService;

    @Autowired(required = false)
    private McpStoreLoginService mcpStoreLoginService;

    private final AtomicReference<Boolean> orderLogGroupColumnsPresent = new AtomicReference<>();

    /**
     * 中台下单接口 baseUrl，例如 http://test-cn.your-api-server.com
     */
    @Value("${middle-platform.base-url:}")
    private String middlePlatformBaseUrl;

    /**
     * 中台下单接口 X-Api-Key（可为空：按对方网关策略决定）
     */
    @Value("${middle-platform.api-key:}")
    private String middlePlatformApiKey;

    /**
     * 门店编号（中台必填）
     */
    @Value("${middle-platform.store-id:MD001}")
    private String middlePlatformStoreId;

    /**
     * 订单状态：1待接单，2待下单，3配送中，4待上传凭证
     */
    @Value("${middle-platform.default-status:2}")
    private int middlePlatformDefaultStatus;

    /**
     * 面向 MCP 工具的 AI SQL 扩展点。
     * 当前默认实现不启用 AI，返回 null，调用方会自动回退到兜底 SQL。
     */
    @Autowired(required = false)
    private AiSqlService aiSqlService;

    @Override
    public Map<String, Object> queryOrderByOrderId(String orderId) {
        Map<String, Object> result = new HashMap<>();
        if (!StringUtils.hasText(orderId)) {
            result.put("code", 400);
            result.put("msg", "orderId 不能为空");
            result.put("data", null);
            return result;
        }

        // 1. 优先尝试通过 AI 生成更灵活的 SQL 查询（可返回更多字段/组合表）
        Map<String, Object> aiResult = tryQueryByAi("core_order_query", Map.of("orderId", orderId));
        if (aiResult != null) {
            return aiResult;
        }

        // 2. 兜底：使用当前写死的 SQL，保证老客户行为不变

        String sql = """
                SELECT
                  orderId,
                  status,
                  payStatus,
                  shipStatus,
                  orderType,
                  goodsAmount,
                  payedAmount,
                  orderAmount,
                  costFreight,
                  orderDiscountAmount,
                  goodsDiscountAmount,
                  couponDiscountAmount,
                  payType,
                  paymentCode,
                  paymentTime,
                  sfwaybillNo,
                  logisticsId,
                  logisticsName,
                  shipName,
                  shipMobile,
                  shipAddress,
                  memcardno,
                  cardholder,
                  idcard,
                  mobile,
                  busno,
                  prescriptionDoctor,
                  patient,
                  patientIllness,
                  sex,
                  age,
                  office,
                  createTime,
                  updateTime
                FROM corecmsorder
                WHERE orderId = ?
                """;
        try {
            List<Map<String, Object>> list = haidianJdbcTemplate.queryForList(sql, orderId);
            if (list == null || list.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "订单不存在");
                result.put("data", null);
            } else {
                result.put("code", 0);
                result.put("msg", "ok");
                result.put("data", list.get(0));
            }
        } catch (Exception e) {
            log.error("根据订单号查询核心订单失败，orderId={}", orderId, e);
            result.put("code", 500);
            result.put("msg", "查询订单失败：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    @Override
    public Map<String, Object> queryInsurance(Long userId, String mobile, String idCard) {
        Map<String, Object> result = new HashMap<>();

        String whereClause;
        Object[] args;

        boolean hasIdCard = StringUtils.hasText(idCard);
        boolean hasMobile = StringUtils.hasText(mobile);
        if (userId != null) {
            whereClause = "userId = ?";
            args = new Object[]{userId};
        } else if (hasIdCard && hasMobile) {
            // 同时传了身份证与手机号：不要只按身份证查，要两者都覆盖（与建档“任意两项”保持一致）
            whereClause = "((idCard = ? OR bmrSfz = ?) OR mobile = ?)";
            args = new Object[]{idCard, idCard, mobile};
        } else if (hasIdCard) {
            whereClause = "(idCard = ? OR bmrSfz = ?)";
            args = new Object[]{idCard, idCard};
        } else if (hasMobile) {
            whereClause = "mobile = ?";
            args = new Object[]{mobile};
        } else {
            result.put("code", 400);
            result.put("msg", "userId、mobile、idCard 至少传一个");
            result.put("data", null);
            return result;
        }

        // 1) 先固定查询患者/收货档案（corecmsusership），不交给 AI
        String sql = """
                SELECT
                  id,
                  userId,
                  name,
                  mobile,
                  address,
                  areaId,
                  idCard,
                  bmrSfz,
                  customerId,
                  bmrCustomerId,
                  hzgx       AS relation,
                  sex,
                  birthday,
                  isDefault,
                  yisheng    AS doctor,
                  jibing     AS disease,
                  keshi      AS department,
                  longitude,
                  latitude,
                  street,
                  dispenser        AS pharmacist,
                  dispenser_Phone  AS pharmacistPhone,
                  remark,
                  createTime,
                  updateTime
                FROM corecmsusership
                WHERE %s
                """.formatted(whereClause);

        List<Map<String, Object>> rows;
        try {
            rows = haidianJdbcTemplate.queryForList(sql, args);
        } catch (Exception e) {
            log.error("查询医保/患者档案失败，userId={}, mobile={}, idCard={}", userId, mobile, idCard, e);
            result.put("code", 500);
            result.put("msg", "查询医保/患者档案失败：" + e.getMessage());
            result.put("data", null);
            return result;
        }

        // 2) 查询患者基础档案（corecmsuser），并为每条 corecmsusership 记录挂载 userArchive
        try {
            java.util.Map<String, Object> aiParams = new java.util.LinkedHashMap<>();
            if (userId != null) aiParams.put("userId", userId);
            if (StringUtils.hasText(mobile)) aiParams.put("mobile", mobile);
            if (StringUtils.hasText(idCard)) aiParams.put("idCard", idCard);
            Map<String, Object> aiResult = tryQueryByAi("core_insurance_query", aiParams);
            String aiSql = extractSqlFromAiResult(aiResult);

            java.util.Map<Long, Map<String, Object>> userArchiveById = new java.util.HashMap<>();
            java.util.Set<Long> userIds = new java.util.HashSet<>();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Object uid = row.get("userId");
                    if (uid instanceof Number) {
                        userIds.add(((Number) uid).longValue());
                    } else if (uid != null) {
                        try {
                            userIds.add(Long.parseLong(uid.toString()));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (!userIds.isEmpty()) {
                String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
                java.util.ArrayList<Object> uArgs = new java.util.ArrayList<>(userIds);
                List<Map<String, Object>> users = haidianJdbcTemplate.queryForList(
                        "SELECT * FROM corecmsuser WHERE id IN (" + placeholders + ")",
                        uArgs.toArray());
                for (Map<String, Object> u : users) {
                    Object idVal = u.get("id");
                    if (idVal instanceof Number) {
                        userArchiveById.put(((Number) idVal).longValue(), u);
                    } else if (idVal != null) {
                        try {
                            userArchiveById.put(Long.parseLong(idVal.toString()), u);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } else {
                // 若 corecmsusership 未命中，尝试按 mobile/userId 直接查询 corecmsuser（患者基础档案）
                List<Map<String, Object>> users = List.of();
                if (userId != null) {
                    users = haidianJdbcTemplate.queryForList("SELECT * FROM corecmsuser WHERE id = ? LIMIT 1", userId);
                } else if (StringUtils.hasText(mobile)) {
                    users = haidianJdbcTemplate.queryForList("SELECT * FROM corecmsuser WHERE mobile = ? ORDER BY createTime DESC LIMIT 5", mobile.trim());
                }
                for (Map<String, Object> u : users) {
                    Object idVal = u.get("id");
                    if (idVal instanceof Number) {
                        userArchiveById.put(((Number) idVal).longValue(), u);
                    }
                }
            }

            // 3) 为每条患者补充医保记录 + 患者基础档案
            for (Map<String, Object> row : rows) {
                Object idCardVal = row.get("idCard");
                Object bmrSfzVal = row.get("bmrSfz");
                String ic = idCardVal != null ? idCardVal.toString().trim() : "";
                if (ic.isEmpty() && bmrSfzVal != null) {
                    ic = bmrSfzVal.toString().trim();
                }
                row.put("insuranceRecords", ic.isEmpty() ? List.of() : queryInsuranceOcrByIdCard(ic));

                Long uid = null;
                Object uidVal = row.get("userId");
                if (uidVal instanceof Number) uid = ((Number) uidVal).longValue();
                else if (uidVal != null) {
                    try { uid = Long.parseLong(uidVal.toString()); } catch (Exception ignored) {}
                }
                row.put("userArchive", uid == null ? null : userArchiveById.get(uid));
            }

            // 若 corecmsusership 没有数据，但存在患者基础档案或医保记录，也返回一个“档案汇总”对象，避免客户只拿到医保数据
            if ((rows == null || rows.isEmpty())) {
                java.util.ArrayList<Map<String, Object>> outRows = new java.util.ArrayList<>();
                if (!userArchiveById.isEmpty()) {
                    for (Map.Entry<Long, Map<String, Object>> e : userArchiveById.entrySet()) {
                        Map<String, Object> r = new java.util.LinkedHashMap<>();
                        r.put("userId", e.getKey());
                        r.put("userArchive", e.getValue());
                        r.put("insuranceRecords", StringUtils.hasText(idCard) ? queryInsuranceOcrByIdCard(idCard.trim()) : List.of());
                        outRows.add(r);
                    }
                } else if (StringUtils.hasText(idCard)) {
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("userArchive", null);
                    r.put("insuranceRecords", queryInsuranceOcrByIdCard(idCard.trim()));
                    outRows.add(r);
                }
                rows = outRows;
            }
            result.put("code", 0);
            result.put("msg", "ok");
            result.put("data", rows);
            if (aiSql != null && !aiSql.isBlank()) {
                result.put("aiSql", aiSql);
            }
        } catch (Exception e) {
            log.error("查询医保/患者档案失败，userId={}, mobile={}, idCard={}", userId, mobile, idCard, e);
            result.put("code", 500);
            result.put("msg", "查询医保/患者档案失败：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    @Override
    public Map<String, Object> queryDrug(String keyword, String barCode) {
        Map<String, Object> result = new HashMap<>();

        if (!StringUtils.hasText(keyword) && !StringUtils.hasText(barCode)) {
            result.put("code", 400);
            result.put("msg", "keyword 和 barCode 至少传一个");
            result.put("data", null);
            return result;
        }

        // 1) AI 优先：先尝试 core_drug_query 的 AI SQL，如果成功且返回了数据，则直接使用 AI 结果
        Map<String, Object> aiParams = new HashMap<>();
        if (StringUtils.hasText(keyword)) {
            aiParams.put("keyword", keyword);
        }
        if (StringUtils.hasText(barCode)) {
            aiParams.put("barCode", barCode);
        }
        Map<String, Object> aiResult = tryQueryByAi("core_drug_query", aiParams);
        if (aiResult != null) {
            Object code = aiResult.get("code");
            List<Map<String, Object>> rows = extractRowsFromAiResult(aiResult);
            if (code instanceof Number && ((Number) code).intValue() == 0 && rows != null && !rows.isEmpty()) {
                // 统一返回结构：data 为 rows，额外带上 aiSql 字段便于调试
                String aiSql = extractSqlFromAiResult(aiResult);
                Map<String, Object> out = new HashMap<>();
                out.put("code", 0);
                out.put("msg", "ok");
                out.put("data", rows);
                if (aiSql != null && !aiSql.isBlank()) {
                    out.put("aiSql", aiSql);
                }
                return out;
            }
            // 否则认为 AI 查询未命中有效数据，继续走兜底 SQL
        }

        // 海典同步数据源：
        // - 药品基础表 t_ware_base：WAREID, WARENAME, WAREGENERALNAME, WARESPEC, FACTORYID, WAREUNIT, FILENO, WAREABC, WARECODE, BARCODE, LASTTIME 等
        // - 门店库存表 t_store_h：按 BUSNO + WAREID 维度记录门店库存，这里按 WAREID 聚合出总库存（SUMQTY）、在途（ENROUTEQTY）、待到货（SUMAWAITQTY）及最近配货时间（LASTDISTDATE）
        StringBuilder sql = new StringBuilder();
        sql.append("""
                SELECT
                  b.WAREID,
                  b.WARENAME,
                  b.WAREGENERALNAME,
                  b.WARESPEC,
                  b.FACTORYID,
                  b.WAREUNIT,
                  b.FILENO,
                  b.WAREABC,
                  b.WARECODE,
                  b.BARCODE,
                  b.LASTTIME,
                  COALESCE(SUM(s.SUMQTY), 0)      AS totalStock,
                  COALESCE(SUM(s.ENROUTEQTY), 0)  AS enrouteQty,
                  COALESCE(SUM(s.SUMAWAITQTY), 0) AS awaitQty,
                  MAX(s.LASTDISTDATE)             AS lastDistDate
                FROM
                  t_ware_base b
                LEFT JOIN
                  t_store_h s ON b.WAREID = s.WAREID
                WHERE 1 = 1
                """);

        java.util.List<Object> params = new java.util.ArrayList<>();
        if (StringUtils.hasText(barCode)) {
            sql.append(" AND (b.BARCODE = ? OR b.BARCODE LIKE ?) ");
            params.add(barCode.trim());
            params.add("%" + barCode.trim() + "%");
        } else if (StringUtils.hasText(keyword)) {
            sql.append(" AND (b.WARENAME LIKE ? OR b.WAREABC LIKE ?) ");
            String like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
        }
        sql.append("""
                GROUP BY
                  b.WAREID,
                  b.WARENAME,
                  b.WAREGENERALNAME,
                  b.WARESPEC,
                  b.FACTORYID,
                  b.WAREUNIT,
                  b.FILENO,
                  b.WAREABC,
                  b.WARECODE,
                  b.BARCODE,
                  b.LASTTIME
                ORDER BY
                  b.LASTTIME DESC
                LIMIT 100
                """);

        try {
            List<Map<String, Object>> list = haidianJdbcTemplate.queryForList(sql.toString(), params.toArray());
            result.put("code", 0);
            result.put("msg", "ok");
            result.put("data", list);
        } catch (Exception e) {
            log.error("查询药品信息失败，keyword={}, barCode={}", keyword, barCode, e);
            result.put("code", 500);
            result.put("msg", "查询药品信息失败：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    @Override
    public Map<String, Object> queryVisitStrategy(String businessId) {
        Map<String, Object> result = new HashMap<>();
        // 当前无真实回访策略数据，统一返回“暂无数据支持”
        result.put("code", 0);
        result.put("msg", "暂无数据支持");
        result.put("data", null);
        return result;
    }

    @Override
    public Map<String, Object> createOrderPlaceholder(String requestJson) {
        Map<String, Object> result = new HashMap<>();
        if (!StringUtils.hasText(requestJson)) {
            result.put("code", 400);
            result.put("msg", "requestJson 不能为空（可直接传自然语言描述，例如：患者名称：张三，电话号码：...，需要买的药：...）");
            result.put("data", null);
            return result;
        }
        persistRawOrderCreatePlaceholder(requestJson);
        // 只保存到表A，状态为待审核，不调用中台接口
        String pendingId = newMcpOrderPendingId();
        Map<String, Object> saved = saveMcpOrderRequestLog(pendingId, pendingId, "natural", Map.of("requestJson", requestJson));
        if (saved == null) {
            result.put("code", 500);
            result.put("msg", "订单写入海典库 mcp_order_create_request_log 失败，请检查 haidian.datasource 与表结构（原始接入留痕已尝试写入）");
            result.put("data", Map.of("pendingId", pendingId));
            return result;
        }

        result.put("code", 0);
        result.put("msg", "ok");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pendingId", pendingId);
        data.put("message", "订单已提交，等待审核");
        result.put("data", data);
        return result;
    }

    private static Map<String, Object> naturalParseSnapshot(String pendingId, String phase, ParsedOrderText parsed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase);
        m.put("pendingId", pendingId);
        if (parsed != null) {
            m.put("patientName", parsed.patientName);
            m.put("patientPhone", parsed.patientPhone);
            m.put("patientIdCard", parsed.patientIdCard);
            m.put("patientEducation", parsed.patientEducation);
            m.put("parsedItemCount", parsed.items != null ? parsed.items.size() : 0);
        }
        return m;
    }

    /**
     * 结构化下单至少需要其一：手机号（patientPhone / mobile / phone 等）、requestJson、或非空 items。
     * 姓名、身份证、患教可选；仅姓名/身份证/患教而无手机号、无 requestJson、无药品时不能创建。
     */
    private boolean hasOrderCreatePhone(Map<String, Object> request) {
        if (request == null) {
            return false;
        }
        String p = firstNonBlank(
                blankToNull(str(request.get("patientPhone"))),
                blankToNull(getIgnoreCase(request, "patient_phone")),
                blankToNull(str(request.get("mobile"))),
                blankToNull(str(request.get("phone"))));
        return StringUtils.hasText(p);
    }

    private boolean hasStructuredOrderContext(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            return false;
        }
        Object rj = request.get("requestJson");
        if (rj == null || !StringUtils.hasText(String.valueOf(rj))) {
            String rjs = getIgnoreCase(request, "request_json");
            rj = StringUtils.hasText(rjs) ? rjs : null;
        }
        if (rj != null && StringUtils.hasText(String.valueOf(rj))) {
            return true;
        }
        if (hasOrderCreatePhone(request)) {
            return true;
        }
        Object itemsObj = request.get("items");
        return itemsObj instanceof List<?> list && !list.isEmpty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> createOrder(Map<String, Object> request) {
        if (request == null) {
            return Map.of("code", 400, "msg", "请求体不能为空", "data", null);
        }
        persistRawOrderCreateRequest(request);
        String formatErr = validateChatInfoOnlyPayload(request);
        if (StringUtils.hasText(formatErr)) {
            return Map.of("code", 400, "msg", formatErr, "data", Map.of("request", request));
        }

        Map<String, Object> result = new HashMap<>();

        Object itemsObj = request.get("items");
        if (!(itemsObj instanceof List<?>)) {
            itemsObj = Collections.emptyList();
        }
        List<?> rawItems = (List<?>) itemsObj;
        List<Map<String, Object>> normalizedItems;
        if (rawItems.isEmpty()) {
            normalizedItems = Collections.emptyList();
        } else {
            normalizedItems = normalizeItemsList(itemsObj);
            if (normalizedItems.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "items 已传入但无有效药品行（每项需含 drugName/name 等）；若暂无药品请省略 items 或传空数组 []");
                result.put("data", Map.of("request", request));
                return result;
            }
        }

        Map<String, Object> normalizedRequest = prepareNormalizedOrderPayload(request);
        String hitPendingId = findRecentPendingIdForShortDuplicateMerge(normalizedRequest, DUPLICATE_SUBMIT_WINDOW_MINUTES);
        if (StringUtils.hasText(hitPendingId)) {
            try {
                syncOcrProfileFromAuditPatientEdit(hitPendingId, normalizedRequest);
                String requestDataJson = objectMapper.writeValueAsString(normalizedRequest);
                int updated = haidianJdbcTemplate.update(
                        "UPDATE mcp_order_create_request_log SET user_request_data = ? WHERE pending_id = ? AND audit_status = 0",
                        requestDataJson, hitPendingId);
                if (updated > 0) {
                    syncOrderGroupDenormalizedColumns(hitPendingId, normalizedRequest);
                    if (mcpOrderAuditRealtimePublisher != null) {
                        mcpOrderAuditRealtimePublisher.publishMergedPendingOrder(
                                hitPendingId, "structured",
                                str(normalizedRequest.get("patientName")),
                                str(normalizedRequest.get("patientPhone")));
                    }
                    result.put("code", 0);
                    result.put("msg", "ok");
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("pendingId", hitPendingId);
                    data.put("merged", true);
                    data.put("message", "检测到短时间内同手机号重复提交，已更新最近一条待审核订单");
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("patientName", normalizedRequest.get("patientName"));
                    p.put("patientPhone", normalizedRequest.get("patientPhone"));
                    data.put("patient", p);
                    result.put("data", data);
                    return result;
                }
                // 合并目标在库中已不存在或已非待审核：此前会仍返回 merged 成功但 0 行写入，导致库中无数据
                log.warn("短时合并 UPDATE 未命中（pending 不存在或已审核）pendingId={}，改为新建待审核单", hitPendingId);
            } catch (Exception e) {
                log.error("短时间重复下单合并更新失败 pendingId={}", hitPendingId, e);
                return Map.of("code", 500, "msg", "短时间重复下单处理失败：" + e.getMessage(), "data", null);
            }
        }

        // 只保存到表A，状态为待审核，不调用中台接口
        String pendingId = newMcpOrderPendingId();
        Map<String, Object> savedNorm = saveMcpOrderRequestLog(pendingId, pendingId, "structured", normalizedRequest);
        if (savedNorm == null) {
            return Map.of("code", 500, "msg", "订单写入海典库 mcp_order_create_request_log 失败，请检查 haidian.datasource 连接、表结构及日志", "data", Map.of("pendingId", pendingId));
        }

        result.put("code", 0);
        result.put("msg", "ok");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pendingId", pendingId);
        data.put("message", normalizedItems.isEmpty()
                ? "订单已提交，等待审核（暂无药品信息，审核通过下单前须补充药品）"
                : "订单已提交，等待审核");
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("groupName", savedNorm.get("groupName"));
        g.put("orderRemark", savedNorm.get("orderRemark"));
        g.put("userGroupNickname", savedNorm.get("userGroupNickname"));
        g.put("groupTokens", savedNorm.get("groupTokens"));
        data.put("group", g);
        result.put("data", data);
        return result;
    }

    @SuppressWarnings("unchecked")
    private String validateChatInfoOnlyPayload(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            return "请求体不能为空";
        }
        // 仅允许 chatInfo 风格，禁止旧兼容字段，避免调用方混用导致语义歧义。
        String[] forbidden = {
                "groupName", "group_name", "orderRemark", "order_remark",
                "userGroupNickname", "user_group_nickname", "groupNickname", "group_nickname",
                "requestJson", "request_json", "mobile", "phone", "patient_name", "patient_phone"
        };
        for (String k : forbidden) {
            Object v = request.get(k);
            if (v != null && StringUtils.hasText(String.valueOf(v))) {
                return "仅支持 chatInfo 结构传参，请移除字段：" + k;
            }
        }
        Object chatInfoObj = request.get("chatInfo");
        if (chatInfoObj != null && !(chatInfoObj instanceof Map<?, ?>)) {
            return "chatInfo 须为 JSON 对象";
        }
        return null;
    }

    private static String normalizePhone(String phone) {
        if (!StringUtils.hasText(phone)) {
            return null;
        }
        String p = phone.trim().replaceAll("\\s+", "");
        return p.isEmpty() ? null : p;
    }

    /**
     * 短时「重复提交合并」用手机号做主键；与微信号/群昵称无关。
     * 统一抽取 11 位大陆手机号（去空格、去 +86/86 前缀），避免格式不一致导致误判。
     */
    private static String canonicalMobileForShortDuplicate(String raw) {
        String base = normalizePhone(raw);
        if (!StringUtils.hasText(base)) {
            return null;
        }
        String digits = base.replaceAll("\\D", "");
        if (digits.length() >= 13 && digits.startsWith("86")) {
            digits = digits.substring(2);
        }
        if (digits.length() == 11 && digits.charAt(0) == '1') {
            return digits;
        }
        return base;
    }

    private static String normalizePatientNameForMergeCompare(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        return name.trim().replaceAll("\\s+", "");
    }

    private static String normalizeIdCardForMergeCompare(String idCard) {
        if (!StringUtils.hasText(idCard)) {
            return "";
        }
        String t = idCard.trim();
        if (t.length() == 18) {
            return t.toUpperCase();
        }
        return t;
    }

    /**
     * 短时合并：须为同一手机号；若两侧均有 18 位身份证且不同，或两侧均有非空姓名且不同，则视为不同患者，不合并（避免上游误传同一手机号、或家庭共号时误覆盖）。
     */
    private static boolean shouldMergeShortDuplicatePatient(Map<String, Object> oldNorm, Map<String, Object> newNorm) {
        if (oldNorm == null || newNorm == null) {
            return false;
        }
        String oldM = canonicalMobileForShortDuplicate(str(oldNorm.get("patientPhone")));
        String newM = canonicalMobileForShortDuplicate(str(newNorm.get("patientPhone")));
        if (!StringUtils.hasText(oldM) || !oldM.equals(newM)) {
            return false;
        }
        String oldId = normalizeIdCardForMergeCompare(str(oldNorm.get("patientIdCard")));
        String newId = normalizeIdCardForMergeCompare(str(newNorm.get("patientIdCard")));
        if (oldId.length() == 18 && newId.length() == 18 && !oldId.equals(newId)) {
            return false;
        }
        String oldName = normalizePatientNameForMergeCompare(str(oldNorm.get("patientName")));
        String newName = normalizePatientNameForMergeCompare(str(newNorm.get("patientName")));
        if (StringUtils.hasText(oldName) && StringUtils.hasText(newName) && !oldName.equals(newName)) {
            return false;
        }
        return true;
    }

    /**
     * 查找短时内可合并的待审核单：逻辑仅依赖 user_request_data 中的患者手机号（规范化后）及姓名/身份证互斥，不使用微信标识。
     */
    private String findRecentPendingIdForShortDuplicateMerge(Map<String, Object> normalizedNew, int windowMinutes) {
        if (haidianJdbcTemplate == null || normalizedNew == null || windowMinutes <= 0) {
            return null;
        }
        String canonNew = canonicalMobileForShortDuplicate(str(normalizedNew.get("patientPhone")));
        if (!StringUtils.hasText(canonNew)) {
            return null;
        }
        try {
            java.sql.Timestamp since = new java.sql.Timestamp(System.currentTimeMillis() - windowMinutes * 60_000L);
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(
                    """
                            SELECT pending_id, user_request_data
                            FROM mcp_order_create_request_log
                            WHERE audit_status = 0 AND create_time >= ?
                              AND IFNULL(request_source, '') <> 'ingest'
                            ORDER BY create_time DESC
                            LIMIT 30
                            """,
                    since);
            for (Map<String, Object> row : rows) {
                String pendingId = row.get("pending_id") == null ? null : String.valueOf(row.get("pending_id"));
                String reqJson = row.get("user_request_data") == null ? null : String.valueOf(row.get("user_request_data"));
                if (!StringUtils.hasText(pendingId) || !StringUtils.hasText(reqJson)) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> oldReq = objectMapper.readValue(reqJson, Map.class);
                    Map<String, Object> oldNorm = normalizeUserRequestDataMap(oldReq);
                    if (shouldMergeShortDuplicatePatient(oldNorm, normalizedNew)) {
                        return pendingId;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("按手机号查找短时重复订单失败 canonPhone={}", canonNew, e);
        }
        return null;
    }

    /**
     * 审核通过后调用中台接口（内部方法，由审核接口调用）
     */
    private Map<String, Object> submitOrderToMiddlePlatform(String pendingId, Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        if (!StringUtils.hasText(middlePlatformBaseUrl)) {
            result.put("code", 500);
            result.put("msg", "中台下单接口未配置：请设置 middle-platform.base-url");
            result.put("data", null);
            return result;
        }

        String patientName = request.get("patientName") == null ? null : String.valueOf(request.get("patientName")).trim();
        String patientPhone = request.get("patientPhone") == null ? null : String.valueOf(request.get("patientPhone")).trim();
        String patientIdCard = request.get("patientIdCard") == null ? null : String.valueOf(request.get("patientIdCard")).trim();
        String patientEducationRaw = request.get("patientEducation") == null ? null : String.valueOf(request.get("patientEducation")).trim();
        String requestTriggerType = request.get("requestTriggerType") == null ? null : String.valueOf(request.get("requestTriggerType")).trim();
        String groupName = request.get("groupName") == null ? null : String.valueOf(request.get("groupName")).trim();
        String middleOrderRemark = request.get("orderRemark") == null ? null : String.valueOf(request.get("orderRemark")).trim();
        String groupNickName = request.get("userGroupNickname") == null ? null : String.valueOf(request.get("userGroupNickname")).trim();
        String deliveryHospital = request.get("deliveryHospital") == null ? null : String.valueOf(request.get("deliveryHospital")).trim();
        String y3ImageInfo = request.get("y3ImageInfo") == null ? null : String.valueOf(request.get("y3ImageInfo")).trim();
        // 小程序/中台侧「患教」展示以群昵称为准，昵称缺失时再回落到原患教字段
        String patientEducation = firstNonBlank(groupNickName, patientEducationRaw);
        Object itemsObj = request.get("items");
        List<?> items = (itemsObj instanceof List) ? (List<?>) itemsObj : List.of();
        // 允许无药品信息下单：items 为空时 goodsDetail 传 []

        // 患者信息：可选。优先使用审核页已确认数据，避免每单都做 AI 回退导致审核接口变慢。
        // 仅在关键字段缺失时再做补齐查询。
        Map<String, Object> patient = null;
        boolean needPatientEnrich = !StringUtils.hasText(patientName)
                || !StringUtils.hasText(patientPhone)
                || !StringUtils.hasText(patientIdCard);
        if (needPatientEnrich && (StringUtils.hasText(patientPhone) || StringUtils.hasText(patientIdCard) || StringUtils.hasText(patientName))) {
            patient = findPatientWithAiFallback(patientPhone, patientIdCard, patientName);
        }
        if (patient != null) {
            String pn = str(patient.get("name"));
            if (!StringUtils.hasText(pn)) pn = str(patient.get("consignee"));
            String pp = str(patient.get("mobile"));
            if (!StringUtils.hasText(pp)) pp = str(patient.get("phone"));
            String pic = firstNonBlank(str(patient.get("idCard")), str(patient.get("bmrSfz")));
            if (StringUtils.hasText(pn)) patientName = pn;
            if (StringUtils.hasText(pp)) patientPhone = pp;
            if (StringUtils.hasText(pic)) patientIdCard = pic;
        }

        // 新逻辑：不再根据药品名称/条码/wareId 去查询 t_ware_base，也不在 goodsDetail 传递药品名称。
        // MCP 收到的原始药品信息（drugName/spec/qty/wareId/barCode）统一放到备注 middleOrderRemark 中，原样传给中台/小程序。
        AiEvidence evidence = new AiEvidence();
        java.util.ArrayList<Map<String, Object>> goods = new java.util.ArrayList<>();
        java.util.ArrayList<Map<String, Object>> remarkItems = new java.util.ArrayList<>();
        int rowNo = 1;
        for (Object it : items) {
            if (!(it instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) it;
            String wareId = m.get("wareId") == null ? null : String.valueOf(m.get("wareId")).trim();
            String barCode = m.get("barCode") == null ? null : String.valueOf(m.get("barCode")).trim();
            String drugName = m.get("drugName") == null ? null : String.valueOf(m.get("drugName")).trim();
            String spec = m.get("spec") == null ? null : String.valueOf(m.get("spec")).trim();
            int qty = 1;
            Object qv = m.get("qty");
            if (qv instanceof Number) qty = ((Number) qv).intValue();
            else if (qv != null) {
                try { qty = Integer.parseInt(String.valueOf(qv).trim()); } catch (Exception ignored) {}
            }
            if (qty <= 0) qty = 1;

            Map<String, Object> g = new LinkedHashMap<>();
            g.put("warespec", StringUtils.hasText(spec) ? spec : "");
            g.put("wareqty", String.valueOf(qty));
            // 兼容旧字段形态：warecode 仍保留（优先 wareId，其次 barCode），但不再回表解析。
            g.put("warecode", firstNonBlank(wareId, barCode, ""));
            // 关键变更：不传药品名称
            g.put("warename", "");
            g.put("tid", "");
            g.put("factoryname", "");
            g.put("rowno", String.valueOf(rowNo++));
            goods.add(g);

            Map<String, Object> ri = new LinkedHashMap<>();
            if (StringUtils.hasText(drugName)) ri.put("drugName", drugName);
            if (StringUtils.hasText(spec)) ri.put("spec", spec);
            ri.put("qty", qty);
            if (StringUtils.hasText(wareId)) ri.put("wareId", wareId);
            if (StringUtils.hasText(barCode)) ri.put("barCode", barCode);
            remarkItems.add(ri);
        }

        String remarkDrugJson = null;
        try {
            remarkDrugJson = objectMapper.writeValueAsString(remarkItems);
        } catch (Exception ignored) {
        }
        if (StringUtils.hasText(remarkDrugJson)) {
            String appended = "MCP药品原始信息=" + remarkDrugJson;
            if (StringUtils.hasText(middleOrderRemark)) {
                middleOrderRemark = middleOrderRemark.trim();
                if (!middleOrderRemark.isEmpty()) {
                    middleOrderRemark = middleOrderRemark + "\n" + appended;
                } else {
                    middleOrderRemark = appended;
                }
            } else {
                middleOrderRemark = appended;
            }
        }
        if (StringUtils.hasText(deliveryHospital)) {
            String line = "送货医院：" + deliveryHospital.trim();
            middleOrderRemark = StringUtils.hasText(middleOrderRemark) ? (middleOrderRemark.trim() + "\n" + line) : line;
        }
        if (StringUtils.hasText(y3ImageInfo)) {
            String line = "聊天截图：" + y3ImageInfo.trim();
            middleOrderRemark = StringUtils.hasText(middleOrderRemark) ? (middleOrderRemark.trim() + "\n" + line) : line;
        }

        String goodsDetailJson;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            goodsDetailJson = om.writeValueAsString(goods);
        } catch (Exception e) {
            String urlEarly = buildMiddlePlatformReceiveUrl(middlePlatformBaseUrl);
            Map<String, Object> partial = new LinkedHashMap<>();
            partial.put("phase", "goods_detail_serialize");
            partial.put("pendingId", pendingId);
            partial.put("patientName", patientName);
            partial.put("patientPhone", patientPhone);
            partial.put("patientIdCard", patientIdCard);
            saveMcpOrderOrderLog(pendingId, pendingId, urlEarly, partial, null, false, "goodsDetail 序列化失败：" + e.getMessage());
            result.put("code", 500);
            result.put("msg", "goodsDetail 序列化失败：" + e.getMessage());
            result.put("data", Map.of("pendingId", pendingId));
            return result;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pendingId", pendingId);
        payload.put("patientName", patientName);
        payload.put("patientPhone", patientPhone);
        payload.put("patientIdCard", patientIdCard);
        payload.put("name", patientName);
        payload.put("phone", patientPhone);
        payload.put("idCard", patientIdCard);
        payload.put("patientEducation", patientEducation);
        // 身份证触发=私聊单：中台不接收群名称
        payload.put("groupName", "idcard".equalsIgnoreCase(requestTriggerType) ? null : groupName);
        payload.put("middleOrderRemark", middleOrderRemark);
        payload.put("groupNickName", groupNickName);
        payload.put("goodsDetail", goodsDetailJson);
        payload.put("status", normalizeStatus(middlePlatformDefaultStatus));
        String storeIdForGate = extractStoreIdForGate(request, extractGroupNameForStoreGate(request));
        String storeIdResolved = StringUtils.hasText(storeIdForGate) ? storeIdForGate : middlePlatformStoreId;
        payload.put("storeId", storeIdResolved);
        // 小程序/中台 snake_case：名称→group_name（优先群名称，私聊单等无群名时回落送货医院）；患教名称；门店编号
        String groupNameSnake = firstNonBlank(blankToNull(groupName), blankToNull(deliveryHospital));
        payload.put("group_name", StringUtils.hasText(groupNameSnake) ? groupNameSnake : null);
        payload.put("patient_education_name", StringUtils.hasText(patientEducation) ? patientEducation : null);
        payload.put("store_code", StringUtils.hasText(storeIdResolved) ? storeIdResolved : null);

        String url = buildMiddlePlatformReceiveUrl(middlePlatformBaseUrl);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(middlePlatformApiKey)) {
                headers.set("X-Api-Key", middlePlatformApiKey);
            }
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            Map<String, Object> resp = restTemplate.postForObject(url, entity, Map.class);

            Map<String, Object> middleData = null;
            String returnedOrderId = null;
            if (resp != null) {
                Object dataObj = resp.get("data");
                if (dataObj instanceof Map) {
                    middleData = (Map<String, Object>) dataObj;
                    returnedOrderId = firstNonBlank(str(middleData.get("orderId")), str(middleData.get("pendingId")));
                }
                if (!StringUtils.hasText(returnedOrderId)) {
                    returnedOrderId = firstNonBlank(str(resp.get("orderId")), str(resp.get("pendingId")));
                }
            }
            String orderIdForLog = StringUtils.hasText(returnedOrderId) ? returnedOrderId : pendingId;
            saveMcpOrderOrderLog(pendingId, orderIdForLog, url, payload, resp, true, null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("input", request);
            data.put("patient", patient);
            data.put("goodsDetail", goods);
            data.put("ai", evidence);
            data.put("middlePlatformUrl", url);
            data.put("pendingId", pendingId);
            data.put("orderId", StringUtils.hasText(returnedOrderId) ? returnedOrderId : pendingId);
            data.put("order", middleData);
            data.put("middlePlatformResponse", resp);

            result.put("code", 0);
            result.put("msg", "ok");
            result.put("data", data);
            return result;
        } catch (Exception e) {
            saveMcpOrderOrderLog(pendingId, pendingId, url, payload, null, false, e.getMessage());
            result.put("code", 500);
            result.put("msg", "调用中台下单接口失败：" + e.getMessage());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("input", request);
            data.put("payload", payload);
            data.put("middlePlatformUrl", url);
            data.put("pendingId", pendingId);
            result.put("data", data);
            return result;
        }
    }

    private static String blankToNull(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 聊天记录里常见“联系送药/安排发货”等动作文案，不应当作药品名入库或审核下单。
     * 这里采用保守规则：仅拦截明显指令型短语，避免误伤真实药名。
     */
    private static boolean isLikelyInstructionTextName(String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        String n = name.trim()
                .replaceAll("[，。；;,.!！?？\\s]+", "")
                .toLowerCase();
        if (!StringUtils.hasText(n)) {
            return true;
        }
        if ("联系送药".equals(n) || "联系发货".equals(n) || "联系配送".equals(n)
                || "安排送药".equals(n) || "安排发货".equals(n)
                || "尽快送药".equals(n) || "马上送药".equals(n)
                || "送药".equals(n) || "发货".equals(n) || "配送".equals(n)
                || "联系患者".equals(n) || "电话联系".equals(n) || "回电".equals(n)) {
            return true;
        }
        // 仅对很短且动作词明显的文本做兜底判定，降低误判风险。
        return n.length() <= 8 && (n.contains("联系") || n.contains("送药") || n.contains("发货") || n.contains("配送"));
    }

    /**
     * 统一 items 元素字段名（drugName/spec/qty/wareId/barCode），忽略无法识别的项。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeItemsList(Object itemsObj) {
        List<Map<String, Object>> out = new ArrayList<>();
        // 兼容：items 可能被上游序列化成字符串，如 "[]"
        if (itemsObj instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) {
                return out;
            }
            try {
                Object parsed = objectMapper.readValue(t, Object.class);
                return normalizeItemsList(parsed);
            } catch (Exception ignored) {
                return out;
            }
        }
        if (!(itemsObj instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            String drugName = firstNonBlank(
                    blankToNull(str(m.get("drugName"))),
                    blankToNull(getIgnoreCase(m, "drug_name")),
                    blankToNull(str(m.get("name"))));
            if (!StringUtils.hasText(drugName)) {
                continue;
            }
            String wareId = firstNonBlank(blankToNull(str(m.get("wareId"))), blankToNull(getIgnoreCase(m, "ware_id")));
            String barCode = firstNonBlank(blankToNull(str(m.get("barCode"))), blankToNull(getIgnoreCase(m, "bar_code")));
            // 无 wareId/barCode 且药名像“动作文案”时，直接丢弃，避免后续审核时报“药品未匹配”。
            if (!StringUtils.hasText(wareId) && !StringUtils.hasText(barCode) && isLikelyInstructionTextName(drugName)) {
                continue;
            }
            LinkedHashMap<String, Object> it = new LinkedHashMap<>();
            it.put("drugName", drugName.trim());
            String spec = firstNonBlank(
                    blankToNull(str(m.get("spec"))),
                    blankToNull(str(m.get("wareSpec"))),
                    blankToNull(getIgnoreCase(m, "ware_spec")));
            if (StringUtils.hasText(spec)) {
                it.put("spec", spec.trim());
            }
            int qty = 1;
            Object qv = m.get("qty");
            if (qv == null) {
                qv = m.get("quantity");
            }
            if (qv instanceof Number) {
                qty = ((Number) qv).intValue();
            } else if (qv != null) {
                try {
                    qty = Integer.parseInt(String.valueOf(qv).trim());
                } catch (Exception ignored) {
                    qty = 1;
                }
            }
            if (qty <= 0) {
                qty = 1;
            }
            it.put("qty", qty);
            if (StringUtils.hasText(wareId)) {
                it.put("wareId", wareId.trim());
            }
            if (StringUtils.hasText(barCode)) {
                it.put("barCode", barCode.trim());
            }
            out.add(it);
        }
        return out;
    }

    /**
     * 从 MCP/Y3 入参中抽取图片类信息（URL 或 JSON），供审核页只读展示及写入备注传给中台。
     */
    private String extractY3ImageInfoFromRaw(Map<String, Object> raw) {
        if (raw == null) {
            return "";
        }
        String direct = firstNonBlank(
                blankToNull(str(raw.get("y3ImageInfo"))),
                blankToNull(getIgnoreCase(raw, "y3_image_info")),
                blankToNull(str(raw.get("y3PicUrl"))),
                blankToNull(getIgnoreCase(raw, "y3_pic_url")),
                blankToNull(str(raw.get("y3ImageUrl"))),
                blankToNull(getIgnoreCase(raw, "y3_image_url")),
                blankToNull(str(raw.get("chatScreenshot"))),
                blankToNull(getIgnoreCase(raw, "chat_screenshot")));
        if (StringUtils.hasText(direct)) {
            return direct.trim();
        }
        Object imgs = firstNonNullObj(raw.get("y3Images"), raw.get("y3_images"), raw.get("Y3Images"));
        String fromList = stringifyY3ImagesObject(imgs);
        if (StringUtils.hasText(fromList)) {
            return fromList;
        }
        Object chatInfoObj = raw.get("chatInfo");
        if (chatInfoObj instanceof Map<?, ?> cm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> chat = (Map<String, Object>) cm;
            String nested = firstNonBlank(
                    blankToNull(str(chat.get("y3ImageInfo"))),
                    blankToNull(str(chat.get("y3PicUrl"))),
                    blankToNull(str(chat.get("y3ImageUrl"))),
                    blankToNull(str(chat.get("chatScreenshot"))),
                    blankToNull(getIgnoreCase(chat, "chat_screenshot")));
            if (StringUtils.hasText(nested)) {
                return nested.trim();
            }
            String nestedList = stringifyY3ImagesObject(firstNonNullObj(chat.get("y3Images"), chat.get("y3_images"), null));
            if (StringUtils.hasText(nestedList)) {
                return nestedList;
            }
        }
        return "";
    }

    private static Object firstNonNullObj(Object a, Object b, Object c) {
        if (a != null) {
            return a;
        }
        if (b != null) {
            return b;
        }
        return c;
    }

    private String stringifyY3ImagesObject(Object imgs) {
        if (imgs == null) {
            return "";
        }
        if (imgs instanceof String s) {
            return s.trim();
        }
        try {
            return objectMapper.writeValueAsString(imgs);
        } catch (Exception e) {
            return String.valueOf(imgs);
        }
    }

    /**
     * 按门店编号筛选（匹配 JSON 内 storeId 或群后缀解析出的门店串）。
     */
    private boolean matchesStoreIdFilter(Map<String, Object> requestData, String storeIdKeyword) {
        if (!StringUtils.hasText(storeIdKeyword)) {
            return true;
        }
        if (requestData == null) {
            return false;
        }
        String q = storeIdKeyword.trim();
        String reqGroupNameRaw = requestData.get("groupName") == null ? "" : String.valueOf(requestData.get("groupName"));
        String sid = extractStoreIdForGate(requestData, extractGroupNameForStoreGate(requestData));
        String suffixCsv = storeIdsCsvFromGroupNameSuffix(reqGroupNameRaw);
        String blob = firstNonBlank(sid, suffixCsv, "");
        return matchesTextFilter(blob, q);
    }

    /**
     * 将 user_request_data 多种入参形态统一为：patientName、patientPhone、patientIdCard、patientEducation、items，
     * 并从 requestJson（自然语言）中补全空缺字段。保留 requestJson 原文便于追溯。
     */
    private Map<String, Object> normalizeUserRequestDataMap(Map<String, Object> raw) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            out.put("patientName", "");
            out.put("patientPhone", "");
            out.put("patientIdCard", "");
            out.put("patientEducation", "");
            out.put("storeId", "");
            out.put("groupName", "");
            out.put("orderRemark", "");
            out.put("userGroupNickname", "");
            out.put("deliveryHospital", "");
            out.put("y3ImageInfo", "");
            out.put("items", new ArrayList<>());
            return out;
        }
        // 兼容：用户侧传 chatInfo: { roomName, remark, senderName } 映射到群字段
        String chatRoomName = null;
        String chatRemark = null;
        String chatSenderName = null;
        String chatNickName = null;
        String chatStoreId = null;
        Object chatInfoObj = raw.get("chatInfo");
        if (chatInfoObj instanceof Map<?, ?> m) {
            chatRoomName = blankToNull(str(m.get("roomName")));
            chatRemark = blankToNull(str(m.get("remark")));
            chatSenderName = blankToNull(str(m.get("senderName")));
            chatNickName = firstNonBlank(
                    blankToNull(str(m.get("nickName"))),
                    blankToNull(str(m.get("nickname"))),
                    blankToNull(str(m.get("wxNickName"))),
                    blankToNull(str(m.get("wechatNickName")))
            );
            chatStoreId = firstNonBlank(
                    blankToNull(str(m.get("storeId"))),
                    blankToNull(str(m.get("storeNo"))),
                    blankToNull(str(m.get("busNo"))),
                    blankToNull(str(m.get("busno")))
            );
        }
        String patientName = firstNonBlank(
                blankToNull(str(raw.get("patientName"))),
                blankToNull(getIgnoreCase(raw, "patient_name")));
        String patientPhone = firstNonBlank(
                blankToNull(str(raw.get("patientPhone"))),
                blankToNull(getIgnoreCase(raw, "patient_phone")),
                blankToNull(str(raw.get("mobile"))),
                blankToNull(str(raw.get("phone"))));
        String patientIdCard = firstNonBlank(
                blankToNull(str(raw.get("patientIdCard"))),
                blankToNull(getIgnoreCase(raw, "patient_id_card")),
                blankToNull(getIgnoreCase(raw, "idCard")),
                blankToNull(getIgnoreCase(raw, "id_card")));
        String patientEducation = firstNonBlank(
                blankToNull(str(raw.get("patientEducation"))),
                blankToNull(getIgnoreCase(raw, "patient_education")));
        String storeId = firstNonBlank(
                blankToNull(str(raw.get("storeId"))),
                blankToNull(getIgnoreCase(raw, "store_id")),
                // 兼容：审核列表返回/前端编辑里经常只有 store_ids（展示用），也要能回填成 storeId
                blankToNull(getIgnoreCase(raw, "storeIds")),
                blankToNull(getIgnoreCase(raw, "store_ids")),
                blankToNull(str(raw.get("storeNo"))),
                blankToNull(getIgnoreCase(raw, "store_no")),
                blankToNull(str(raw.get("busNo"))),
                blankToNull(getIgnoreCase(raw, "busno")),
                blankToNull(chatStoreId)
        );
        String requestTriggerType = normalizeOrderTriggerTypeText(
                firstNonBlank(
                        blankToNull(str(raw.get("requestTriggerType"))),
                        blankToNull(str(raw.get("triggerType"))),
                        blankToNull(str(raw.get("triggerBy"))),
                        blankToNull(str(raw.get("requestSourceType")))
                ),
                patientPhone,
                patientIdCard
        );

        // 身份证触发=私聊单：不应依赖/接收群名称（roomName）。手机号触发的群单仍保留群名称。
        String groupName = "idcard".equals(requestTriggerType)
                ? ""
                : firstNonBlank(
                    blankToNull(str(raw.get("groupName"))),
                    blankToNull(chatRoomName),
                    blankToNull(getIgnoreCase(raw, "group_name"))
                );
        String orderRemark = firstNonBlank(
                blankToNull(str(raw.get("orderRemark"))),
                blankToNull(chatRemark),
                blankToNull(getIgnoreCase(raw, "order_remark")));
        String userGroupNickname = firstNonBlank(
                blankToNull(str(raw.get("userGroupNickname"))),
                blankToNull(chatSenderName),
                blankToNull(chatNickName),
                blankToNull(getIgnoreCase(raw, "user_group_nickname")),
                blankToNull(str(raw.get("groupNickname"))),
                blankToNull(str(raw.get("nickName"))),
                blankToNull(str(raw.get("wxNickName"))),
                blankToNull(getIgnoreCase(raw, "group_nickname")));

        String deliveryHospital = firstNonBlank(
                blankToNull(str(raw.get("deliveryHospital"))),
                blankToNull(getIgnoreCase(raw, "delivery_hospital")),
                blankToNull(str(raw.get("hospitalName"))),
                blankToNull(getIgnoreCase(raw, "hospital_name")));

        String y3ImageInfo = extractY3ImageInfoFromRaw(raw);

        String requestJsonRaw = firstNonBlank(
                blankToNull(str(raw.get("requestJson"))),
                blankToNull(getIgnoreCase(raw, "request_json")));

        ParsedOrderText parsed = null;
        if (StringUtils.hasText(requestJsonRaw)) {
            parsed = parseOrderText(requestJsonRaw);
        }

        patientName = firstNonBlank(patientName, parsed != null ? parsed.patientName : null);
        patientPhone = firstNonBlank(patientPhone, parsed != null ? parsed.patientPhone : null);
        patientIdCard = firstNonBlank(patientIdCard, parsed != null ? parsed.patientIdCard : null);
        patientEducation = firstNonBlank(patientEducation, parsed != null ? parsed.patientEducation : null);

        out.put("patientName", patientName != null ? patientName : "");
        out.put("patientPhone", patientPhone != null ? patientPhone : "");
        out.put("patientIdCard", patientIdCard != null ? patientIdCard : "");
        out.put("patientEducation", patientEducation != null ? patientEducation : "");
        out.put("requestTriggerType", requestTriggerType);
        // 身份证触发（私聊）订单：门店号暂时统一写死为 11403（与页面展示/请求传参无关）
        if ("idcard".equals(requestTriggerType)) {
            storeId = "11403";
        }
        out.put("storeId", storeId != null ? storeId : "");
        out.put("groupName", groupName != null ? groupName : "");
        out.put("orderRemark", orderRemark != null ? orderRemark : "");
        out.put("userGroupNickname", userGroupNickname != null ? userGroupNickname : "");
        out.put("deliveryHospital", deliveryHospital != null ? deliveryHospital : "");
        out.put("y3ImageInfo", y3ImageInfo != null ? y3ImageInfo : "");

        List<Map<String, Object>> items = normalizeItemsList(raw.get("items"));
        if (items.isEmpty() && parsed != null && parsed.items != null) {
            for (ParsedDrugItem pi : parsed.items) {
                if (pi == null || !StringUtils.hasText(pi.drugName)) {
                    continue;
                }
                if (isLikelyInstructionTextName(pi.drugName)) {
                    continue;
                }
                LinkedHashMap<String, Object> it = new LinkedHashMap<>();
                it.put("drugName", pi.drugName.trim());
                it.put("qty", pi.qty > 0 ? pi.qty : 1);
                if (StringUtils.hasText(pi.spec)) {
                    it.put("spec", pi.spec.trim());
                }
                if (StringUtils.hasText(pi.drugAlias)) {
                    it.put("drugAlias", pi.drugAlias.trim());
                }
                items.add(it);
            }
        }
        out.put("items", items);

        if (StringUtils.hasText(requestJsonRaw)) {
            out.put("requestJson", requestJsonRaw);
        }
        if (chatInfoObj instanceof Map) {
            out.put("chatInfo", chatInfoObj);
        }
        return out;
    }

    private String extractStoreIdForGate(Map<String, Object> normalizedRequest, String groupNameFallback) {
        if (normalizedRequest != null) {
            String sid = firstNonBlank(
                    blankToNull(str(normalizedRequest.get("storeId"))),
                    blankToNull(getIgnoreCase(normalizedRequest, "store_id")),
                    blankToNull(getIgnoreCase(normalizedRequest, "storeId"))
            );
            if (StringUtils.hasText(sid)) {
                return sid.trim();
            }
        }
        if (StringUtils.hasText(groupNameFallback)) {
            String first = firstStoreIdFromGroupNameSuffix(groupNameFallback);
            if (StringUtils.hasText(first)) {
                return first.trim();
            }
        }
        return null;
    }

    private boolean hasOrderGroupColumns() {
        Boolean cached = orderLogGroupColumnsPresent.get();
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (orderLogGroupColumnsPresent.get() != null) {
                return orderLogGroupColumnsPresent.get();
            }
            boolean ok = false;
            if (haidianJdbcTemplate != null) {
                try {
                    Integer c = haidianJdbcTemplate.queryForObject(
                            """
                                    SELECT COUNT(*) FROM information_schema.COLUMNS
                                    WHERE TABLE_SCHEMA = DATABASE()
                                      AND TABLE_NAME = 'mcp_order_create_request_log'
                                      AND COLUMN_NAME = 'group_name'
                                    """,
                            Integer.class);
                    ok = c != null && c > 0;
                } catch (Exception e) {
                    log.debug("检测 mcp_order_create_request_log 群字段失败: {}", e.getMessage());
                }
            }
            orderLogGroupColumnsPresent.set(ok);
            return ok;
        }
    }

    private Map<String, Object> prepareNormalizedOrderPayload(Map<String, Object> userRequestData) {
        Map<String, Object> normalized = normalizeUserRequestDataMap(userRequestData);
        enrichOrderPatientFromHaidianTables(normalized);
        if (groupNameTokenService != null) {
            groupNameTokenService.attachGroupTokens(normalized);
        } else {
            normalized.putIfAbsent("groupTokens", new ArrayList<String>());
        }
        return normalized;
    }

    /**
     * 将已序列化 JSON 写入表A（任意 audit_status，用于原始接入留痕等）。
     */
    private int insertMcpOrderRequestLogWithJson(String pendingId, String orderId, String requestSource,
            String userRequestDataJson, int auditStatus) throws Exception {
        if (haidianJdbcTemplate == null || !StringUtils.hasText(pendingId)) {
            return 0;
        }
        String src = StringUtils.hasText(requestSource) ? requestSource : "structured";
        if (hasOrderGroupColumns()) {
            return haidianJdbcTemplate.update("""
                            INSERT INTO mcp_order_create_request_log (
                                pending_id, order_id, request_source, user_request_data, audit_status,
                                group_name, order_context_remark, user_group_nickname, group_tokens_json, group_tokens_search)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    pendingId,
                    orderId,
                    src,
                    userRequestDataJson,
                    auditStatus,
                    null,
                    null,
                    null,
                    "[]",
                    null);
        }
        return haidianJdbcTemplate.update("""
                        INSERT INTO mcp_order_create_request_log (
                            pending_id, order_id, request_source, user_request_data, audit_status
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                pendingId,
                orderId,
                src,
                userRequestDataJson,
                auditStatus);
    }

    /**
     * 结构化下单：请求体一进服务即落库（校验/合并失败也有据可查）。不抛异常以免阻断业务。
     */
    private void persistRawOrderCreateRequest(Map<String, Object> requestBody) {
        if (haidianJdbcTemplate == null || requestBody == null) {
            return;
        }
        String ingestPid = newMcpOrderPendingId();
        try {
            LinkedHashMap<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("_mcpRawIngest", Boolean.TRUE);
            wrap.put("_ingestPendingId", ingestPid);
            wrap.put("receivedAt", java.time.Instant.now().toString());
            wrap.put("body", requestBody);
            String json = objectMapper.writeValueAsString(wrap);
            int n = insertMcpOrderRequestLogWithJson(ingestPid, ingestPid, "ingest", json, AUDIT_STATUS_RAW_INGEST);
            if (n <= 0) {
                log.error("MCP 下单原始接入落库失败（影响行数=0）ingestPendingId={}", ingestPid);
            }
        } catch (Exception e) {
            log.error("MCP 下单原始接入落库异常（业务仍继续执行）ingestPendingId={}", ingestPid, e);
        }
    }

    /**
     * 自然语言下单：先落一条原始 requestJson 快照。
     */
    private void persistRawOrderCreatePlaceholder(String requestJson) {
        if (haidianJdbcTemplate == null || !StringUtils.hasText(requestJson)) {
            return;
        }
        String ingestPid = newMcpOrderPendingId();
        try {
            LinkedHashMap<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("_mcpRawIngest", Boolean.TRUE);
            wrap.put("_ingestPendingId", ingestPid);
            wrap.put("receivedAt", java.time.Instant.now().toString());
            wrap.put("requestJson", requestJson);
            String json = objectMapper.writeValueAsString(wrap);
            int n = insertMcpOrderRequestLogWithJson(ingestPid, ingestPid, "ingest", json, AUDIT_STATUS_RAW_INGEST);
            if (n <= 0) {
                log.error("MCP 自然语言下单原始接入落库失败（影响行数=0）ingestPendingId={}", ingestPid);
            }
        } catch (Exception e) {
            log.error("MCP 自然语言下单原始接入落库异常（业务仍继续）ingestPendingId={}", ingestPid, e);
        }
    }

    /**
     * 表A：保存用户传入的下单原始数据（审核状态默认0：待审核）。
     *
     * @return 归一化后的请求对象（含 groupTokens）；失败返回 null
     */
    private Map<String, Object> saveMcpOrderRequestLog(String pendingId, String orderId, String requestSource, Map<String, Object> userRequestData) {
        if (haidianJdbcTemplate == null || !StringUtils.hasText(pendingId)) {
            return null;
        }
        try {
            Map<String, Object> normalized = prepareNormalizedOrderPayload(userRequestData);
            String requestJson = objectMapper.writeValueAsString(normalized);
            String src = StringUtils.hasText(requestSource) ? requestSource : "structured";
            int n;
            if (hasOrderGroupColumns()) {
                String gn = str(normalized.get("groupName"));
                String orm = str(normalized.get("orderRemark"));
                String ugn = str(normalized.get("userGroupNickname"));
                @SuppressWarnings("unchecked")
                List<String> tokens = normalized.get("groupTokens") instanceof List
                        ? (List<String>) normalized.get("groupTokens")
                        : List.of();
                String tokensJson = objectMapper.writeValueAsString(tokens);
                String search = GroupNameTokenService.buildSearchPipe(tokens);
                n = haidianJdbcTemplate.update("""
                                INSERT INTO mcp_order_create_request_log (
                                    pending_id, order_id, request_source, user_request_data, audit_status,
                                    group_name, order_context_remark, user_group_nickname, group_tokens_json, group_tokens_search)
                                VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                                """,
                        pendingId,
                        orderId,
                        src,
                        requestJson,
                        StringUtils.hasText(gn) ? gn.trim() : null,
                        StringUtils.hasText(orm) ? orm.trim() : null,
                        StringUtils.hasText(ugn) ? ugn.trim() : null,
                        tokensJson,
                        search);
            } else {
                n = haidianJdbcTemplate.update("""
                                INSERT INTO mcp_order_create_request_log (
                                    pending_id, order_id, request_source, user_request_data, audit_status
                                ) VALUES (?, ?, ?, ?, 0)
                                """,
                        pendingId,
                        orderId,
                        src,
                        requestJson);
            }
            if (n <= 0) {
                log.error("写入 mcp_order_create_request_log 影响行数为 0，pendingId={}（请检查是否写入错误库、表权限或 SQL）", pendingId);
                return null;
            }
            if (mcpOrderAuditRealtimePublisher != null) {
                mcpOrderAuditRealtimePublisher.publishNewPendingOrder(
                        pendingId, src,
                        str(normalized.get("patientName")),
                        str(normalized.get("patientPhone")),
                        str(normalized.get("groupName")),
                        str(normalized.get("orderRemark")));
            }
            return normalized;
        } catch (Exception ex) {
            log.error("写入 mcp_order_create_request_log 失败，pendingId={}（请确认海典同步库连接与 db/mcp_order_create_log.sql / db/mcp_chat_group_config.sql 已执行）", pendingId, ex);
            return null;
        }
    }

    private void syncOrderGroupDenormalizedColumns(String pendingId, Map<String, Object> normalized) {
        if (!hasOrderGroupColumns() || !StringUtils.hasText(pendingId) || normalized == null) {
            return;
        }
        try {
            if (groupNameTokenService != null) {
                groupNameTokenService.attachGroupTokens(normalized);
            }
            String gn = str(normalized.get("groupName"));
            String orm = str(normalized.get("orderRemark"));
            String ugn = str(normalized.get("userGroupNickname"));
            @SuppressWarnings("unchecked")
            List<String> tokens = normalized.get("groupTokens") instanceof List
                    ? (List<String>) normalized.get("groupTokens")
                    : List.of();
            String tokensJson = objectMapper.writeValueAsString(tokens);
            String search = GroupNameTokenService.buildSearchPipe(tokens);
            haidianJdbcTemplate.update("""
                            UPDATE mcp_order_create_request_log SET
                                group_name = ?, order_context_remark = ?, user_group_nickname = ?,
                                group_tokens_json = ?, group_tokens_search = ?
                            WHERE pending_id = ?
                            """,
                    StringUtils.hasText(gn) ? gn.trim() : null,
                    StringUtils.hasText(orm) ? orm.trim() : null,
                    StringUtils.hasText(ugn) ? ugn.trim() : null,
                    tokensJson,
                    search,
                    pendingId);
        } catch (Exception e) {
            log.warn("同步订单群冗余字段失败 pendingId={}", pendingId, e);
        }
    }

    /**
     * 下单接入时补齐患者信息（仅填空）：
     * - 手机触发：按手机号从 corecmsusership、corecmsuser、ocrsichuanyibao 补齐姓名/身份证
     * - 身份证触发：按身份证从 corecmsusership、ocrsichuanyibao 补齐姓名（必要时补手机号/身份证）
     *
     * 重要：这里不走 AI，避免 createOrder 入口慢；列表页也不应再做补齐查询。
     */
    private void enrichOrderPatientFromHaidianTables(Map<String, Object> norm) {
        if (haidianJdbcTemplate == null || norm == null) {
            return;
        }
        String phone = blankToNull(str(norm.get("patientPhone")));
        String idCard = blankToNull(str(norm.get("patientIdCard")));
        String name = blankToNull(str(norm.get("patientName")));
        if (StringUtils.hasText(phone)) {
            phone = phone.trim().replaceAll("\\s+", "");
        }
        if (StringUtils.hasText(idCard)) {
            idCard = idCard.trim().replaceAll("\\s+", "");
        }
        if (StringUtils.hasText(name) && (StringUtils.hasText(phone) || StringUtils.hasText(idCard))) {
            // 姓名已有，且已提供任一关键标识，通常无需补齐（仍允许后续按空缺字段补手机号/身份证）
        }

        // A) 优先按身份证补齐（私聊身份证下单的核心需求）
        if (StringUtils.hasText(idCard) && (!StringUtils.hasText(name) || !StringUtils.hasText(phone))) {
            try {
                Map<String, Object> shipById = findUserShipByIdCardFixed(idCard);
                if (shipById != null && !shipById.isEmpty()) {
                    if (!StringUtils.hasText(name)) {
                        String n = blankToNull(str(shipById.get("name")));
                        if (StringUtils.hasText(n)) {
                            norm.put("patientName", n);
                            name = n;
                        }
                    }
                    if (!StringUtils.hasText(phone)) {
                        String p = firstNonBlank(blankToNull(str(shipById.get("mobile"))), blankToNull(str(shipById.get("phone"))));
                        if (StringUtils.hasText(p)) {
                            norm.put("patientPhone", p.trim());
                            phone = p.trim();
                        }
                    }
                    if (!StringUtils.hasText(idCard)) {
                        String id = firstNonBlank(blankToNull(str(shipById.get("idCard"))), blankToNull(str(shipById.get("bmrSfz"))));
                        if (StringUtils.hasText(id)) {
                            norm.put("patientIdCard", id);
                            idCard = id;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("下单补全：按身份证查询 corecmsusership 失败, idCard={}", idCard, e);
            }
            if (!StringUtils.hasText(name)) {
                try {
                    Map<String, Object> ocrRow = findOcrProfileRowByIdCard(idCard);
                    if (ocrRow != null) {
                        String n = firstNonBlank(
                                blankToNull(str(ocrRow.get("xing_ming"))),
                                blankToNull(getIgnoreCase(ocrRow, "xing_ming")),
                                blankToNull(str(ocrRow.get("patientName"))),
                                blankToNull(getIgnoreCase(ocrRow, "patientname")));
                        if (StringUtils.hasText(n)) {
                            norm.put("patientName", n);
                            name = n;
                        }
                    }
                } catch (Exception e) {
                    log.debug("下单补全：按身份证查询 ocrsichuanyibao 失败, idCard={}", idCard, e);
                }
            }
        }

        // B) 手机号路径（原逻辑保留）
        if (!StringUtils.hasText(phone)) {
            return;
        }

        Map<String, Object> ship = null;
        try {
            List<Map<String, Object>> ships = haidianJdbcTemplate.queryForList(
                    "SELECT * FROM corecmsusership WHERE mobile = ? ORDER BY isDefault DESC, updateTime DESC LIMIT 1",
                    phone);
            if (ships != null && !ships.isEmpty()) {
                ship = ships.get(0);
            }
        } catch (Exception e) {
            log.warn("下单补全：查询 corecmsusership 失败, mobile={}", phone, e);
        }

        if (!StringUtils.hasText(name) && ship != null) {
            String n = blankToNull(str(ship.get("name")));
            if (StringUtils.hasText(n)) {
                norm.put("patientName", n);
                name = n;
            }
        }
        if (!StringUtils.hasText(idCard) && ship != null) {
            String id = firstNonBlank(blankToNull(str(ship.get("idCard"))), blankToNull(str(ship.get("bmrSfz"))));
            if (StringUtils.hasText(id)) {
                norm.put("patientIdCard", id);
                idCard = id;
            }
        }

        if (!StringUtils.hasText(name) || !StringUtils.hasText(idCard)) {
            try {
                List<Map<String, Object>> users = haidianJdbcTemplate.queryForList(
                        "SELECT * FROM corecmsuser WHERE mobile = ? ORDER BY createTime DESC LIMIT 1",
                        phone);
                if (users != null && !users.isEmpty()) {
                    Map<String, Object> u = users.get(0);
                    if (!StringUtils.hasText(name)) {
                        String n = firstNonBlank(blankToNull(str(u.get("name"))), blankToNull(str(u.get("realName"))));
                        if (StringUtils.hasText(n)) {
                            norm.put("patientName", n);
                            name = n;
                        }
                    }
                    if (!StringUtils.hasText(idCard)) {
                        String id = blankToNull(str(u.get("idCard")));
                        if (StringUtils.hasText(id)) {
                            norm.put("patientIdCard", id);
                            idCard = id;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("下单补全：查询 corecmsuser 失败, mobile={}", phone, e);
            }
        }

        if (!StringUtils.hasText(name) || !StringUtils.hasText(idCard)) {
            Map<String, Object> ocrRow = findOcrProfileRowByMobile(phone);
            if (ocrRow != null) {
                if (!StringUtils.hasText(name)) {
                    String n = firstNonBlank(
                            blankToNull(str(ocrRow.get("xing_ming"))),
                            blankToNull(getIgnoreCase(ocrRow, "xing_ming")),
                            blankToNull(str(ocrRow.get("patientName"))),
                            blankToNull(getIgnoreCase(ocrRow, "patientname")));
                    if (StringUtils.hasText(n)) {
                        norm.put("patientName", n);
                    }
                }
                if (!StringUtils.hasText(idCard)) {
                    String id = firstNonBlank(
                            blankToNull(str(ocrRow.get("shen_fen_zheng"))),
                            blankToNull(getIgnoreCase(ocrRow, "shen_fen_zheng")),
                            blankToNull(str(ocrRow.get("idCard"))),
                            blankToNull(getIgnoreCase(ocrRow, "idcard")));
                    if (StringUtils.hasText(id)) {
                        norm.put("patientIdCard", id);
                    }
                }
            }
        }
    }

    /** 建档表 ocrsichuanyibao 按手机号查最近一条 */
    private Map<String, Object> findOcrProfileRowByMobile(String mobileTrim) {
        if (haidianJdbcTemplate == null || !StringUtils.hasText(mobileTrim)) {
            return null;
        }
        try {
            java.util.Set<String> cols = getTableColumnsLower("ocrsichuanyibao");
            boolean fullSchema = cols.contains("shen_fen_zheng");
            String phoneCol = fullSchema ? "lian_xi_dian_hua" : "medicalCardNo";
            if (!cols.contains(phoneCol.toLowerCase())) {
                return null;
            }
            List<Map<String, Object>> list = haidianJdbcTemplate.queryForList(
                    "SELECT * FROM ocrsichuanyibao WHERE " + phoneCol + " = ? LIMIT 1",
                    mobileTrim.trim());
            if (list == null || list.isEmpty()) {
                return null;
            }
            return list.get(0);
        } catch (Exception e) {
            log.debug("查询 ocrsichuanyibao 失败, mobile={}", mobileTrim, e);
            return null;
        }
    }

    private Map<String, Object> findOcrProfileRowByIdCard(String idCardTrim) {
        if (haidianJdbcTemplate == null || !StringUtils.hasText(idCardTrim) || idCardTrim.trim().length() != 18) {
            return null;
        }
        try {
            java.util.Set<String> cols = getTableColumnsLower("ocrsichuanyibao");
            boolean fullSchema = cols.contains("shen_fen_zheng");
            String idCol = fullSchema ? "shen_fen_zheng" : "idCard";
            if (!cols.contains(idCol.toLowerCase())) {
                return null;
            }
            List<Map<String, Object>> list = haidianJdbcTemplate.queryForList(
                    "SELECT * FROM ocrsichuanyibao WHERE " + idCol + " = ? LIMIT 1",
                    idCardTrim.trim());
            if (list == null || list.isEmpty()) {
                return null;
            }
            return list.get(0);
        } catch (Exception e) {
            log.debug("查询 ocrsichuanyibao 失败, idCard={}", idCardTrim, e);
            return null;
        }
    }

    /**
     * 审核页保存患者信息后，同步更新 ocrsichuanyibao（按原单/新单 手机或身份证 定位一条记录）。
     */
    private void syncOcrProfileFromAuditPatientEdit(String pendingId, Map<String, Object> normalizedNew) {
        if (haidianJdbcTemplate == null || !StringUtils.hasText(pendingId) || normalizedNew == null) {
            return;
        }
        String newNameRaw = str(normalizedNew.get("patientName"));
        String newPhoneRaw = str(normalizedNew.get("patientPhone"));
        String newIdRaw = str(normalizedNew.get("patientIdCard"));
        String newName = newNameRaw == null ? "" : newNameRaw.trim();
        String newPhone = newPhoneRaw == null ? "" : newPhoneRaw.trim().replaceAll("\\s+", "");
        String newId = newIdRaw == null ? "" : newIdRaw.trim();

        if (!StringUtils.hasText(newPhone) && !StringUtils.hasText(newId) && !StringUtils.hasText(newName)) {
            return;
        }
        if (StringUtils.hasText(newPhone) && newPhone.length() != 11) {
            log.debug("审核同步建档：跳过非法手机号 pendingId={}", pendingId);
            return;
        }
        if (StringUtils.hasText(newId) && newId.length() != 18) {
            log.debug("审核同步建档：跳过非法身份证 pendingId={}", pendingId);
            return;
        }

        Map<String, Object> oldNorm = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(
                    "SELECT user_request_data FROM mcp_order_create_request_log WHERE pending_id = ? LIMIT 1",
                    pendingId);
            if (rows != null && !rows.isEmpty()) {
                Object uj = rows.get(0).get("user_request_data");
                if (uj != null && StringUtils.hasText(String.valueOf(uj))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> raw = objectMapper.readValue(String.valueOf(uj), Map.class);
                    oldNorm = normalizeUserRequestDataMap(raw);
                }
            }
        } catch (Exception e) {
            log.warn("审核同步建档：读取原订单 JSON 失败 pendingId={}", pendingId, e);
        }

        String oldPhone = str(oldNorm.get("patientPhone"));
        if (StringUtils.hasText(oldPhone)) {
            oldPhone = oldPhone.trim().replaceAll("\\s+", "");
        } else {
            oldPhone = "";
        }
        String oldId = str(oldNorm.get("patientIdCard"));
        if (StringUtils.hasText(oldId)) {
            oldId = oldId.trim();
        } else {
            oldId = "";
        }

        Map<String, Object> ocrRow = null;
        if (oldId.length() == 18) {
            ocrRow = findOcrProfileRowByIdCard(oldId);
        }
        if (ocrRow == null && oldPhone.length() == 11) {
            ocrRow = findOcrProfileRowByMobile(oldPhone);
        }
        if (ocrRow == null && newId.length() == 18) {
            ocrRow = findOcrProfileRowByIdCard(newId);
        }
        if (ocrRow == null && newPhone.length() == 11) {
            ocrRow = findOcrProfileRowByMobile(newPhone);
        }

        if (ocrRow != null) {
            applyOcrProfilePatientUpdate(ocrRow, newName, newPhone, newId);
            return;
        }

        // 已下线“订单链路自动建档”能力：
        // 当未命中现有档案时，不再在本系统内 createProfile 新建档案，改由外部系统负责。
        return;
    }

    private void applyOcrProfilePatientUpdate(Map<String, Object> ocrRow, String newName, String newPhone, String newId) {
        if (haidianJdbcTemplate == null || ocrRow == null) {
            return;
        }
        try {
            java.util.Set<String> cols = getTableColumnsLower("ocrsichuanyibao");
            boolean fullSchema = cols.contains("shen_fen_zheng");
            java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
            Object idVal = ocrRow.get("id");

            if (fullSchema) {
                String xingMing = StringUtils.hasText(newName) ? newName : "";
                String lianXi = StringUtils.hasText(newPhone) && newPhone.length() == 11
                        ? newPhone
                        : blankToNull(str(ocrRow.get("lian_xi_dian_hua")));
                if (!StringUtils.hasText(lianXi)) {
                    lianXi = "";
                }
                String shenFen = StringUtils.hasText(newId) && newId.length() == 18
                        ? newId
                        : blankToNull(str(ocrRow.get("shen_fen_zheng")));
                if (!StringUtils.hasText(shenFen)) {
                    shenFen = "";
                }
                if (idVal != null) {
                    StringBuilder sql = new StringBuilder("UPDATE ocrsichuanyibao SET ");
                    java.util.ArrayList<Object> args = new java.util.ArrayList<>();
                    sql.append("xing_ming = ?, ");
                    args.add(xingMing);
                    sql.append("lian_xi_dian_hua = ?, ");
                    args.add(lianXi);
                    sql.append("shen_fen_zheng = ?");
                    args.add(shenFen);
                    if (cols.contains("up_time")) {
                        sql.append(", up_time = ?");
                        args.add(now);
                    }
                    sql.append(" WHERE id = ?");
                    args.add(idVal);
                    haidianJdbcTemplate.update(sql.toString(), args.toArray());
                } else if (cols.contains("lian_xi_dian_hua")) {
                    String oldP = blankToNull(str(ocrRow.get("lian_xi_dian_hua")));
                    if (StringUtils.hasText(oldP)) {
                        java.util.ArrayList<Object> args2 = new java.util.ArrayList<>();
                        args2.add(xingMing);
                        args2.add(lianXi);
                        args2.add(shenFen);
                        if (cols.contains("up_time")) {
                            args2.add(now);
                        }
                        args2.add(oldP);
                        haidianJdbcTemplate.update(
                                "UPDATE ocrsichuanyibao SET xing_ming = ?, lian_xi_dian_hua = ?, shen_fen_zheng = ?"
                                        + (cols.contains("up_time") ? ", up_time = ?" : "")
                                        + " WHERE lian_xi_dian_hua = ?",
                                args2.toArray());
                    }
                }
            } else if (cols.contains("idcard")) {
                String pName = StringUtils.hasText(newName) ? newName : "";
                String mNo = StringUtils.hasText(newPhone) && newPhone.length() == 11
                        ? newPhone
                        : blankToNull(str(ocrRow.get("medicalCardNo")));
                if (!StringUtils.hasText(mNo)) {
                    mNo = "";
                }
                String idC = StringUtils.hasText(newId) && newId.length() == 18
                        ? newId
                        : blankToNull(str(ocrRow.get("idCard")));
                if (!StringUtils.hasText(idC)) {
                    idC = "";
                }
                if (idVal != null) {
                    StringBuilder sql = new StringBuilder("UPDATE ocrsichuanyibao SET patientName = ?, medicalCardNo = ?, idCard = ?");
                    java.util.ArrayList<Object> args = new java.util.ArrayList<>();
                    args.add(pName);
                    args.add(mNo);
                    args.add(idC);
                    if (cols.contains("updatetime")) {
                        sql.append(", updateTime = ?");
                        args.add(now);
                    }
                    sql.append(" WHERE id = ?");
                    args.add(idVal);
                    haidianJdbcTemplate.update(sql.toString(), args.toArray());
                } else if (cols.contains("medicalcardno")) {
                    String oldP = blankToNull(str(ocrRow.get("medicalCardNo")));
                    if (StringUtils.hasText(oldP)) {
                        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
                        args.add(pName);
                        args.add(mNo);
                        args.add(idC);
                        if (cols.contains("updatetime")) {
                            args.add(now);
                        }
                        args.add(oldP);
                        haidianJdbcTemplate.update(
                                "UPDATE ocrsichuanyibao SET patientName = ?, medicalCardNo = ?, idCard = ?"
                                        + (cols.contains("updatetime") ? ", updateTime = ?" : "")
                                        + " WHERE medicalCardNo = ?",
                                args.toArray());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("审核同步建档：UPDATE ocrsichuanyibao 失败", e);
        }
    }

    /**
     * 表B：保存我方生成的订单数据 + 中台响应。
     */
    private void saveMcpOrderOrderLog(String pendingId, String orderId, String middleUrl,
                                      Map<String, Object> generatedOrderData, Map<String, Object> middleResponse,
                                      boolean success, String errorMessage) {
        if (haidianJdbcTemplate == null || !StringUtils.hasText(pendingId)) {
            return;
        }
        try {
            String generatedJson = objectMapper.writeValueAsString(generatedOrderData != null ? generatedOrderData : Map.of());
            String responseJson = middleResponse == null ? null : objectMapper.writeValueAsString(middleResponse);
            final int maxResp = 16_000_000;
            if (responseJson != null && responseJson.length() > maxResp) {
                responseJson = responseJson.substring(0, maxResp) + "...[truncated]";
            }
            String err = errorMessage;
            if (err != null && err.length() > 2000) {
                err = err.substring(0, 2000);
            }
            haidianJdbcTemplate.update("""
                            INSERT INTO mcp_order_create_order_log (
                                pending_id, order_id, order_status, status_update_time,
                                middle_platform_url, generated_order_data, middle_response, call_success, error_message
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    pendingId,
                    orderId,
                    1,
                    new java.sql.Timestamp(System.currentTimeMillis()),
                    middleUrl,
                    generatedJson,
                    responseJson,
                    success ? 1 : 0,
                    err);
        } catch (Exception ex) {
            log.error("写入 mcp_order_create_order_log 失败，pendingId={}（请确认海典同步库连接与 db/mcp_order_create_log.sql 已在该库执行）", pendingId, ex);
        }
    }

    private static String newMcpOrderPendingId() {
        return "PD-" + java.time.LocalDate.now() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** audit_status：0 待审核/待下单，1 已下单（走中台），2 已驳回，3 身份证私聊单本地已完成（未走中台） */
    private static String formatAuditStatusForMessage(int auditStatus) {
        return switch (auditStatus) {
            case 1 -> "已下单";
            case 2 -> "已驳回";
            case 3 -> "已完成";
            default -> "状态码" + auditStatus;
        };
    }

    @Override
    public Map<String, Object> approveOrder(String pendingId, String auditRemark) {
        Map<String, Object> result = new HashMap<>();
        if (!StringUtils.hasText(pendingId)) {
            result.put("code", 400);
            result.put("msg", "pendingId 不能为空");
            result.put("data", null);
            return result;
        }
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", null);
            return result;
        }

        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(
                    "SELECT user_request_data, audit_status FROM mcp_order_create_request_log WHERE pending_id = ?",
                    pendingId);
            if (rows.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "未找到对应的待审核订单");
                result.put("data", null);
                return result;
            }
            Map<String, Object> row = rows.get(0);
            Integer auditStatus = row.get("audit_status") instanceof Number ? ((Number) row.get("audit_status")).intValue() : null;
            if (auditStatus != null && auditStatus != 0) {
                result.put("code", 400);
                result.put("msg", "订单已审核，不能重复审核（当前状态：" + formatAuditStatusForMessage(auditStatus) + "）");
                result.put("data", Map.of("pendingId", pendingId, "auditStatus", auditStatus));
                return result;
            }

            String userRequestDataJson = row.get("user_request_data") == null ? null : String.valueOf(row.get("user_request_data"));
            if (!StringUtils.hasText(userRequestDataJson)) {
                result.put("code", 400);
                result.put("msg", "订单数据为空，无法审核");
                result.put("data", null);
                return result;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(userRequestDataJson, Map.class);
            request = normalizeUserRequestDataMap(request);

            // 移除门店权限校验，允许所有门店审核订单
            String gateGroup = extractGroupNameForStoreGate(request);

            String reqTrigger = str(request.get("requestTriggerType"));
            // 身份证触发（私聊单）：不在本系统提交中台/不推小程序侧链路，仅本地标记为已完成（audit_status=3）
            if ("idcard".equalsIgnoreCase(reqTrigger)) {
                java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
                haidianJdbcTemplate.update(
                        "UPDATE mcp_order_create_request_log SET audit_status = 3, audit_time = ?, audit_remark = ? WHERE pending_id = ?",
                        now, auditRemark, pendingId);
                result.put("code", 0);
                result.put("msg", "身份证私聊单已标记为已完成（未提交中台/小程序）");
                result.put("data", Map.of("pendingId", pendingId, "auditStatus", 3, "idcardLocalComplete", true));
                return result;
            }

            Map<String, Object> submitResult = submitOrderToMiddlePlatform(pendingId, request);
            if (submitResult.get("code") instanceof Number && ((Number) submitResult.get("code")).intValue() == 0) {
                java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
                haidianJdbcTemplate.update(
                        "UPDATE mcp_order_create_request_log SET audit_status = 1, audit_time = ?, audit_remark = ? WHERE pending_id = ?",
                        now, auditRemark, pendingId);
                result.put("code", 0);
                result.put("msg", "审核通过，已提交中台");
                result.put("data", submitResult.get("data"));
            } else {
                result.put("code", submitResult.get("code"));
                result.put("msg", "审核通过，但提交中台失败：" + submitResult.get("msg"));
                result.put("data", submitResult.get("data"));
            }
            return result;
        } catch (Exception e) {
            log.error("审核通过失败，pendingId={}", pendingId, e);
            result.put("code", 500);
            result.put("msg", "审核失败：" + e.getMessage());
            result.put("data", null);
            return result;
        }
    }

    @Override
    public Map<String, Object> rejectOrder(String pendingId, String auditRemark) {
        Map<String, Object> result = new HashMap<>();
        if (!StringUtils.hasText(pendingId)) {
            result.put("code", 400);
            result.put("msg", "pendingId 不能为空");
            result.put("data", null);
            return result;
        }
        if (!StringUtils.hasText(auditRemark)) {
            result.put("code", 400);
            result.put("msg", "驳回原因不能为空");
            result.put("data", null);
            return result;
        }
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", null);
            return result;
        }

        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(
                    "SELECT audit_status, user_request_data FROM mcp_order_create_request_log WHERE pending_id = ?",
                    pendingId);
            if (rows.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "未找到对应的待审核订单");
                result.put("data", null);
                return result;
            }
            Integer auditStatus = rows.get(0).get("audit_status") instanceof Number ? ((Number) rows.get(0).get("audit_status")).intValue() : null;
            if (auditStatus != null && auditStatus != 0) {
                result.put("code", 400);
                result.put("msg", "订单已处理，不能驳回（当前状态：" + formatAuditStatusForMessage(auditStatus) + "）");
                result.put("data", Map.of("pendingId", pendingId, "auditStatus", auditStatus));
                return result;
            }
            // 移除门店权限校验，允许所有门店驳回订单

            java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
            int updated = haidianJdbcTemplate.update(
                    "UPDATE mcp_order_create_request_log SET audit_status = 2, audit_time = ?, audit_remark = ? WHERE pending_id = ?",
                    now, auditRemark, pendingId);
            result.put("code", 0);
            result.put("msg", updated > 0 ? "ok" : "更新失败");
            result.put("data", Map.of("pendingId", pendingId, "updated", updated));
            return result;
        } catch (Exception e) {
            log.error("审核驳回失败，pendingId={}", pendingId, e);
            result.put("code", 500);
            result.put("msg", "审核驳回失败：" + e.getMessage());
            result.put("data", null);
            return result;
        }
    }

    private static String sanitizeGroupTokenForSearch(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String t = token.trim();
        if (t.contains("|") || t.contains("%") || t.contains("_") || t.contains("\\")) {
            return null;
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveGroupTokensFromRequest(Map<String, Object> requestData) {
        if (requestData == null) {
            return List.of();
        }
        Object gt = requestData.get("groupTokens");
        if (gt instanceof List<?> list && !list.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && StringUtils.hasText(String.valueOf(o).trim())) {
                    out.add(String.valueOf(o).trim());
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        String groupName = blankToNull(str(requestData.get("groupName")));
        if (!StringUtils.hasText(groupName)) {
            return List.of();
        }
        if (groupNameTokenService != null) {
            return groupNameTokenService.resolveTokens(groupName);
        }
        return List.of(groupName);
    }

    private boolean matchesGroupToken(Map<String, Object> requestData, String tokenQ) {
        if (!StringUtils.hasText(tokenQ)) {
            return true;
        }
        List<String> tokens = resolveGroupTokensFromRequest(requestData);
        for (String t : tokens) {
            if (tokenQ.equals(t)) {
                return true;
            }
        }
        // 兼容老数据：无 groupTokens 时允许在群名称内做包含匹配
        String groupName = blankToNull(str(requestData.get("groupName")));
        return StringUtils.hasText(groupName) && groupName.contains(tokenQ);
    }

    @Override
    public Map<String, Object> getOrderAuditList(String status, String groupToken,
                                                 String pendingId, String patientName, String patientPhone, String patientIdCard, String groupName,
                                                 String storeId,
                                                 String createDateStart, String createDateEnd,
                                                 String createTimeStart, String createTimeEnd,
                                                 String requestTriggerType) {
        Map<String, Object> result = new HashMap<>();
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", new java.util.ArrayList<>());
            return result;
        }

        try {
            String sql = """
                    SELECT 
                        id, pending_id, order_id, request_source, audit_status, audit_time, audit_remark, create_time,
                        user_request_data
                    FROM mcp_order_create_request_log
                    WHERE 1=1
                      AND (audit_status IS NULL OR audit_status <> ?)
                    """;
            java.util.List<Object> params = new java.util.ArrayList<>();
            params.add(AUDIT_STATUS_RAW_INGEST);
            if (StringUtils.hasText(status)) {
                try {
                    int statusInt = Integer.parseInt(status.trim());
                    sql += " AND audit_status = ?";
                    params.add(statusInt);
                } catch (NumberFormatException e) {
                    // 忽略无效的状态值
                }
            }
            LocalDateTime createFrom = parseFlexibleDateTimeStart(createTimeStart, createDateStart);
            LocalDateTime createTo = parseFlexibleDateTimeEnd(createTimeEnd, createDateEnd);
            if (createFrom != null) {
                sql += " AND create_time >= ?";
                params.add(java.sql.Timestamp.valueOf(createFrom));
            }
            if (createTo != null) {
                sql += " AND create_time <= ?";
                params.add(java.sql.Timestamp.valueOf(createTo));
            }
            // pending_id 是表字段，优先下推到 SQL，避免先 LIMIT 再内存过滤导致“统计有、列表无”
            if (StringUtils.hasText(pendingId)) {
                sql += " AND pending_id LIKE ?";
                params.add("%" + pendingId.trim() + "%");
            }
            String triggerNorm = normalizeOrderTriggerTypeText(requestTriggerType, null, null);
            if ("idcard".equals(triggerNorm)) {
                // 只在“身份证 Tab”场景下做粗过滤下推，减少取回行数与 JSON 解析量
                sql += " AND (user_request_data LIKE '%\"requestTriggerType\":\"idcard\"%'"
                        + " OR user_request_data LIKE '%\"requestTriggerType\":\"id_card\"%'"
                        + " OR (user_request_data LIKE '%\"patientIdCard\":\"%' AND user_request_data NOT LIKE '%\"patientPhone\":\"1%'))";
            } else if ("phone".equals(triggerNorm)) {
                sql += " AND (user_request_data LIKE '%\"requestTriggerType\":\"phone\"%'"
                        + " OR user_request_data LIKE '%\"requestTriggerType\":\"mobile\"%'"
                        + " OR user_request_data LIKE '%\"patientPhone\":\"1%')";
            }
            String tokenQ = sanitizeGroupTokenForSearch(groupToken);

            // 全量统计：返回“筛选后总单数”，不受页面列表 LIMIT 影响
            int totalCountFull = computeOrderAuditTotalCountFull(status, tokenQ, pendingId, patientName, patientPhone, patientIdCard, groupName,
                    storeId, createFrom, createTo, requestTriggerType);

            // 列表查询：若存在姓名/手机号/身份证/群名称/分词等筛选，放大 SQL 取数窗口，
            // 避免“先 LIMIT 再内存过滤”导致“筛选结果有值但列表空白”。
            boolean hasDeepFilter = StringUtils.hasText(tokenQ)
                    || StringUtils.hasText(patientName)
                    || StringUtils.hasText(patientPhone)
                    || StringUtils.hasText(patientIdCard)
                    || StringUtils.hasText(groupName)
                    || StringUtils.hasText(storeId);
            sql += " ORDER BY create_time DESC LIMIT ?";
            params.add(hasDeepFilter ? 5000 : 100);

            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(sql, params.toArray());
            java.util.List<Map<String, Object>> orderList = new java.util.ArrayList<>();

            for (Map<String, Object> row : rows) {
                String userRequestDataJson = row.get("user_request_data") == null ? null : String.valueOf(row.get("user_request_data"));
                if (!StringUtils.hasText(userRequestDataJson)) {
                    continue;
                }

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> requestData = objectMapper.readValue(userRequestDataJson, Map.class);
                    requestData = normalizeUserRequestDataMap(requestData);
                    if (!matchesGroupToken(requestData, tokenQ)) {
                        continue;
                    }
                    if (!matchesTextFilter(str(row.get("pending_id")), pendingId)) {
                        continue;
                    }
                    String reqPatientName = str(requestData.get("patientName"));
                    String reqPatientPhone = str(requestData.get("patientPhone"));
                    String reqPatientIdCard = str(requestData.get("patientIdCard"));
                    String reqGroupName = extractGroupNameForStoreGate(requestData);
                    if (!matchesTextFilter(reqPatientName, patientName)
                            || !matchesTextFilter(reqPatientPhone, patientPhone)
                            || !matchesTextFilter(reqPatientIdCard, patientIdCard)
                            || !matchesTextFilter(reqGroupName, groupName)) {
                        continue;
                    }
                    if (!matchesOrderTriggerType(requestData, requestTriggerType)) {
                        continue;
                    }
                    if (!isMcpAuditStoreVisibleForRequest(requestData, reqGroupName)) {
                        continue;
                    }
                    if (!matchesStoreIdFilter(requestData, storeId)) {
                        continue;
                    }

                    // 提取患者信息
                    String patientNameValue = requestData.get("patientName") == null ? null : String.valueOf(requestData.get("patientName"));
                    String patientPhoneValue = requestData.get("patientPhone") == null ? null : String.valueOf(requestData.get("patientPhone"));
                    String patientIdCardValue = requestData.get("patientIdCard") == null ? null : String.valueOf(requestData.get("patientIdCard"));
                    // 列表页不再做“患者补齐查询”（应在 core_order_create 接入时补齐并落库），以避免列表接口变慢。
                    String patientEducation = requestData.get("patientEducation") == null ? null : String.valueOf(requestData.get("patientEducation"));
                    String reqGroupNameRaw = requestData.get("groupName") == null ? "" : String.valueOf(requestData.get("groupName"));
                    String reqStoreId = extractStoreIdForGate(requestData, reqGroupNameRaw);
                    String reqOrderRemark = requestData.get("orderRemark") == null ? "" : String.valueOf(requestData.get("orderRemark"));
                    String reqGroupNickname = requestData.get("userGroupNickname") == null ? "" : String.valueOf(requestData.get("userGroupNickname"));
                    String reqDeliveryHospital = requestData.get("deliveryHospital") == null ? "" : String.valueOf(requestData.get("deliveryHospital"));
                    String reqY3ImageInfo = requestData.get("y3ImageInfo") == null ? "" : String.valueOf(requestData.get("y3ImageInfo"));
                    String reqStoreIds = StringUtils.hasText(reqStoreId) ? reqStoreId : storeIdsCsvFromGroupNameSuffix(reqGroupNameRaw);
                    String reqTriggerType = requestData.get("requestTriggerType") == null ? "" : String.valueOf(requestData.get("requestTriggerType"));

                    // 提取 items：有药品则一行一药；无药品也至少返回一行便于展示患者信息
                    Object itemsObj = requestData.get("items");
                    if (itemsObj instanceof List<?> rawItems && !rawItems.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                        for (Map<String, Object> item : items) {
                            Map<String, Object> orderItem = new LinkedHashMap<>();
                            orderItem.put("id", row.get("id"));
                            orderItem.put("pending_id", row.get("pending_id"));
                            orderItem.put("order_id", row.get("order_id"));
                            orderItem.put("request_source", row.get("request_source"));
                            orderItem.put("audit_status", row.get("audit_status"));
                            orderItem.put("audit_time", row.get("audit_time"));
                            orderItem.put("audit_remark", row.get("audit_remark"));
                            orderItem.put("create_time", row.get("create_time"));
                            orderItem.put("patient_name", patientNameValue);
                            orderItem.put("patient_phone", patientPhoneValue);
                            orderItem.put("patient_id_card", patientIdCardValue);
                            orderItem.put("patient_education", patientEducation);
                            orderItem.put("group_name", reqGroupNameRaw);
                            orderItem.put("store_ids", reqStoreIds);
                            orderItem.put("request_trigger_type", reqTriggerType);
                            orderItem.put("order_remark", reqOrderRemark);
                            orderItem.put("user_group_nickname", reqGroupNickname);
                            orderItem.put("delivery_hospital", reqDeliveryHospital);
                            orderItem.put("y3_image_info", reqY3ImageInfo);
                            orderItem.put("group_tokens", requestData.get("groupTokens"));
                            orderItem.put("drug_name", item.get("drugName"));
                            orderItem.put("spec", item.get("spec"));
                            orderItem.put("qty", item.get("qty"));
                            orderItem.put("ware_id", item.get("wareId"));
                            orderItem.put("bar_code", item.get("barCode"));
                            orderList.add(orderItem);
                        }
                    } else {
                        Map<String, Object> orderItem = new LinkedHashMap<>();
                        orderItem.put("id", row.get("id"));
                        orderItem.put("pending_id", row.get("pending_id"));
                        orderItem.put("order_id", row.get("order_id"));
                        orderItem.put("request_source", row.get("request_source"));
                        orderItem.put("audit_status", row.get("audit_status"));
                        orderItem.put("audit_time", row.get("audit_time"));
                        orderItem.put("audit_remark", row.get("audit_remark"));
                        orderItem.put("create_time", row.get("create_time"));
                        orderItem.put("patient_name", patientNameValue);
                        orderItem.put("patient_phone", patientPhoneValue);
                        orderItem.put("patient_id_card", patientIdCardValue);
                        orderItem.put("patient_education", patientEducation);
                        orderItem.put("group_name", reqGroupNameRaw);
                        orderItem.put("store_ids", reqStoreIds);
                        orderItem.put("request_trigger_type", reqTriggerType);
                        orderItem.put("order_remark", reqOrderRemark);
                        orderItem.put("user_group_nickname", reqGroupNickname);
                        orderItem.put("delivery_hospital", reqDeliveryHospital);
                        orderItem.put("y3_image_info", reqY3ImageInfo);
                        orderItem.put("group_tokens", requestData.get("groupTokens"));
                        orderList.add(orderItem);
                    }
                    if (tokenQ != null && orderList.size() >= 100) {
                        break;
                    }
                } catch (Exception e) {
                    log.warn("解析订单JSON失败，id={}", row.get("id"), e);
                    // 即使解析失败，也返回基本信息
                    Map<String, Object> orderItem = new LinkedHashMap<>();
                    orderItem.put("id", row.get("id"));
                    orderItem.put("pending_id", row.get("pending_id"));
                    orderItem.put("audit_status", row.get("audit_status"));
                    orderItem.put("create_time", row.get("create_time"));
                    orderList.add(orderItem);
                }
            }

            java.util.Set<String> uniqPending = new java.util.HashSet<>();
            for (Map<String, Object> o : orderList) {
                String pid = str(o.get("pending_id"));
                if (StringUtils.hasText(pid)) {
                    uniqPending.add(pid);
                }
            }
            result.put("code", 0);
            result.put("msg", "ok");
            result.put("data", orderList);
            // totalCount：全量统计的“单数”，用于页面右上角展示；uniqPending 仅是当前返回行的去重
            result.put("totalCount", totalCountFull);
            result.put("totalRows", orderList.size());
            return result;
        } catch (Exception e) {
            log.error("获取订单审核列表失败", e);
            result.put("code", 500);
            result.put("msg", "获取订单列表失败：" + e.getMessage());
            result.put("data", new java.util.ArrayList<>());
            return result;
        }
    }

    // 移除列表页补齐查询：患者信息应在 core_order_create 接入阶段补齐并保存到 user_request_data。

    /**
     * 订单审核列表全量统计（按筛选条件匹配后的 pending_id 去重计数）。
     * 说明：groupToken、patientName 等在 user_request_data JSON 中，无法完全用 SQL count 直接统计；
     * 这里分两种：
     * - 无 JSON 过滤条件时：走 COUNT(DISTINCT pending_id)
     * - 含 JSON 过滤条件时：分页扫描并按现有 matchesX 逻辑过滤后去重
     */
    private int computeOrderAuditTotalCountFull(String status, String tokenQ,
                                                String pendingId, String patientName, String patientPhone, String patientIdCard, String groupName,
                                                String storeId,
                                                LocalDateTime createFrom, LocalDateTime createTo,
                                                String requestTriggerType) {
        if (haidianJdbcTemplate == null) {
            return 0;
        }
        String triggerNorm = normalizeOrderTriggerTypeText(requestTriggerType, null, null);
        boolean hasJsonFiltersExceptTrigger =
                StringUtils.hasText(tokenQ)
                        || StringUtils.hasText(pendingId)
                        || StringUtils.hasText(patientName)
                        || StringUtils.hasText(patientPhone)
                        || StringUtils.hasText(patientIdCard)
                        || StringUtils.hasText(groupName)
                        || StringUtils.hasText(storeId);
        boolean hasJsonFilters = hasJsonFiltersExceptTrigger || StringUtils.hasText(triggerNorm);

        // 快路径：仅按 requestTriggerType 过滤时，用 LIKE 直接统计，避免全量 JSON 扫描
        if (!hasJsonFiltersExceptTrigger && StringUtils.hasText(triggerNorm)) {
            String countSql = """
                    SELECT COUNT(DISTINCT pending_id)
                    FROM mcp_order_create_request_log
                    WHERE 1=1
                      AND (audit_status IS NULL OR audit_status <> ?)
                    """;
            java.util.List<Object> countParams = new java.util.ArrayList<>();
            countParams.add(AUDIT_STATUS_RAW_INGEST);
            if (StringUtils.hasText(status)) {
                try {
                    int statusInt = Integer.parseInt(status.trim());
                    countSql += " AND audit_status = ?";
                    countParams.add(statusInt);
                } catch (NumberFormatException ignored) {
                }
            }
            if (createFrom != null) {
                countSql += " AND create_time >= ?";
                countParams.add(java.sql.Timestamp.valueOf(createFrom));
            }
            if (createTo != null) {
                countSql += " AND create_time <= ?";
                countParams.add(java.sql.Timestamp.valueOf(createTo));
            }
            if ("idcard".equals(triggerNorm)) {
                // 兼容：requestTriggerType 字段缺失时，仍可能只有 patientIdCard（且 patientPhone 为空）
                countSql += " AND (user_request_data LIKE '%\"requestTriggerType\":\"idcard\"%'"
                        + " OR user_request_data LIKE '%\"requestTriggerType\":\"id_card\"%'"
                        + " OR (user_request_data LIKE '%\"patientIdCard\":\"%' AND user_request_data NOT LIKE '%\"patientPhone\":\"1%'))";
            } else if ("phone".equals(triggerNorm)) {
                countSql += " AND (user_request_data LIKE '%\"requestTriggerType\":\"phone\"%'"
                        + " OR user_request_data LIKE '%\"requestTriggerType\":\"mobile\"%'"
                        + " OR user_request_data LIKE '%\"patientPhone\":\"1%')";
            }
            try {
                Integer n = haidianJdbcTemplate.queryForObject(countSql, countParams.toArray(), Integer.class);
                return n == null ? 0 : n;
            } catch (Exception e) {
                log.warn("订单列表按 triggerType LIKE 统计失败，回退扫描 triggerType={}", triggerNorm, e);
                // fallthrough
            }
        }

        if (!hasJsonFilters) {
            String countSql = """
                    SELECT COUNT(DISTINCT pending_id)
                    FROM mcp_order_create_request_log
                    WHERE 1=1
                      AND (audit_status IS NULL OR audit_status <> ?)
                    """;
            java.util.List<Object> countParams = new java.util.ArrayList<>();
            countParams.add(AUDIT_STATUS_RAW_INGEST);
            if (StringUtils.hasText(status)) {
                try {
                    int statusInt = Integer.parseInt(status.trim());
                    countSql += " AND audit_status = ?";
                    countParams.add(statusInt);
                } catch (NumberFormatException ignored) {
                }
            }
            if (createFrom != null) {
                countSql += " AND create_time >= ?";
                countParams.add(java.sql.Timestamp.valueOf(createFrom));
            }
            if (createTo != null) {
                countSql += " AND create_time <= ?";
                countParams.add(java.sql.Timestamp.valueOf(createTo));
            }
            try {
                Integer n = haidianJdbcTemplate.queryForObject(countSql, countParams.toArray(), Integer.class);
                return n == null ? 0 : n;
            } catch (Exception e) {
                log.warn("订单列表 COUNT(DISTINCT pending_id) 统计失败，回退扫描", e);
                // fallthrough
            }
        }

        final int pageSize = 1000;
        int offset = 0;
        java.util.Set<String> uniq = new java.util.HashSet<>();

        String baseSql = """
                SELECT pending_id, user_request_data
                FROM mcp_order_create_request_log
                WHERE 1=1
                  AND (audit_status IS NULL OR audit_status <> ?)
                """;
        java.util.List<Object> baseParams = new java.util.ArrayList<>();
        baseParams.add(AUDIT_STATUS_RAW_INGEST);
        if (StringUtils.hasText(status)) {
            try {
                int statusInt = Integer.parseInt(status.trim());
                baseSql += " AND audit_status = ?";
                baseParams.add(statusInt);
            } catch (NumberFormatException ignored) {
            }
        }
        if (createFrom != null) {
            baseSql += " AND create_time >= ?";
            baseParams.add(java.sql.Timestamp.valueOf(createFrom));
        }
        if (createTo != null) {
            baseSql += " AND create_time <= ?";
            baseParams.add(java.sql.Timestamp.valueOf(createTo));
        }
        if ("idcard".equals(triggerNorm)) {
            baseSql += " AND (user_request_data LIKE '%\"requestTriggerType\":\"idcard\"%'"
                    + " OR user_request_data LIKE '%\"requestTriggerType\":\"id_card\"%'"
                    + " OR (user_request_data LIKE '%\"patientIdCard\":\"%' AND user_request_data NOT LIKE '%\"patientPhone\":\"1%'))";
        } else if ("phone".equals(triggerNorm)) {
            baseSql += " AND (user_request_data LIKE '%\"requestTriggerType\":\"phone\"%'"
                    + " OR user_request_data LIKE '%\"requestTriggerType\":\"mobile\"%'"
                    + " OR user_request_data LIKE '%\"patientPhone\":\"1%')";
        }
        baseSql += " ORDER BY create_time DESC LIMIT ? OFFSET ?";

        while (true) {
            java.util.List<Object> p = new java.util.ArrayList<>(baseParams);
            p.add(pageSize);
            p.add(offset);
            List<Map<String, Object>> batch;
            try {
                batch = haidianJdbcTemplate.queryForList(baseSql, p.toArray());
            } catch (Exception e) {
                log.warn("订单列表全量统计分页扫描失败 offset={}", offset, e);
                break;
            }
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : batch) {
                String pid = str(row.get("pending_id"));
                if (!StringUtils.hasText(pid)) {
                    continue;
                }
                if (!matchesTextFilter(pid, pendingId)) {
                    continue;
                }
                String json = row.get("user_request_data") == null ? null : String.valueOf(row.get("user_request_data"));
                if (!StringUtils.hasText(json)) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> requestData = objectMapper.readValue(json, Map.class);
                    requestData = normalizeUserRequestDataMap(requestData);
                    if (!matchesGroupToken(requestData, tokenQ)) {
                        continue;
                    }
                    String reqPatientName = str(requestData.get("patientName"));
                    String reqPatientPhone = str(requestData.get("patientPhone"));
                    String reqPatientIdCard = str(requestData.get("patientIdCard"));
                    String reqGroupName = extractGroupNameForStoreGate(requestData);
                    if (!matchesTextFilter(reqPatientName, patientName)
                            || !matchesTextFilter(reqPatientPhone, patientPhone)
                            || !matchesTextFilter(reqPatientIdCard, patientIdCard)
                            || !matchesTextFilter(reqGroupName, groupName)) {
                        continue;
                    }
                    if (!matchesOrderTriggerType(requestData, requestTriggerType)) {
                        continue;
                    }
                    if (!isMcpAuditStoreVisibleForRequest(requestData, reqGroupName)) {
                        continue;
                    }
                    if (!matchesStoreIdFilter(requestData, storeId)) {
                        continue;
                    }
                    uniq.add(pid);
                } catch (Exception ignored) {
                    // JSON 异常：统计口径以“无法解析则不计入筛选命中”为准，避免把未知数据算进来
                }
            }
            offset += pageSize;
            // 兜底：避免异常数据导致无限扫描（一般不会触发）
            if (offset > 500_000) {
                log.warn("订单列表全量统计扫描达到上限 offset={}，提前结束", offset);
                break;
            }
        }
        return uniq.size();
    }

    @Override
    public Map<String, Object> queryOrdersByGroupToken(String groupToken) {
        if (sanitizeGroupTokenForSearch(groupToken) == null) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 400);
            r.put("msg", "groupToken 不能为空，且不能包含 | % _ \\ 等字符");
            r.put("data", List.of());
            return r;
        }
        return getOrderAuditList("0", groupToken, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static boolean matchesTextFilter(String source, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        if (!StringUtils.hasText(source)) {
            return false;
        }
        return source.toLowerCase().contains(keyword.trim().toLowerCase());
    }

    private static String normalizeOrderTriggerTypeText(String rawType, String patientPhone, String patientIdCard) {
        if (StringUtils.hasText(rawType)) {
            String t = rawType.trim().toLowerCase();
            if ("idcard".equals(t) || "id_card".equals(t) || "id-card".equals(t) || "身份证".equals(t)) {
                return "idcard";
            }
            if ("phone".equals(t) || "mobile".equals(t) || "手机号".equals(t) || "手机".equals(t)) {
                return "phone";
            }
        }
        if (StringUtils.hasText(patientIdCard) && !StringUtils.hasText(patientPhone)) {
            return "idcard";
        }
        return "phone";
    }

    private static String normalizedOrderTriggerTypeFromRequest(Map<String, Object> requestData) {
        String rawType = requestData == null ? null : firstNonBlank(
                str(requestData.get("requestTriggerType")),
                str(requestData.get("triggerType")),
                str(requestData.get("triggerBy")),
                str(requestData.get("requestSourceType"))
        );
        String patientPhone = requestData == null ? null : str(requestData.get("patientPhone"));
        String patientIdCard = requestData == null ? null : str(requestData.get("patientIdCard"));
        return normalizeOrderTriggerTypeText(rawType, patientPhone, patientIdCard);
    }

    private static boolean matchesOrderTriggerType(Map<String, Object> requestData, String expectedType) {
        if (!StringUtils.hasText(expectedType)) {
            return true;
        }
        String expected = normalizeOrderTriggerTypeText(expectedType, null, null);
        String actual = normalizedOrderTriggerTypeFromRequest(requestData);
        return expected.equals(actual);
    }

    private static LocalDateTime parseFlexibleDateTimeStart(String dateTimeText, String dateText) {
        if (StringUtils.hasText(dateTimeText)) {
            try {
                return LocalDateTime.parse(dateTimeText.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
            } catch (DateTimeParseException ignored) {
            }
            try {
                return LocalDateTime.parse(dateTimeText.trim());
            } catch (DateTimeParseException ignored) {
            }
        }
        if (StringUtils.hasText(dateText)) {
            try {
                return LocalDate.parse(dateText.trim()).atStartOfDay();
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static LocalDateTime parseFlexibleDateTimeEnd(String dateTimeText, String dateText) {
        if (StringUtils.hasText(dateTimeText)) {
            try {
                return LocalDateTime.parse(dateTimeText.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
            } catch (DateTimeParseException ignored) {
            }
            try {
                return LocalDateTime.parse(dateTimeText.trim());
            } catch (DateTimeParseException ignored) {
            }
        }
        if (StringUtils.hasText(dateText)) {
            try {
                return LocalDate.parse(dateText.trim()).plusDays(1).atStartOfDay().minusNanos(1);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    @Override
    public Map<String, Object> listChatGroupConfigs() {
        Map<String, Object> result = new HashMap<>();
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", List.of());
            return result;
        }
        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(
                    "SELECT id, group_name, chat_id, segment_words, store_code, config_remark, create_time, update_time "
                            + "FROM mcp_chat_group_config ORDER BY id DESC");
            result.put("code", 0);
            result.put("msg", "ok");
            result.put("data", rows != null ? rows : List.of());
            return result;
        } catch (Exception e) {
            log.error("查询群配置失败（请确认已执行 db/mcp_chat_group_config.sql）", e);
            result.put("code", 500);
            result.put("msg", "查询失败：" + e.getMessage());
            result.put("data", List.of());
            return result;
        }
    }

    @Override
    public Map<String, Object> listGroupTokenOptions() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> cached = groupTokenOptionsCachedList;
        if (cached != null && now < groupTokenOptionsCacheExpiresAtMs) {
            return wrapGroupTokenOptionsData(new ArrayList<>(cached));
        }
        synchronized (groupTokenOptionsCacheLock) {
            now = System.currentTimeMillis();
            cached = groupTokenOptionsCachedList;
            if (cached != null && now < groupTokenOptionsCacheExpiresAtMs) {
                return wrapGroupTokenOptionsData(new ArrayList<>(cached));
            }
            Map<String, Object> fresh = listGroupTokenOptionsFresh();
            if (fresh.get("code") instanceof Number n && n.intValue() == 0) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) fresh.get("data");
                if (data != null) {
                    groupTokenOptionsCachedList = List.copyOf(data);
                    groupTokenOptionsCacheExpiresAtMs = System.currentTimeMillis() + GROUP_TOKEN_OPTIONS_CACHE_TTL_MS;
                }
                return fresh;
            }
            // 降级：海典库连接异常或查询失败时，优先返回历史缓存，避免页面/接口被 DB 拖死。
            if (cached != null && !cached.isEmpty()) {
                return wrapGroupTokenOptionsDataWithMsg(new ArrayList<>(cached),
                        "海典库暂不可用，已返回缓存分词选项");
            }
            return wrapGroupTokenOptionsDataWithMsg(List.of(),
                    "海典库暂不可用，分词选项已降级为空列表");
        }
    }

    private static Map<String, Object> wrapGroupTokenOptionsData(List<Map<String, Object>> data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("msg", "ok");
        result.put("data", data);
        return result;
    }

    private static Map<String, Object> wrapGroupTokenOptionsDataWithMsg(List<Map<String, Object>> data, String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("msg", StringUtils.hasText(msg) ? msg : "ok");
        result.put("data", data != null ? data : List.of());
        return result;
    }

    /**
     * 从落库冗余字段 {@code group_tokens_search}（|词1|词2|）统计分词，避免解析整段 user_request_data。
     */
    private void addUsageFromGroupTokensSearchColumn(Map<String, Integer> counter, String pipe) {
        if (!StringUtils.hasText(pipe)) {
            return;
        }
        for (String seg : pipe.split("\\|", -1)) {
            String t = seg.trim();
            if (t.isEmpty() || t.length() > 8) {
                continue;
            }
            counter.put(t, counter.getOrDefault(t, 0) + 1);
        }
    }

    private void addUsageFromGroupTokensJsonColumn(Map<String, Integer> counter, String json) {
        if (!StringUtils.hasText(json)) {
            return;
        }
        String trimmed = json.trim();
        if ("[]".equals(trimmed)) {
            return;
        }
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) {
                return;
            }
            for (JsonNode n : arr) {
                if (n != null && n.isTextual() && StringUtils.hasText(n.asText())) {
                    String token = n.asText().trim();
                    if (token.length() > 8) {
                        continue;
                    }
                    counter.put(token, counter.getOrDefault(token, 0) + 1);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void mergeConfigSegmentWords(Map<String, Integer> counter) {
        List<Map<String, Object>> cfgRows = haidianJdbcTemplate.queryForList(
                "SELECT segment_words FROM mcp_chat_group_config");
        for (Map<String, Object> row : cfgRows) {
            String seg = row.get("segment_words") == null ? null : String.valueOf(row.get("segment_words"));
            if (!StringUtils.hasText(seg)) {
                continue;
            }
            try {
                JsonNode arr = objectMapper.readTree(seg);
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        if (n != null && n.isTextual() && StringUtils.hasText(n.asText())) {
                            String token = n.asText().trim();
                            counter.putIfAbsent(token, 0);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void addUsageFromUserRequestDataJson(Map<String, Integer> counter, String req) {
        if (!StringUtils.hasText(req)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> requestData = objectMapper.readValue(req, Map.class);
            requestData = normalizeUserRequestDataMap(requestData);
            List<String> tokens = resolveGroupTokensFromRequest(requestData);
            for (String token : tokens) {
                if (!StringUtils.hasText(token) || token.trim().length() > 8) {
                    continue;
                }
                String t = token.trim();
                counter.put(t, counter.getOrDefault(t, 0) + 1);
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> listGroupTokenOptionsFresh() {
        Map<String, Object> result = new HashMap<>();
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", List.of());
            return result;
        }
        try {
            Map<String, Integer> counter = new HashMap<>();
            mergeConfigSegmentWords(counter);

            if (hasOrderGroupColumns()) {
                List<Map<String, Object>> lightRows = haidianJdbcTemplate.queryForList(
                        """
                                SELECT group_tokens_search, group_tokens_json
                                FROM mcp_order_create_request_log
                                WHERE (audit_status IS NULL OR audit_status <> ?)
                                ORDER BY create_time DESC
                                LIMIT
                                """
                                + GROUP_TOKEN_OPTIONS_FAST_SCAN_LIMIT,
                        AUDIT_STATUS_RAW_INGEST);
                for (Map<String, Object> row : lightRows) {
                    String search = row.get("group_tokens_search") == null ? null : String.valueOf(row.get("group_tokens_search"));
                    if (StringUtils.hasText(search)) {
                        addUsageFromGroupTokensSearchColumn(counter, search);
                    } else {
                        String gtj = row.get("group_tokens_json") == null ? null : String.valueOf(row.get("group_tokens_json"));
                        addUsageFromGroupTokensJsonColumn(counter, gtj);
                    }
                }

                List<Map<String, Object>> legacyRows = haidianJdbcTemplate.queryForList(
                        """
                                SELECT user_request_data
                                FROM mcp_order_create_request_log
                                WHERE (audit_status IS NULL OR audit_status <> ?)
                                  AND (group_tokens_search IS NULL OR group_tokens_search = '')
                                  AND (group_tokens_json IS NULL OR TRIM(group_tokens_json) = '' OR TRIM(group_tokens_json) = '[]')
                                ORDER BY create_time DESC
                                LIMIT
                                """
                                + GROUP_TOKEN_OPTIONS_LEGACY_JSON_LIMIT,
                        AUDIT_STATUS_RAW_INGEST);
                for (Map<String, Object> row : legacyRows) {
                    String req = row.get("user_request_data") == null ? null : String.valueOf(row.get("user_request_data"));
                    addUsageFromUserRequestDataJson(counter, req);
                }
            } else {
                List<Map<String, Object>> logRows = haidianJdbcTemplate.queryForList(
                        """
                                SELECT user_request_data
                                FROM mcp_order_create_request_log
                                WHERE (audit_status IS NULL OR audit_status <> ?)
                                ORDER BY create_time DESC
                                LIMIT
                                """
                                + GROUP_TOKEN_OPTIONS_FULL_JSON_LIMIT,
                        AUDIT_STATUS_RAW_INGEST);
                for (Map<String, Object> row : logRows) {
                    String req = row.get("user_request_data") == null ? null : String.valueOf(row.get("user_request_data"));
                    addUsageFromUserRequestDataJson(counter, req);
                }
            }

            List<Map<String, Object>> data = new ArrayList<>();
            for (Map.Entry<String, Integer> e : counter.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("token", e.getKey());
                item.put("usageCount", e.getValue());
                data.add(item);
            }
            data.sort(Comparator
                    .comparing((Map<String, Object> m) -> ((Number) m.get("usageCount")).intValue()).reversed()
                    .thenComparing(m -> String.valueOf(m.get("token"))));

            result.put("code", 0);
            result.put("msg", "ok");
            result.put("data", data);
            return result;
        } catch (org.springframework.jdbc.CannotGetJdbcConnectionException e) {
            // 单接口降级：DB 连接失败时返回空列表，避免前端因 500 阻断正常使用。
            log.warn("查询群分词选项失败（海典库连接不可用，已降级为空列表）");
            result.put("code", 0);
            result.put("msg", "海典库连接失败，分词选项已降级为空列表");
            result.put("data", List.of());
            return result;
        } catch (org.springframework.dao.TransientDataAccessResourceException e) {
            // 瞬时资源异常：常见于网络抖动/连接被服务端回收，读包 EOF。这里降级避免刷 ERROR 并影响前端。
            log.warn("查询群分词选项失败（海典库连接中断/网络抖动，已降级为空列表）：{}", e.getMessage());
            result.put("code", 0);
            result.put("msg", "海典库连接中断，分词选项已降级为空列表");
            result.put("data", List.of());
            return result;
        } catch (org.springframework.dao.DataAccessResourceFailureException e) {
            log.warn("查询群分词选项失败（海典库资源不可用，已降级为空列表）：{}", e.getMessage());
            result.put("code", 0);
            result.put("msg", "海典库资源不可用，分词选项已降级为空列表");
            result.put("data", List.of());
            return result;
        } catch (Exception e) {
            log.error("查询群分词选项失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败：" + e.getMessage());
            result.put("data", List.of());
            return result;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveChatGroupConfig(Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", null);
            return result;
        }
        if (body == null || body.isEmpty()) {
            result.put("code", 400);
            result.put("msg", "body 不能为空");
            result.put("data", null);
            return result;
        }
        String groupName = firstNonBlank(
                blankToNull(str(body.get("groupName"))),
                blankToNull(getIgnoreCase(body, "group_name")));
        if (!StringUtils.hasText(groupName)) {
            result.put("code", 400);
            result.put("msg", "groupName 不能为空");
            result.put("data", null);
            return result;
        }
        Object segObj = body.get("segmentWords");
        if (segObj == null) {
            segObj = body.get("segment_words");
        }
        List<String> segments = new ArrayList<>();
        if (segObj instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && StringUtils.hasText(String.valueOf(o).trim())) {
                    segments.add(String.valueOf(o).trim());
                }
            }
        } else if (segObj instanceof String s && StringUtils.hasText(s)) {
            try {
                JsonNode arr = objectMapper.readTree(s);
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        if (n != null && n.isTextual() && StringUtils.hasText(n.asText())) {
                            segments.add(n.asText().trim());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (segments.isEmpty()) {
            result.put("code", 400);
            result.put("msg", "segmentWords 须为非空数组，如 [\"一丰\",\"恒瑞\"]");
            result.put("data", null);
            return result;
        }
        String segmentJson;
        try {
            segmentJson = objectMapper.writeValueAsString(segments);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "segmentWords 序列化失败");
            result.put("data", null);
            return result;
        }
        String chatId = firstNonBlank(blankToNull(str(body.get("chatId"))), blankToNull(getIgnoreCase(body, "chat_id")));
        String storeCode = firstNonBlank(blankToNull(str(body.get("storeCode"))), blankToNull(getIgnoreCase(body, "store_code")));
        String cfgRemark = firstNonBlank(blankToNull(str(body.get("configRemark"))), blankToNull(getIgnoreCase(body, "config_remark")));
        Long id = null;
        Object idObj = body.get("id");
        if (idObj instanceof Number) {
            id = ((Number) idObj).longValue();
        } else if (idObj != null) {
            try {
                id = Long.parseLong(String.valueOf(idObj).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            if (id != null && id > 0) {
                int u = haidianJdbcTemplate.update(
                        """
                                UPDATE mcp_chat_group_config SET group_name = ?, chat_id = ?, segment_words = ?,
                                store_code = ?, config_remark = ? WHERE id = ?
                                """,
                        groupName.trim(),
                        StringUtils.hasText(chatId) ? chatId.trim() : null,
                        segmentJson,
                        StringUtils.hasText(storeCode) ? storeCode.trim() : null,
                        StringUtils.hasText(cfgRemark) ? cfgRemark.trim() : null,
                        id);
                result.put("code", 0);
                result.put("msg", u > 0 ? "ok" : "未找到记录");
                result.put("data", Map.of("id", id));
            } else {
                haidianJdbcTemplate.update(
                        """
                                INSERT INTO mcp_chat_group_config (group_name, chat_id, segment_words, store_code, config_remark)
                                VALUES (?, ?, ?, ?, ?)
                                """,
                        groupName.trim(),
                        StringUtils.hasText(chatId) ? chatId.trim() : null,
                        segmentJson,
                        StringUtils.hasText(storeCode) ? storeCode.trim() : null,
                        StringUtils.hasText(cfgRemark) ? cfgRemark.trim() : null);
                Long newId = haidianJdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                result.put("code", 0);
                result.put("msg", "ok");
                result.put("data", Map.of("id", newId != null ? newId : 0L));
            }
            return result;
        } catch (Exception e) {
            log.error("保存群配置失败", e);
            result.put("code", 500);
            result.put("msg", "保存失败：" + e.getMessage());
            result.put("data", null);
            return result;
        }
    }

    @Override
    public Map<String, Object> deleteChatGroupConfig(Long id) {
        Map<String, Object> result = new HashMap<>();
        if (id == null || id <= 0) {
            result.put("code", 400);
            result.put("msg", "id 无效");
            result.put("data", null);
            return result;
        }
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", null);
            return result;
        }
        try {
            int n = haidianJdbcTemplate.update("DELETE FROM mcp_chat_group_config WHERE id = ?", id);
            result.put("code", 0);
            result.put("msg", n > 0 ? "ok" : "未找到记录");
            result.put("data", Map.of("deleted", n));
            return result;
        } catch (Exception e) {
            log.error("删除群配置失败 id={}", id, e);
            result.put("code", 500);
            result.put("msg", "删除失败：" + e.getMessage());
            result.put("data", null);
            return result;
        }
    }

    @Override
    public Map<String, Object> updateOrderRequestData(String pendingId, Map<String, Object> userRequestData) {
        Map<String, Object> result = new HashMap<>();
        if (!StringUtils.hasText(pendingId)) {
            result.put("code", 400);
            result.put("msg", "pendingId 不能为空");
            result.put("data", null);
            return result;
        }
        if (userRequestData == null || userRequestData.isEmpty()) {
            result.put("code", 400);
            result.put("msg", "userRequestData 不能为空");
            result.put("data", null);
            return result;
        }
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", null);
            return result;
        }

        try {
            List<Map<String, Object>> existingRows = haidianJdbcTemplate.queryForList(
                    "SELECT user_request_data, audit_status FROM mcp_order_create_request_log WHERE pending_id = ? LIMIT 1",
                    pendingId);
            if (existingRows == null || existingRows.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "未找到对应订单");
                result.put("data", Map.of("pendingId", pendingId));
                return result;
            }
            Map<String, Object> existingRow = existingRows.get(0);
            Integer auditStatus = null;
            Object stObj = existingRow.get("audit_status");
            if (stObj instanceof Number n) {
                auditStatus = n.intValue();
            } else if (stObj != null) {
                try {
                    auditStatus = Integer.parseInt(String.valueOf(stObj).trim());
                } catch (Exception ignored) {
                }
            }
            if (auditStatus != null && auditStatus != 0) {
                result.put("code", 409);
                result.put("msg", "该订单已处理，不能再编辑");
                result.put("data", Map.of("pendingId", pendingId, "auditStatus", auditStatus));
                return result;
            }
            Map<String, Object> existingNorm = null;
            String existingJson = str(existingRow.get("user_request_data"));
            if (StringUtils.hasText(existingJson)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> raw = objectMapper.readValue(existingJson, Map.class);
                    existingNorm = normalizeUserRequestDataMap(raw);
                } catch (Exception ignored) {
                }
            }
            // 移除门店权限校验，允许所有门店编辑订单
            Map<String, Object> normalized = prepareNormalizedOrderPayload(userRequestData);
            // 防止前端篡改群归属，群相关字段以原订单为准
            if (existingNorm != null) {
                normalized.put("groupName", str(existingNorm.get("groupName")));
                // 允许审核页修改备注、群昵称、送货医院、Y3 图等；群名仍以原订单为准
                // requestTriggerType 不再强制沿用旧值：
                // 身份证 Tab 下单前会显式传 idcard，这里必须允许覆盖旧历史值（如 phone），
                // 否则 approveOrder 无法进入“身份证本地完成”分支。
                if (!StringUtils.hasText(str(normalized.get("requestTriggerType")))) {
                    normalized.put("requestTriggerType", str(existingNorm.get("requestTriggerType")));
                }
                Object gt = existingNorm.get("groupTokens");
                if (gt != null) {
                    normalized.put("groupTokens", gt);
                }
            }
            syncOcrProfileFromAuditPatientEdit(pendingId, normalized);
            String requestDataJson = objectMapper.writeValueAsString(normalized);
            int updated = haidianJdbcTemplate.update(
                    "UPDATE mcp_order_create_request_log SET user_request_data = ? WHERE pending_id = ? AND (audit_status IS NULL OR audit_status = 0)",
                    requestDataJson, pendingId);
            if (updated > 0) {
                syncOrderGroupDenormalizedColumns(pendingId, normalized);
            }
            
            result.put("code", 0);
            result.put("msg", updated > 0 ? "ok" : "未找到对应订单");
            result.put("data", Map.of("pendingId", pendingId, "updated", updated));
            return result;
        } catch (Exception e) {
            log.error("更新订单数据失败，pendingId={}", pendingId, e);
            result.put("code", 500);
            result.put("msg", "更新失败：" + e.getMessage());
            result.put("data", null);
            return result;
        }
    }

    @Override
    public Map<String, Object> updateOrderStatus(String orderId, String pendingId, Integer statusCode, String status,
                                                 String invoiceInfo, String callbackData, String receiverName, String completionImagesJson) {
        Map<String, Object> result = new HashMap<>();
        String oid = orderId == null ? "" : orderId.trim();
        String pid = pendingId == null ? "" : pendingId.trim();
        String st = status == null ? "" : status.trim();
        String inv = invoiceInfo == null ? null : invoiceInfo.trim();
        String cb = callbackData == null ? null : callbackData.trim();
        String rn = receiverName == null ? null : receiverName.trim();
        String ci = completionImagesJson == null ? null : completionImagesJson.trim();

        if (!StringUtils.hasText(oid) && !StringUtils.hasText(pid)) {
            result.put("code", 400);
            result.put("msg", "orderId 或 pendingId 至少传一个");
            result.put("data", null);
            return result;
        }

        Integer code = normalizeOrderStatusCode(statusCode, st);
        if (code == null) {
            result.put("code", 400);
            result.put("msg", "statusCode 仅支持：1预下单 2已领单 3配送中 4待上传凭证 5完成 6驳回 7已退单（也可用 status 传中文）");
            result.put("data", Map.of("statusCode", statusCode, "status", st));
            return result;
        }
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化，无法更新订单状态");
            result.put("data", null);
            return result;
        }

        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        int updated;
        String callbackJson = null;
        if (StringUtils.hasText(inv) || StringUtils.hasText(cb)) {
            Map<String, Object> callbackMap = new LinkedHashMap<>();
            if (StringUtils.hasText(inv)) {
                callbackMap.put("invoiceInfo", inv);
            }
            if (StringUtils.hasText(cb)) {
                callbackMap.put("callbackData", cb);
            }
            try {
                callbackJson = objectMapper.writeValueAsString(callbackMap);
            } catch (Exception e) {
                callbackJson = String.valueOf(callbackMap);
            }
        }
        String setSql = "UPDATE mcp_order_create_order_log SET order_status = ?, status_update_time = ?, " +
                "mini_callback_data = COALESCE(?, mini_callback_data), " +
                "receiver_name = COALESCE(?, receiver_name), " +
                "completion_images_json = COALESCE(?, completion_images_json) ";
        if (StringUtils.hasText(oid) && StringUtils.hasText(pid)) {
            updated = haidianJdbcTemplate.update(
                    setSql + "WHERE order_id = ? OR pending_id = ?",
                    code, now, callbackJson, rn, ci, oid, pid
            );
        } else if (StringUtils.hasText(oid)) {
            updated = haidianJdbcTemplate.update(
                    setSql + "WHERE order_id = ?",
                    code, now, callbackJson, rn, ci, oid
            );
        } else {
            updated = haidianJdbcTemplate.update(
                    setSql + "WHERE pending_id = ?",
                    code, now, callbackJson, rn, ci, pid
            );
        }
        result.put("code", 0);
        result.put("msg", updated > 0 ? "ok" : "未找到对应订单记录");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updated", updated);
        data.put("orderId", oid.isEmpty() ? null : oid);
        data.put("pendingId", pid.isEmpty() ? null : pid);
        data.put("statusCode", code);
        data.put("invoiceInfoSaved", StringUtils.hasText(inv));
        data.put("callbackDataSaved", StringUtils.hasText(callbackJson));
        data.put("receiverNameSaved", StringUtils.hasText(rn));
        data.put("completionImagesSaved", StringUtils.hasText(ci));
        result.put("data", data);
        return result;
    }

    private Integer normalizeOrderStatusCode(Integer statusCode, String statusText) {
        if (statusCode != null) {
            if (statusCode >= 1 && statusCode <= 7) {
                return statusCode;
            }
        }
        String s = statusText == null ? "" : statusText.trim();
        if (s.isEmpty()) {
            return null;
        }
        // 支持数字字符串
        if (s.matches("\\d+")) {
            try {
                int v = Integer.parseInt(s);
                return (v >= 1 && v <= 7) ? v : null;
            } catch (Exception ignored) {
            }
        }
        if ("预下单".equals(s)) return 1;
        if ("已领单".equals(s)) return 2;
        if ("配送中".equals(s)) return 3;
        if ("待上传凭证".equals(s)) return 4;
        if ("完成".equals(s)) return 5;
        if ("驳回".equals(s)) return 6;
        if ("已退单".equals(s) || "退单".equals(s)) return 7;
        return null;
    }

    private String buildMiddlePlatformReceiveUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (!StringUtils.hasText(url)) {
            return "";
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String path = "/api/PendingReceiver/Receive";
        // 兼容配置项可能直接写了完整路径：例如 https://xx.com/api/PendingReceiver/Receive
        if (url.endsWith(path)) {
            return url;
        }
        // 避免重复拼接（例如 baseUrl 已包含该 path）
        if (url.contains(path)) {
            return url;
        }
        return url + path;
    }

    /**
     * 建档：写入医保表 ocrsichuanyibao。11 位手机号单独即可建档；无手机号时需姓名+身份证。
     */
    @Override
    public Map<String, Object> createProfile(String name, String idCard, String mobile) {
        Map<String, Object> result = new HashMap<>();
        String nameTrim = name == null ? "" : name.trim();
        String idCardTrim = idCard == null ? "" : idCard.trim();
        String mobileTrim = mobile == null ? "" : mobile.trim().replaceAll("\\s+", "");

        boolean hasMobile = StringUtils.hasText(mobileTrim);
        if (hasMobile && mobileTrim.length() != 11) {
            result.put("code", 400);
            result.put("msg", "手机号须为11位");
            result.put("data", null);
            return result;
        }
        if (!hasMobile) {
            int provided = 0;
            if (StringUtils.hasText(nameTrim)) {
                provided++;
            }
            if (StringUtils.hasText(idCardTrim)) {
                provided++;
            }
            if (provided < 2) {
                result.put("code", 400);
                result.put("msg", "请提供11位手机号，或姓名与身份证至少两项");
                result.put("data", null);
                return result;
            }
        }
        if (StringUtils.hasText(idCardTrim) && idCardTrim.length() != 18) {
            result.put("code", 400);
            result.put("msg", "身份证号须为18位");
            result.put("data", null);
            return result;
        }
        try {
            // ocrsichuanyibao 在不同环境存在多种结构，这里按 information_schema 动态识别列，
            // 再用“最小可用字段集合”写入，避免因列差异导致 bad SQL grammar。
            java.util.Set<String> cols = getTableColumnsLower("ocrsichuanyibao");
            boolean fullSchema = cols.contains("shen_fen_zheng");
            boolean simplifiedSchema = cols.contains("idcard");

            // 先查 ocrsichuanyibao 是否已有该档案：优先按身份证；否则按手机号（与「单手机号可建档」一致）
            List<Map<String, Object>> exist = List.of();
            String duplicateMsg = null;
            if (StringUtils.hasText(idCardTrim)) {
                exist = haidianJdbcTemplate.queryForList(
                        fullSchema
                                ? "SELECT 1 FROM ocrsichuanyibao WHERE shen_fen_zheng = ? LIMIT 1"
                                : "SELECT 1 FROM ocrsichuanyibao WHERE idCard = ? LIMIT 1",
                        idCardTrim);
                duplicateMsg = "该身份证已建档，无需重复提交";
            } else if (StringUtils.hasText(mobileTrim)) {
                String phoneCol = fullSchema ? "lian_xi_dian_hua" : "medicalCardNo";
                if (cols.contains(phoneCol.toLowerCase())) {
                    exist = haidianJdbcTemplate.queryForList(
                            "SELECT 1 FROM ocrsichuanyibao WHERE " + phoneCol + " = ? LIMIT 1",
                            mobileTrim);
                    duplicateMsg = "该手机号已建档，无需重复提交";
                }
            }
            if (exist != null && !exist.isEmpty()) {
                log.info("建档重复：name={}, idCard={}, mobile={}", nameTrim, idCardTrim, mobileTrim);
                result.put("code", 409);
                result.put("msg", duplicateMsg != null ? duplicateMsg : "档案已存在，无需重复提交");
                Map<String, Object> data = new LinkedHashMap<>();
                if (StringUtils.hasText(nameTrim)) data.put("name", nameTrim);
                if (StringUtils.hasText(idCardTrim)) data.put("idCard", idCardTrim);
                if (StringUtils.hasText(mobileTrim)) data.put("mobile", mobileTrim);
                result.put("data", data);
                return result;
            }
            java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());

            if (fullSchema) {
                // 全量结构也只插入“最小字段集合”，避免列缺失/默认值约束问题
                StringBuilder sbCols = new StringBuilder();
                StringBuilder sbVals = new StringBuilder();
                java.util.ArrayList<Object> args = new java.util.ArrayList<>();
                java.util.Map<String, ColumnMeta> requiredNoDefault = getRequiredNoDefaultColumns("ocrsichuanyibao");

                // 注意：部分环境 id 可能非自增；若列存在且非自增，则需要手工填充
                if (cols.contains("id")) {
                    Integer nextId = haidianJdbcTemplate.queryForObject(
                            "SELECT COALESCE(MAX(id), 0) + 1 FROM ocrsichuanyibao",
                            Integer.class);
                    if (nextId == null) {
                        nextId = 1;
                    }
                    appendInsertCol(sbCols, sbVals, "id", args, nextId);
                }
                // 未提供身份证时填空串（NOT NULL 约束下仍可插入）；后续查询建议补齐身份证
                appendInsertCol(sbCols, sbVals, "shen_fen_zheng", args, StringUtils.hasText(idCardTrim) ? idCardTrim : "");
                if (cols.contains("xing_ming")) {
                    appendInsertCol(sbCols, sbVals, "xing_ming", args, StringUtils.hasText(nameTrim) ? nameTrim : "");
                }
                if (cols.contains("lian_xi_dian_hua")) {
                    appendInsertCol(sbCols, sbVals, "lian_xi_dian_hua", args, StringUtils.hasText(mobileTrim) ? mobileTrim : "");
                }
                if (cols.contains("create_time")) {
                    appendInsertCol(sbCols, sbVals, "create_time", args, now);
                }
                if (cols.contains("up_time")) {
                    appendInsertCol(sbCols, sbVals, "up_time", args, now);
                }
                if (cols.contains("remark")) {
                    appendInsertCol(sbCols, sbVals, "remark", args, "MCP建档");
                }
                if (cols.contains("adduser")) {
                    appendInsertCol(sbCols, sbVals, "addUser", args, 0);
                }

                // 自动补齐“NOT NULL 且无默认值”的必填列，避免如 xing_bie 这类字段报错
                java.util.Set<String> already = new java.util.HashSet<>();
                for (String c : sbCols.toString().split(",")) {
                    String cc = c == null ? "" : c.trim();
                    if (!cc.isEmpty()) {
                        already.add(cc.toLowerCase());
                    }
                }
                for (java.util.Map.Entry<String, ColumnMeta> e : requiredNoDefault.entrySet()) {
                    String colLower = e.getKey();
                    if (already.contains(colLower)) {
                        continue;
                    }
                    // 不尝试写入自增字段
                    if ("id".equals(colLower)) {
                        continue;
                    }
                    ColumnMeta meta = e.getValue();
                    Object defVal = defaultValueForRequiredColumn(meta, now);
                    appendInsertCol(sbCols, sbVals, meta.columnName, args, defVal);
                    already.add(colLower);
                }

                if (args.isEmpty()) {
                    throw new IllegalStateException("ocrsichuanyibao 表结构异常：无法识别可写入字段");
                }
                haidianJdbcTemplate.update(
                        "INSERT INTO ocrsichuanyibao (" + sbCols + ") VALUES (" + sbVals + ")",
                        args.toArray()
                );
            } else if (simplifiedSchema) {
                // 简化结构：字段基本为 ocr* 通用字段，尽量补齐 NOT NULL
                java.util.ArrayList<Object> args = new java.util.ArrayList<>();
                StringBuilder sbCols = new StringBuilder();
                StringBuilder sbVals = new StringBuilder();

                // id 通常自增，不显式插入
                if (cols.contains("medicaltype")) appendInsertCol(sbCols, sbVals, "medicalType", args, "建档");
                // 简化结构没有明确手机号列：用 medicalCardNo 临时承载手机号（若存在该列）
                if (cols.contains("medicalcardno")) appendInsertCol(sbCols, sbVals, "medicalCardNo", args, StringUtils.hasText(mobileTrim) ? mobileTrim : "");
                if (cols.contains("patientname")) appendInsertCol(sbCols, sbVals, "patientName", args, StringUtils.hasText(nameTrim) ? nameTrim : "");
                appendInsertCol(sbCols, sbVals, "idCard", args, StringUtils.hasText(idCardTrim) ? idCardTrim : "");
                if (cols.contains("diagnosis")) appendInsertCol(sbCols, sbVals, "diagnosis", args, "");
                if (cols.contains("medicalinstitution")) appendInsertCol(sbCols, sbVals, "medicalInstitution", args, "");
                if (cols.contains("medicaldate")) appendInsertCol(sbCols, sbVals, "medicalDate", args, "");
                if (cols.contains("amount")) appendInsertCol(sbCols, sbVals, "amount", args, java.math.BigDecimal.ZERO);
                if (cols.contains("reimbursementamount")) appendInsertCol(sbCols, sbVals, "reimbursementAmount", args, java.math.BigDecimal.ZERO);
                if (cols.contains("createtime")) appendInsertCol(sbCols, sbVals, "createTime", args, now);
                if (cols.contains("updatetime")) appendInsertCol(sbCols, sbVals, "updateTime", args, now);
                if (cols.contains("status")) appendInsertCol(sbCols, sbVals, "status", args, 1);
                if (cols.contains("remark")) appendInsertCol(sbCols, sbVals, "remark", args, "MCP建档");
                if (cols.contains("adduser")) appendInsertCol(sbCols, sbVals, "addUser", args, "mcp");

                haidianJdbcTemplate.update(
                        "INSERT INTO ocrsichuanyibao (" + sbCols + ") VALUES (" + sbVals + ")",
                        args.toArray()
                );
            } else {
                throw new IllegalStateException("ocrsichuanyibao 表结构不兼容：找不到 shen_fen_zheng 或 idCard 字段");
            }
            result.put("code", 0);
            result.put("msg", "建档成功，可通过手机号或 core_insurance_query 查询");
            Map<String, Object> data = new LinkedHashMap<>();
            if (StringUtils.hasText(nameTrim)) data.put("name", nameTrim);
            if (StringUtils.hasText(idCardTrim)) data.put("idCard", idCardTrim);
            if (StringUtils.hasText(mobileTrim)) data.put("mobile", mobileTrim);
            result.put("data", data);
            return result;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("建档重复，身份证已存在：idCard={}", idCardTrim);
            result.put("code", 409);
            result.put("msg", "该身份证已建档，无需重复提交");
            result.put("data", Map.of("idCard", idCardTrim));
            return result;
        } catch (Exception e) {
            log.warn("建档保存失败，name={}, idCard={}", name, idCardTrim, e);
            result.put("code", 500);
            result.put("msg", "建档保存失败：" + e.getMessage());
            result.put("data", null);
            return result;
        }
    }

    @Override
    public Map<String, Object> createPatient(String name, String phone, String idCard, String gender, Integer age, String address, String remark) {
        Map<String, Object> result = new HashMap<>();
        String nameTrim = name == null ? "" : name.trim();
        String phoneTrim = phone == null ? "" : phone.trim().replaceAll("\\s+", "");
        String idCardTrim = idCard == null ? "" : idCard.trim();
        String genderTrim = gender == null ? "" : gender.trim();
        String addressTrim = address == null ? "" : address.trim();
        String remarkTrim = remark == null ? "" : remark.trim();

        // 验证必填字段
        if (!StringUtils.hasText(nameTrim)) {
            result.put("code", 400);
            result.put("msg", "请输入患者姓名");
            result.put("data", null);
            return result;
        }
        if (!StringUtils.hasText(phoneTrim)) {
            result.put("code", 400);
            result.put("msg", "请输入手机号");
            result.put("data", null);
            return result;
        }
        if (phoneTrim.length() != 11) {
            result.put("code", 400);
            result.put("msg", "手机号须为11位");
            result.put("data", null);
            return result;
        }
        if (StringUtils.hasText(idCardTrim) && idCardTrim.length() != 18) {
            result.put("code", 400);
            result.put("msg", "身份证号须为18位");
            result.put("data", null);
            return result;
        }

        try {
            // 先检查当前连接的数据库名称，确认是否连接到海典库
            List<Map<String, Object>> dbNameResult = haidianJdbcTemplate.queryForList("SELECT DATABASE() AS db_name");
            String currentDbName = dbNameResult != null && !dbNameResult.isEmpty() ? String.valueOf(dbNameResult.get(0).get("db_name")) : "unknown";
            log.info("当前海典数据源连接的数据库: {}", currentDbName);
            
            // 先检查 corecmsuser 表是否存在
            java.util.Set<String> cols = getTableColumnsLower("corecmsuser");
            log.info("corecmsuser 表的列数: {}", cols != null ? cols.size() : 0);
            if (cols == null || cols.isEmpty()) {
                log.warn("corecmsuser 表不存在于数据库: {}", currentDbName);
                result.put("code", 500);
                result.put("msg", "患者信息表（corecmsuser）不存在于数据库 " + currentDbName + "，请联系管理员确认数据库配置");
                result.put("data", null);
                return result;
            }

            // 查找可用的手机号字段（支持多种命名）
            String phoneColumn = null;
            if (cols.contains("phone")) {
                phoneColumn = "phone";
            } else if (cols.contains("mobile")) {
                phoneColumn = "mobile";
            } else if (cols.contains("phone_num")) {
                phoneColumn = "phone_num";
            } else if (cols.contains("mobile_phone")) {
                phoneColumn = "mobile_phone";
            } else if (cols.contains("telephone")) {
                phoneColumn = "telephone";
            } else if (cols.contains("tel")) {
                phoneColumn = "tel";
            }
            
            if (phoneColumn == null) {
                log.warn("corecmsuser 表缺少手机号字段，可用字段: {}", cols);
                result.put("code", 500);
                result.put("msg", "患者信息表缺少手机号字段，请联系管理员确认表结构");
                result.put("data", null);
                return result;
            }
            log.info("检测到手机号字段: {}", phoneColumn);

            // 先检查是否已存在相同手机号的患者
            List<Map<String, Object>> exist = haidianJdbcTemplate.queryForList(
                    "SELECT 1 FROM corecmsuser WHERE " + phoneColumn + " = ? LIMIT 1",
                    phoneTrim);
            if (exist != null && !exist.isEmpty()) {
                log.info("患者已存在：name={}, phone={}", nameTrim, phoneTrim);
                result.put("code", 409);
                result.put("msg", "该手机号已存在患者信息");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("name", nameTrim);
                data.put("phone", phoneTrim);
                result.put("data", data);
                return result;
            }

            java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());

            StringBuilder sbCols = new StringBuilder();
            StringBuilder sbVals = new StringBuilder();
            java.util.ArrayList<Object> args = new java.util.ArrayList<>();

            // 处理 id 字段（如果存在且非自增）
            if (cols.contains("id")) {
                Integer nextId = haidianJdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(id), 0) + 1 FROM corecmsuser",
                        Integer.class);
                if (nextId == null) {
                    nextId = 1;
                }
                appendInsertCol(sbCols, sbVals, "id", args, nextId);
            }

            // 添加基本字段
            if (cols.contains("name")) {
                appendInsertCol(sbCols, sbVals, "name", args, nameTrim);
            }
            // 使用动态检测到的手机号字段
            appendInsertCol(sbCols, sbVals, phoneColumn, args, phoneTrim);
            if (cols.contains("idcard") && StringUtils.hasText(idCardTrim)) {
                appendInsertCol(sbCols, sbVals, "idcard", args, idCardTrim);
            }
            if (cols.contains("id_card") && StringUtils.hasText(idCardTrim)) {
                appendInsertCol(sbCols, sbVals, "id_card", args, idCardTrim);
            }
            if (cols.contains("gender") && StringUtils.hasText(genderTrim)) {
                appendInsertCol(sbCols, sbVals, "gender", args, genderTrim);
            }
            if (cols.contains("sex") && StringUtils.hasText(genderTrim)) {
                // sex 字段通常是整数类型：1=男，0=女
                int sexValue = "男".equals(genderTrim) ? 1 : ("女".equals(genderTrim) ? 0 : 0);
                appendInsertCol(sbCols, sbVals, "sex", args, sexValue);
            }
            if (cols.contains("age") && age != null) {
                appendInsertCol(sbCols, sbVals, "age", args, age);
            }
            if (cols.contains("address") && StringUtils.hasText(addressTrim)) {
                appendInsertCol(sbCols, sbVals, "address", args, addressTrim);
            }
            if (cols.contains("remark") && StringUtils.hasText(remarkTrim)) {
                appendInsertCol(sbCols, sbVals, "remark", args, remarkTrim);
            }
            if (cols.contains("create_time")) {
                appendInsertCol(sbCols, sbVals, "create_time", args, now);
            }
            if (cols.contains("update_time")) {
                appendInsertCol(sbCols, sbVals, "update_time", args, now);
            }
            if (cols.contains("status")) {
                appendInsertCol(sbCols, sbVals, "status", args, 1);
            }
            // 处理表中其他必需字段（NOT NULL 且无默认值）
            // 根据 corecmsuser.sql 表结构添加所有必需字段
            if (cols.contains("balance")) {
                appendInsertCol(sbCols, sbVals, "balance", args, 0);
            }
            if (cols.contains("point")) {
                appendInsertCol(sbCols, sbVals, "point", args, 0);
            }
            if (cols.contains("grade")) {
                appendInsertCol(sbCols, sbVals, "grade", args, 0);
            }
            if (cols.contains("createtime")) {
                appendInsertCol(sbCols, sbVals, "createTime", args, now);
            }
            if (cols.contains("updatetime")) {
                appendInsertCol(sbCols, sbVals, "updataTime", args, now);
            }
            if (cols.contains("parentid")) {
                appendInsertCol(sbCols, sbVals, "parentId", args, 0);
            }
            if (cols.contains("userwx")) {
                appendInsertCol(sbCols, sbVals, "userWx", args, 0);
            }
            if (cols.contains("paycode")) {
                appendInsertCol(sbCols, sbVals, "payCode", args, "");
            }
            if (cols.contains("usercode")) {
                appendInsertCol(sbCols, sbVals, "userCode", args, "");
            }
            if (cols.contains("isdelete")) {
                appendInsertCol(sbCols, sbVals, "isDelete", args, 0);
            }
            if (cols.contains("yx_account")) {
                appendInsertCol(sbCols, sbVals, "YX_account", args, "");
            }
            if (cols.contains("yx_name")) {
                appendInsertCol(sbCols, sbVals, "YX_name", args, nameTrim);
            }
            if (cols.contains("transactionno")) {
                appendInsertCol(sbCols, sbVals, "transactionNo", args, "");
            }
            if (cols.contains("customerid")) {
                appendInsertCol(sbCols, sbVals, "customerId", args, "");
            }
            if (cols.contains("fddverify")) {
                appendInsertCol(sbCols, sbVals, "fddVerify", args, "0");
            }
            if (cols.contains("fddverifyurl")) {
                appendInsertCol(sbCols, sbVals, "fddVerifyUrl", args, "");
            }
            if (cols.contains("shopid")) {
                appendInsertCol(sbCols, sbVals, "ShopId", args, "");
            }
            if (cols.contains("tjgoods")) {
                appendInsertCol(sbCols, sbVals, "TJgoods", args, "");
            }
            if (cols.contains("usertype")) {
                appendInsertCol(sbCols, sbVals, "userType", args, "患者");
            }
            if (cols.contains("songhuoyiyuan")) {
                appendInsertCol(sbCols, sbVals, "songhuoyiyuan", args, "");
            }
            if (cols.contains("keshi")) {
                appendInsertCol(sbCols, sbVals, "keshi", args, "");
            }
            if (cols.contains("yisheng")) {
                appendInsertCol(sbCols, sbVals, "yisheng", args, "");
            }
            if (cols.contains("bingzhong")) {
                appendInsertCol(sbCols, sbVals, "bingzhong", args, "");
            }
            if (cols.contains("item1")) {
                appendInsertCol(sbCols, sbVals, "item1", args, "");
            }
            if (cols.contains("item2")) {
                appendInsertCol(sbCols, sbVals, "item2", args, "");
            }
            if (cols.contains("item3")) {
                appendInsertCol(sbCols, sbVals, "item3", args, "");
            }
            if (cols.contains("item4")) {
                appendInsertCol(sbCols, sbVals, "item4", args, "");
            }
            if (cols.contains("fzqy")) {
                appendInsertCol(sbCols, sbVals, "fzqy", args, "");
            }
            if (cols.contains("dybsc")) {
                appendInsertCol(sbCols, sbVals, "dybsc", args, "");
            }
            if (cols.contains("dysq")) {
                appendInsertCol(sbCols, sbVals, "dysq", args, "");
            }
            if (cols.contains("sjld")) {
                appendInsertCol(sbCols, sbVals, "sjld", args, "");
            }
            if (cols.contains("zhiwei")) {
                appendInsertCol(sbCols, sbVals, "zhiwei", args, "");
            }
            if (cols.contains("dyspbry")) {
                appendInsertCol(sbCols, sbVals, "dyspbry", args, "");
            }
            if (cols.contains("changjia")) {
                appendInsertCol(sbCols, sbVals, "changjia", args, "");
            }
            if (cols.contains("wareid")) {
                appendInsertCol(sbCols, sbVals, "wareid", args, "");
            }
            if (cols.contains("isdtp")) {
                appendInsertCol(sbCols, sbVals, "isDTP", args, 0);
            }
            if (cols.contains("isaddress")) {
                appendInsertCol(sbCols, sbVals, "isAddress", args, 0);
            }
            if (cols.contains("isdb")) {
                appendInsertCol(sbCols, sbVals, "isDB", args, 0);
            }
            if (cols.contains("istimeoutnotification")) {
                appendInsertCol(sbCols, sbVals, "isTimeoutNotification", args, 0);
            }
            if (cols.contains("isnotification")) {
                appendInsertCol(sbCols, sbVals, "isNotification", args, 0);
            }
            if (cols.contains("isupdb")) {
                appendInsertCol(sbCols, sbVals, "isupdb", args, 0);
            }
            if (cols.contains("hjtype")) {
                appendInsertCol(sbCols, sbVals, "hjType", args, "");
            }

            haidianJdbcTemplate.update(
                    "INSERT INTO corecmsuser (" + sbCols + ") VALUES (" + sbVals + ")",
                    args.toArray()
            );

            result.put("code", 0);
            result.put("msg", "患者信息创建成功");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", nameTrim);
            data.put("phone", phoneTrim);
            if (StringUtils.hasText(idCardTrim)) data.put("idCard", idCardTrim);
            if (StringUtils.hasText(genderTrim)) data.put("gender", genderTrim);
            if (age != null) data.put("age", age);
            if (StringUtils.hasText(addressTrim)) data.put("address", addressTrim);
            result.put("data", data);
            return result;

        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("患者创建重复：phone={}", phoneTrim);
            result.put("code", 409);
            result.put("msg", "该手机号已存在患者信息");
            result.put("data", Map.of("phone", phoneTrim));
            return result;
        } catch (Exception e) {
            log.warn("患者信息创建失败，name={}, phone={}", nameTrim, phoneTrim, e);
            result.put("code", 500);
            result.put("msg", "患者信息创建失败：" + e.getMessage());
            result.put("data", null);
            return result;
        }
    }

    @Override
    public Map<String, Object> testHaidianDbConnection() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 获取当前数据库名称
            List<Map<String, Object>> dbNameResult = haidianJdbcTemplate.queryForList("SELECT DATABASE() AS db_name");
            String dbName = dbNameResult != null && !dbNameResult.isEmpty() ? String.valueOf(dbNameResult.get(0).get("db_name")) : "unknown";
            log.info("海典数据源连接的数据库: {}", dbName);
            
            // 检查 corecmsuser 表是否存在
            List<Map<String, Object>> tableResult = haidianJdbcTemplate.queryForList(
                    "SELECT COUNT(*) AS cnt FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'corecmsuser'",
                    dbName);
            int tableCount = tableResult != null && !tableResult.isEmpty() ? ((Number) tableResult.get(0).get("cnt")).intValue() : 0;
            boolean tableExists = tableCount > 0;
            
            // 获取表结构
            List<Map<String, Object>> columns = null;
            if (tableExists) {
                columns = haidianJdbcTemplate.queryForList(
                        "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'corecmsuser'",
                        dbName);
            }
            
            result.put("code", 0);
            result.put("msg", "success");
            result.put("data", Map.of(
                    "database_name", dbName,
                    "corecmsuser_exists", tableExists,
                    "columns", columns != null ? columns : List.of()
            ));
            return result;
        } catch (Exception e) {
            log.error("测试海典数据源连接失败", e);
            result.put("code", 500);
            result.put("msg", "测试失败：" + e.getMessage());
            result.put("data", null);
            return result;
        }
    }

    private static class ColumnMeta {
        private final String columnName;
        private final String dataTypeLower;

        private ColumnMeta(String columnName, String dataTypeLower) {
            this.columnName = columnName;
            this.dataTypeLower = dataTypeLower;
        }
    }

    private java.util.Map<String, ColumnMeta> getRequiredNoDefaultColumns(String tableName) {
        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList("""
                    SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = ?
                    """, tableName);
            java.util.LinkedHashMap<String, ColumnMeta> out = new java.util.LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                String col = r.get("COLUMN_NAME") == null ? null : String.valueOf(r.get("COLUMN_NAME"));
                String dataType = r.get("DATA_TYPE") == null ? "" : String.valueOf(r.get("DATA_TYPE")).toLowerCase();
                String nullable = r.get("IS_NULLABLE") == null ? "" : String.valueOf(r.get("IS_NULLABLE"));
                Object def = r.get("COLUMN_DEFAULT");
                String extra = r.get("EXTRA") == null ? "" : String.valueOf(r.get("EXTRA")).toLowerCase();
                if (!StringUtils.hasText(col)) {
                    continue;
                }
                // 必填：NOT NULL 且无默认值；排除自增
                if ("NO".equalsIgnoreCase(nullable) && def == null && (extra == null || !extra.contains("auto_increment"))) {
                    out.put(col.toLowerCase(), new ColumnMeta(col, dataType));
                }
            }
            return out;
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    private Object defaultValueForRequiredColumn(ColumnMeta meta, java.sql.Timestamp now) {
        if (meta == null) {
            return "";
        }
        String col = meta.columnName == null ? "" : meta.columnName.toLowerCase();
        String t = meta.dataTypeLower == null ? "" : meta.dataTypeLower;
        // 常见业务字段做更合理的兜底
        if (col.contains("xing_bie") || col.equals("sex")) {
            return "";
        }
        if (col.contains("create") || col.contains("time")) {
            // 对 datetime/timestamp/date 一类字段用当前时间兜底
            if (t.contains("date") || t.contains("time")) {
                return now;
            }
        }
        if (t.contains("int") || t.contains("bigint") || t.contains("tinyint") || t.contains("smallint") || t.contains("mediumint")) {
            return 0;
        }
        if (t.contains("decimal") || t.contains("numeric") || t.contains("float") || t.contains("double")) {
            return java.math.BigDecimal.ZERO;
        }
        if (t.contains("date") || t.contains("time") || t.contains("timestamp") || t.contains("datetime")) {
            return now;
        }
        // 默认：字符串/其它类型给空串
        return "";
    }

    private java.util.Set<String> getTableColumnsLower(String tableName) {
        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList("""
                    SELECT COLUMN_NAME
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = ?
                    """, tableName);
            java.util.HashSet<String> out = new java.util.HashSet<>();
            for (Map<String, Object> r : rows) {
                Object v = r.get("COLUMN_NAME");
                if (v != null) {
                    out.add(String.valueOf(v).toLowerCase());
                }
            }
            return out;
        } catch (Exception e) {
            return java.util.Set.of();
        }
    }

    private void appendInsertCol(StringBuilder cols, StringBuilder vals, String colName, java.util.List<Object> args, Object value) {
        if (cols.length() > 0) {
            cols.append(", ");
            vals.append(", ");
        }
        cols.append(colName);
        vals.append("?");
        args.add(value);
    }

    private int normalizeStatus(int status) {
        return (status >= 1 && status <= 4) ? status : 1;
    }

    private Map<String, Object> findPatientWithAiFallback(String phone, String idCard, String name) {
        // 1) AI 优先：只要传了 mobile / idCard 之一，就尝试走 core_insurance_query
        Map<String, Object> aiParams = new HashMap<>();
        if (StringUtils.hasText(phone)) {
            aiParams.put("mobile", phone.trim());
        }
        if (StringUtils.hasText(idCard)) {
            aiParams.put("idCard", idCard.trim());
        }
        if (!aiParams.isEmpty()) {
            Map<String, Object> aiRes = tryQueryByAi("core_insurance_query", aiParams);
            Map<String, Object> first = extractFirstRowFromAiResult(aiRes);
            if (first != null) {
                return first;
            }
        }
        // 2) 回退：固定 SQL
        try {
            if (StringUtils.hasText(phone)) {
                List<Map<String, Object>> list = haidianJdbcTemplate.queryForList(
                        "SELECT * FROM corecmsusership WHERE mobile = ? ORDER BY isDefault DESC, updateTime DESC LIMIT 1",
                        phone.trim()
                );
                if (list != null && !list.isEmpty()) {
                    return list.get(0);
                }
            }
            if (StringUtils.hasText(idCard)) {
                Map<String, Object> byId = findUserShipByIdCardFixed(idCard.trim());
                if (byId != null && !byId.isEmpty()) {
                    return byId;
                }
            }
            if (StringUtils.hasText(name)) {
                List<Map<String, Object>> list = haidianJdbcTemplate.queryForList(
                        "SELECT * FROM corecmsusership WHERE name = ? ORDER BY isDefault DESC, updateTime DESC LIMIT 1",
                        name.trim()
                );
                if (list != null && !list.isEmpty()) {
                    return list.get(0);
                }
            }
            return null;
        } catch (Exception e) {
            log.error("查询患者信息失败，phone={}, name={}", phone, name, e);
            return null;
        }
    }

    /**
     * corecmsusership 的身份证字段在不同库里可能是 idCard / bmrSfz / id_card 等。
     * 这里按常见字段名逐个尝试，避免因列名差异导致“身份证查不出患者”。
     */
    private Map<String, Object> findUserShipByIdCardFixed(String idCard) {
        if (!StringUtils.hasText(idCard)) {
            return null;
        }
        String v = idCard.trim();
        String[] sqls = new String[]{
                "SELECT * FROM corecmsusership WHERE idCard = ? ORDER BY isDefault DESC, updateTime DESC LIMIT 1",
                "SELECT * FROM corecmsusership WHERE bmrSfz = ? ORDER BY isDefault DESC, updateTime DESC LIMIT 1",
                "SELECT * FROM corecmsusership WHERE id_card = ? ORDER BY isDefault DESC, updateTime DESC LIMIT 1",
                "SELECT * FROM corecmsusership WHERE sfz = ? ORDER BY isDefault DESC, updateTime DESC LIMIT 1"
        };
        for (String sql : sqls) {
            try {
                List<Map<String, Object>> list = haidianJdbcTemplate.queryForList(sql, v);
                if (list != null && !list.isEmpty()) {
                    return list.get(0);
                }
            } catch (Exception ignored) {
                // 列不存在/权限等：尝试下一种列名
            }
        }
        return null;
    }

    private List<Map<String, Object>> buildGoodsDetailWithAiFallback(List<ParsedDrugItem> items, AiEvidence evidence) {
        java.util.ArrayList<Map<String, Object>> goods = new java.util.ArrayList<>();
        int rowNo = 1;
        for (ParsedDrugItem item : items) {
            Map<String, Object> ware = findWareWithAiFallback(item, evidence);
            if (ware == null) {
                continue;
            }

            Map<String, Object> g = new LinkedHashMap<>();
            g.put("warespec", str(ware.get("WARESPEC")));
            g.put("wareqty", String.valueOf(Math.max(1, item.qty)));
            g.put("warecode", firstNonBlank(str(ware.get("WARECODE")), str(ware.get("WAREID"))));
            g.put("warename", str(ware.get("WARENAME")));
            g.put("tid", "");
            g.put("factoryname", ""); // t_ware_base 未提供厂家名称时留空
            g.put("rowno", String.valueOf(rowNo++));
            goods.add(g);
        }
        return goods;
    }

    private Map<String, Object> findWareWithAiFallback(ParsedDrugItem item, AiEvidence evidence) {
        // 1) AI 优先：core_drug_query
        String kw = firstNonBlank(item.drugName, item.drugAlias);
        if (StringUtils.hasText(kw)) {
            Map<String, Object> aiParams = new HashMap<>();
            aiParams.put("keyword", kw);
            Map<String, Object> aiRes = tryQueryByAi("core_drug_query", aiParams);
            if (aiRes != null) {
                evidence.used = true;
                evidence.lastDrugSql = extractSqlFromAiResult(aiRes);
            }
            Map<String, Object> first = extractFirstRowFromAiResult(aiRes);
            if (first != null) {
                // AI 返回的字段可能不全，这里优先拿 WAREID 再回表补齐规格/编码等字段
                String wareId = firstNonBlank(getIgnoreCase(first, "WAREID"), getIgnoreCase(first, "wareId"));
                if (StringUtils.hasText(wareId)) {
                    Map<String, Object> full = findWareByWareId(wareId);
                    if (full != null) {
                        return full;
                    }
                }
                // 如果 AI 直接返回了完整字段，也可直接用（尽量兜住大小写）
                if (first.containsKey("WARENAME") || first.containsKey("warespec") || first.containsKey("WARESPEC")) {
                    return normalizeWareRow(first);
                }
            }
        }
        // 2) 回退：固定 SQL 模糊查
        Map<String, Object> ware = findWareByKeywordFixed(item.drugName);
        if (ware == null && StringUtils.hasText(item.drugAlias)) {
            ware = findWareByKeywordFixed(item.drugAlias);
        }
        return ware;
    }

    /**
     * 从 AI 返回结构中提取 rows 列表（data.rows），用于统一后续处理。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractRowsFromAiResult(Map<String, Object> aiRes) {
        if (aiRes == null) {
            return null;
        }
        Object data = aiRes.get("data");
        if (!(data instanceof Map)) {
            return null;
        }
        Object rows = ((Map<?, ?>) data).get("rows");
        if (!(rows instanceof List) || ((List<?>) rows).isEmpty()) {
            return null;
        }
        return (List<Map<String, Object>>) rows;
    }

    private Map<String, Object> normalizeWareRow(Map<String, Object> row) {
        // 将可能的 key 归一化到大写字段名，便于后续使用
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("WAREID", firstNonBlank(getIgnoreCase(row, "WAREID"), getIgnoreCase(row, "wareId")));
        out.put("WARENAME", firstNonBlank(getIgnoreCase(row, "WARENAME"), getIgnoreCase(row, "warename")));
        out.put("WAREGENERALNAME", firstNonBlank(getIgnoreCase(row, "WAREGENERALNAME"), getIgnoreCase(row, "waregeneralname")));
        out.put("WARESPEC", firstNonBlank(getIgnoreCase(row, "WARESPEC"), getIgnoreCase(row, "warespec")));
        out.put("WAREABC", firstNonBlank(getIgnoreCase(row, "WAREABC"), getIgnoreCase(row, "wareabc")));
        out.put("WARECODE", firstNonBlank(getIgnoreCase(row, "WARECODE"), getIgnoreCase(row, "warecode")));
        out.put("BARCODE", firstNonBlank(getIgnoreCase(row, "BARCODE"), getIgnoreCase(row, "barcode")));
        out.put("LASTTIME", firstNonBlank(getIgnoreCase(row, "LASTTIME"), getIgnoreCase(row, "lasttime")));
        return out;
    }

    private Map<String, Object> findWareByWareId(String wareId) {
        try {
            List<Map<String, Object>> list = haidianJdbcTemplate.queryForList(
                    """
                            SELECT WAREID, WARENAME, WAREGENERALNAME, WARESPEC, WAREABC, WARECODE, BARCODE, LASTTIME
                            FROM t_ware_base
                            WHERE WAREID = ?
                            LIMIT 1
                            """,
                    wareId
            );
            return (list == null || list.isEmpty()) ? null : list.get(0);
        } catch (Exception e) {
            log.error("按 WAREID 查询药品失败，wareId={}", wareId, e);
            return null;
        }
    }

    private Map<String, Object> findWareByKeywordFixed(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        for (String k : buildDrugKeywordCandidates(keyword)) {
            try {
                String like = "%" + k + "%";
                List<Map<String, Object>> list = haidianJdbcTemplate.queryForList(
                        """
                                SELECT WAREID, WARENAME, WAREGENERALNAME, WARESPEC, WAREABC, WARECODE, BARCODE, LASTTIME
                                FROM t_ware_base
                                WHERE (WARENAME LIKE ? OR WAREGENERALNAME LIKE ? OR WAREABC LIKE ?)
                                ORDER BY LASTTIME DESC
                                LIMIT 5
                                """,
                        like, like, like
                );
                if (list == null || list.isEmpty()) {
                    continue;
                }
                // 优先命中度更高的行，再兜底第一条
                for (Map<String, Object> r : list) {
                    String wn = str(r.get("WARENAME"));
                    if (wn != null && wn.contains(k)) {
                        return r;
                    }
                }
                return list.get(0);
            } catch (Exception e) {
                log.error("查询药品基础信息失败，keyword={}", keyword, e);
                return null;
            }
        }
        // 最后一层兜底：仅按“去赠药后”的词做包含匹配，避免“艾瑞利赠药”之类完全落空
        String simple = stripGiftSuffix(keyword);
        if (StringUtils.hasText(simple) && simple.length() >= 2) {
            try {
                List<Map<String, Object>> list = haidianJdbcTemplate.queryForList(
                        """
                                SELECT WAREID, WARENAME, WAREGENERALNAME, WARESPEC, WAREABC, WARECODE, BARCODE, LASTTIME
                                FROM t_ware_base
                                WHERE INSTR(REPLACE(IFNULL(WARENAME, ''), ' ', ''), REPLACE(?, ' ', '')) > 0
                                   OR INSTR(REPLACE(IFNULL(WAREGENERALNAME, ''), ' ', ''), REPLACE(?, ' ', '')) > 0
                                ORDER BY LASTTIME DESC
                                LIMIT 5
                                """,
                        simple, simple
                );
                if (list != null && !list.isEmpty()) {
                    return list.get(0);
                }
            } catch (Exception e) {
                log.error("宽松药名匹配失败，keyword={}", keyword, e);
            }
        }
        return null;
    }

    private List<String> buildDrugKeywordCandidates(String keyword) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        String raw = keyword.trim();
        addKeywordCandidate(set, raw);
        String noBracket = raw.replaceAll("[（(].*$", "").trim();
        addKeywordCandidate(set, noBracket);
        String noGift = stripGiftSuffix(noBracket);
        addKeywordCandidate(set, noGift);
        // 去掉末尾常见规格/数量描述，避免“药名+规格”整体匹配失败。
        String noSpec = noGift
                .replaceAll("\\s*[0-9]+(\\.[0-9]+)?\\s*(mg|g|ml|mL|ug|IU|粒|片|支|袋|盒|瓶|丸).*$", "")
                .replaceAll("\\s*[xX×*]\\s*[0-9]+.*$", "")
                .trim();
        addKeywordCandidate(set, noSpec);
        String firstToken = noSpec.split("[,，/；;\\s]")[0].trim();
        addKeywordCandidate(set, firstToken);
        return new ArrayList<>(set);
    }

    /**
     * 业务侧常见“药名+赠药/赠”写法：例如“艾瑞利赠药”“XX（赠）”。
     * 数据库主数据通常仅存基础药名，需先剥离营销后缀再查。
     */
    private static String stripGiftSuffix(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String t = text.trim();
        t = t.replaceAll("(?i)[（(]\\s*赠药\\s*[)）]$", "");
        t = t.replaceAll("(?i)[（(]\\s*赠\\s*[)）]$", "");
        t = t.replaceAll("(?i)\\s*(赠药|赠品|赠)$", "");
        return t.trim();
    }

    private static void addKeywordCandidate(java.util.LinkedHashSet<String> set, String v) {
        if (!StringUtils.hasText(v)) {
            return;
        }
        String s = v.trim();
        if (s.length() < 2) {
            return;
        }
        set.add(s);
    }

    private Map<String, Object> findWareByBarcodeFlexible(String barCode) {
        String raw = normalizeBarcodeText(barCode);
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String digits = raw.replaceAll("\\D+", "");
        try {
            List<Map<String, Object>> list = haidianJdbcTemplate.queryForList(
                    """
                            SELECT WAREID, WARENAME, WAREGENERALNAME, WARESPEC, WAREABC, WARECODE, BARCODE, FILENO, LASTTIME
                            FROM t_ware_base
                            WHERE TRIM(BARCODE) = ?
                               OR TRIM(WARECODE) = ?
                               OR TRIM(FILENO) = ?
                               OR BARCODE LIKE ?
                               OR WARECODE LIKE ?
                               OR FILENO LIKE ?
                            ORDER BY LASTTIME DESC
                            LIMIT 5
                            """,
                    raw, raw, raw, "%" + raw + "%", "%" + raw + "%", "%" + raw + "%"
            );
            if ((list == null || list.isEmpty()) && StringUtils.hasText(digits) && !digits.equals(raw)) {
                list = haidianJdbcTemplate.queryForList(
                        """
                                SELECT WAREID, WARENAME, WAREGENERALNAME, WARESPEC, WAREABC, WARECODE, BARCODE, FILENO, LASTTIME
                                FROM t_ware_base
                                WHERE REPLACE(REPLACE(TRIM(BARCODE), ' ', ''), '-', '') = ?
                                   OR REPLACE(REPLACE(TRIM(WARECODE), ' ', ''), '-', '') = ?
                                   OR REPLACE(REPLACE(TRIM(FILENO), ' ', ''), '-', '') = ?
                                ORDER BY LASTTIME DESC
                                LIMIT 5
                                """,
                        digits, digits, digits
                );
            }
            if (list == null || list.isEmpty()) {
                return null;
            }
            for (Map<String, Object> r : list) {
                String b1 = normalizeBarcodeText(str(r.get("BARCODE")));
                String b2 = normalizeBarcodeText(str(r.get("WARECODE")));
                String b3 = normalizeBarcodeText(str(r.get("FILENO")));
                if (raw.equals(b1) || raw.equals(b2) || raw.equals(b3)) {
                    return r;
                }
                if (StringUtils.hasText(digits)) {
                    String d1 = b1 == null ? "" : b1.replaceAll("\\D+", "");
                    String d2 = b2 == null ? "" : b2.replaceAll("\\D+", "");
                    String d3 = b3 == null ? "" : b3.replaceAll("\\D+", "");
                    if (digits.equals(d1) || digits.equals(d2) || digits.equals(d3)) {
                        return r;
                    }
                }
            }
            return list.get(0);
        } catch (Exception e) {
            log.warn("按条码/编码匹配药品失败，barCode={}", barCode, e);
            return null;
        }
    }

    private static String normalizeBarcodeText(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        return s.trim().replaceAll("\\s+", "").replace("\u3000", "");
    }

    private Map<String, Object> extractFirstRowFromAiResult(Map<String, Object> aiRes) {
        if (aiRes == null) {
            return null;
        }
        Object data = aiRes.get("data");
        if (!(data instanceof Map)) {
            return null;
        }
        Object rows = ((Map<?, ?>) data).get("rows");
        if (!(rows instanceof List) || ((List<?>) rows).isEmpty()) {
            return null;
        }
        Object first = ((List<?>) rows).get(0);
        if (!(first instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) first;
        return row;
    }

    private String extractSqlFromAiResult(Map<String, Object> aiRes) {
        if (aiRes == null) {
            return null;
        }
        Object data = aiRes.get("data");
        if (!(data instanceof Map)) {
            return null;
        }
        Object sql = ((Map<?, ?>) data).get("sql");
        return sql == null ? null : String.valueOf(sql);
    }

    private static String getIgnoreCase(Map<String, Object> m, String key) {
        if (m == null || key == null) return null;
        Object v = m.get(key);
        if (v != null) return String.valueOf(v);
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key) && e.getValue() != null) {
                return String.valueOf(e.getValue());
            }
        }
        return null;
    }

    private static String firstNonBlank(String... parts) {
        if (parts == null) {
            return null;
        }
        for (String p : parts) {
            if (StringUtils.hasText(p)) {
                return p.trim();
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 从自然语言中解析下单要素：患者姓名、电话、患教（可选）、药品项（名称/别名/数量）。
     * 当前实现面向中文描述的“弱结构”输入，解析失败会返回尽量多的线索便于排查。
     */
    private ParsedOrderText parseOrderText(String text) {
        ParsedOrderText out = new ParsedOrderText();
        String t = text == null ? "" : text.trim();

        out.patientName = matchGroup(t, Pattern.compile("(?:患者名称|患者姓名|患者|姓名)\\s*[:：]?\\s*([\\u4e00-\\u9fa5]{2,20})"));
        out.patientPhone = matchGroup(t, Pattern.compile("(?:电话号码|手机号码|手机号|电话)\\s*[:：]?\\s*(1[3-9]\\d{9})"));
        out.patientIdCard = matchGroup(t, Pattern.compile("(?:身份证号|身份证)\\s*[:：]?\\s*([1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx])"));
        out.patientEducation = matchGroup(t, Pattern.compile("(?:患教|宣教)\\s*[:：]?\\s*([^，。；\\n]+)"));

        // 药品段落：优先取“需要买的药/药品/购买”之后的部分
        String drugPart = null;
        Matcher m = Pattern.compile("(?:需要买的药|需要购买的药|购买药品|药品|用药)\\s*[:：]?\\s*(.+)$").matcher(t);
        if (m.find()) {
            drugPart = m.group(1);
        } else {
            drugPart = t;
        }

        java.util.ArrayList<ParsedDrugItem> items = new java.util.ArrayList<>();
        // 按中文逗号/顿号/分号/换行分割
        String[] segs = drugPart.split("[，、；;\\n]+");
        Pattern itemPat = Pattern.compile("(.+?)([0-9一二两三四五六七八九十百]+)\\s*(盒|瓶|袋|支|片|粒|包|板)?$");
        for (String raw : segs) {
            String s = raw == null ? "" : raw.trim();
            if (!StringUtils.hasText(s)) {
                continue;
            }
            // 去掉前缀“需要买的药：”
            s = s.replaceAll("^需要买的药\\s*[:：]?\\s*", "").trim();
            Matcher im = itemPat.matcher(s);
            if (!im.find()) {
                continue;
            }
            String namePart = im.group(1).trim();
            if (isLikelyInstructionTextName(namePart)) {
                continue;
            }
            String qtyPart = im.group(2).trim();
            int qty = parseChineseOrArabicInt(qtyPart);
            if (qty <= 0) {
                qty = 1;
            }

            ParsedDrugItem item = new ParsedDrugItem();
            item.qty = qty;
            item.drugName = namePart;
            // 解析别名：括号内容
            Matcher am = Pattern.compile("(.+?)[（(]([^）)]+)[）)]").matcher(namePart);
            if (am.find()) {
                item.drugName = am.group(1).trim();
                item.drugAlias = am.group(2).trim();
            }
            items.add(item);
        }
        out.items = items;

        String globalSpec = matchGroup(t, Pattern.compile("(?:规格)\\s*[:：]?\\s*([^，。；\\n]+)"));
        if (globalSpec != null && out.items != null) {
            for (ParsedDrugItem item : out.items) {
                if (item != null && !StringUtils.hasText(item.spec)) {
                    item.spec = globalSpec.trim();
                }
            }
        }
        return out;
    }

    private static String matchGroup(String text, Pattern p) {
        Matcher m = p.matcher(text == null ? "" : text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static int parseChineseOrArabicInt(String s) {
        if (!StringUtils.hasText(s)) {
            return 0;
        }
        String t = s.trim();
        if (t.matches("\\d+")) {
            try {
                return Integer.parseInt(t);
            } catch (Exception ignored) {
                return 0;
            }
        }
        // 简单中文数字：支持 1-99（含“十”“两”）
        t = t.replace("两", "二");
        java.util.Map<Character, Integer> map = Map.of(
                '零', 0, '一', 1, '二', 2, '三', 3, '四', 4,
                '五', 5, '六', 6, '七', 7, '八', 8, '九', 9
        );
        if (t.equals("十")) return 10;
        if (t.startsWith("十")) {
            Integer ones = map.get(t.charAt(1));
            return 10 + (ones == null ? 0 : ones);
        }
        if (t.contains("十")) {
            String[] parts = t.split("十", -1);
            Integer tens = parts[0].isEmpty() ? 1 : map.get(parts[0].charAt(0));
            int tenVal = (tens == null ? 0 : tens) * 10;
            int oneVal = 0;
            if (parts.length > 1 && !parts[1].isEmpty()) {
                Integer ones = map.get(parts[1].charAt(0));
                oneVal = ones == null ? 0 : ones;
            }
            return tenVal + oneVal;
        }
        Integer v = map.get(t.charAt(0));
        return v == null ? 0 : v;
    }

    @Data
    private static class ParsedOrderText {
        private String patientName;
        private String patientPhone;
        private String patientIdCard;
        private String patientEducation;
        private List<ParsedDrugItem> items;
    }

    @Data
    private static class AiEvidence {
        /**
         * 本次下单流程中，是否命中过 AI 查询（命中仅表示调用成功返回了 SQL/rows，不代表最终一定使用了 AI 结果）
         */
        private boolean used;
        private String lastDrugSql;
    }

    @Data
    private static class ParsedDrugItem {
        private String drugName;
        private String drugAlias;
        /** 从全文「规格：xxx」等解析，用于展示或匹配 */
        private String spec;
        private int qty;
    }

    /**
     * 统一的 AI 查询尝试逻辑：
     * - 未配置 AiSqlService 时直接返回 null；
     * - AiSqlService 抛异常或显式返回 null 时，也视为“AI 不可用”，由调用方继续执行兜底 SQL。
     */
    private Map<String, Object> tryQueryByAi(String toolName, Map<String, Object> params) {
        if (aiSqlService == null) {
            return null;
        }
        try {
            Map<String, Object> res = aiSqlService.queryByAi(toolName, params);
            if (res == null) {
                return null;
            }
            Object code = res.get("code");
            if (code instanceof Number && ((Number) code).intValue() == 0) {
                // code == 0 认为是成功，直接返回
                return res;
            }
            // 非 0 视为 AI 查询失败，继续走兜底 SQL，同时打印日志方便排查
            log.warn("AI SQL 查询返回非成功 code，toolName={}, params={}, code={}", toolName, params, code);
            return null;
        } catch (Exception e) {
            log.warn("AI SQL 查询异常，toolName={}, params={}", toolName, params, e);
            return null;
        }
    }

    /**
     * 按身份证号从各地区医保 OCR 表中查询患者最近的医保记录列表。
     * ocrsichuanyibao 使用 shen_fen_zheng、create_time；其它表使用 idCard、createTime。
     */
    private List<Map<String, Object>> queryInsuranceOcrByIdCard(String idCard) {
        if (!StringUtils.hasText(idCard)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> all = new java.util.ArrayList<>();
            try {
                // ocrsichuanyibao 可能是全量结构（shen_fen_zheng/create_time）也可能是简化结构（idCard/createTime）
                try {
                    all.addAll(haidianJdbcTemplate.queryForList("""
                            SELECT * FROM ocrsichuanyibao
                            WHERE shen_fen_zheng = ?
                            ORDER BY create_time DESC
                            LIMIT 50
                            """, idCard));
                } catch (org.springframework.jdbc.BadSqlGrammarException ex) {
                    // 字段不兼容则回退到简化结构
                    all.addAll(haidianJdbcTemplate.queryForList("""
                            SELECT * FROM ocrsichuanyibao
                            WHERE idCard = ?
                            ORDER BY createTime DESC
                            LIMIT 50
                            """, idCard));
                }
            } catch (org.springframework.jdbc.BadSqlGrammarException ex) {
                Throwable cause = ex.getCause();
                String msg = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
                if (cause instanceof java.sql.SQLSyntaxErrorException && msg.contains("doesn't exist")) {
                    log.debug("表 ocrsichuanyibao 不存在，跳过");
                } else {
                    log.warn("从表 ocrsichuanyibao 查询医保 OCR 记录失败，idCard={}", idCard, ex);
                }
            }
            // 其它 OCR 表（若存在）使用 idCard、createTime
            String[] otherTables = {"ocrguangdongyibao", "ocrhenanyibao", "ocrteyaojiesuan"};
            for (String table : otherTables) {
                String sql = """
                        SELECT * FROM %s
                        WHERE idCard = ?
                        ORDER BY createTime DESC
                        LIMIT 50
                        """.formatted(table);
                try {
                    all.addAll(haidianJdbcTemplate.queryForList(sql, idCard));
                } catch (org.springframework.jdbc.BadSqlGrammarException ex) {
                    Throwable cause = ex.getCause();
                    String msg = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
                    if (cause instanceof java.sql.SQLSyntaxErrorException &&
                            (msg.contains("doesn't exist") || msg.contains("Unknown column 'idCard'"))) {
                        log.debug("医保 OCR 表结构不兼容或不存在，忽略本表，table={}", table);
                        continue;
                    }
                    log.warn("从表 {} 查询医保 OCR 记录失败，idCard={}", table, idCard, ex);
                }
            }
            // 按 create_time 或 createTime 倒序截取最多 50 条
            all.sort((a, b) -> {
                Object ca = a.get("create_time") != null ? a.get("create_time") : a.get("createTime");
                Object cb = b.get("create_time") != null ? b.get("create_time") : b.get("createTime");
                if (ca == null || cb == null) return 0;
                if (ca instanceof Comparable && cb.getClass().isAssignableFrom(ca.getClass())) {
                    @SuppressWarnings("unchecked")
                    Comparable<Object> c1 = (Comparable<Object>) ca;
                    return -c1.compareTo(cb);
                }
                return 0;
            });
            if (all.size() > 50) {
                return all.subList(0, 50);
            }
            return all;
        } catch (Exception e) {
            log.warn("按身份证号查询医保 OCR 记录失败，idCard={}", idCard, e);
            return List.of();
        }
    }

    private static String newMcpShipmentId() {
        return "SH-" + java.time.LocalDate.now() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String shipmentPickName(Map<String, Object> request) {
        if (request == null) {
            return null;
        }
        return firstNonBlank(blankToNull(str(request.get("recipientName"))),
                blankToNull(str(request.get("name"))),
                blankToNull(str(request.get("receiverName"))),
                blankToNull(str(request.get("receiver_name"))),
                blankToNull(getIgnoreCase(request, "recipient_name")));
    }

    private String shipmentPickPhone(Map<String, Object> request) {
        if (request == null) {
            return null;
        }
        return firstNonBlank(blankToNull(str(request.get("recipientPhone"))),
                blankToNull(str(request.get("phone"))),
                blankToNull(str(request.get("mobile"))),
                blankToNull(getIgnoreCase(request, "recipient_phone")));
    }

    private String shipmentPickAddress(Map<String, Object> request) {
        if (request == null) {
            return null;
        }
        return firstNonBlank(blankToNull(str(request.get("address"))),
                blankToNull(str(request.get("recvAddress"))),
                blankToNull(str(request.get("detailAddress"))),
                blankToNull(str(request.get("receiverAddress"))),
                blankToNull(getIgnoreCase(request, "receiver_address")),
                blankToNull(getIgnoreCase(request, "detail_address")));
    }

    private String shipmentPickMethod(Map<String, Object> request) {
        if (request == null) {
            return null;
        }
        return firstNonBlank(blankToNull(str(request.get("shipMethod"))),
                blankToNull(str(request.get("mailMethod"))),
                blankToNull(str(request.get("mailingMethod"))),
                blankToNull(str(request.get("shippingMethod"))),
                blankToNull(getIgnoreCase(request, "ship_method")),
                blankToNull(getIgnoreCase(request, "mail_method")),
                blankToNull(str(request.get("邮寄方式"))));
    }

    private boolean shipmentHasItemsPayload(Map<String, Object> request) {
        if (request == null) {
            return false;
        }
        return request.containsKey("items")
                || request.containsKey("drugs")
                || request.containsKey("goodsList")
                || request.containsKey("drugItems")
                || getIgnoreCase(request, "items") != null
                || getIgnoreCase(request, "drugs") != null
                || getIgnoreCase(request, "goods_list") != null
                || getIgnoreCase(request, "drug_items") != null;
    }

    /**
     * 发货药品统一入参：
     * - items（推荐，结构同下单）
     * - drugs / goodsList / drugItems（兼容别名）
     */
    private List<Map<String, Object>> shipmentNormalizeItems(Map<String, Object> request) {
        if (request == null) {
            return List.of();
        }
        Object itemsObj = request.get("items");
        if (itemsObj == null) itemsObj = request.get("drugs");
        if (itemsObj == null) itemsObj = request.get("goodsList");
        if (itemsObj == null) itemsObj = request.get("drugItems");
        if (itemsObj == null) itemsObj = getIgnoreCase(request, "items");
        if (itemsObj == null) itemsObj = getIgnoreCase(request, "drugs");
        if (itemsObj == null) itemsObj = getIgnoreCase(request, "goods_list");
        if (itemsObj == null) itemsObj = getIgnoreCase(request, "drug_items");
        return normalizeItemsList(itemsObj);
    }

    /**
     * 兼容历史 raw_request_data，提取并标准化发货药品明细。
     */
    private List<Map<String, Object>> extractShipmentItemsFromRawJson(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(rawJson, Map.class);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> items = normalizeItemsList(raw.get("items"));
            if (!items.isEmpty()) {
                return items;
            }
            items = normalizeItemsList(raw.get("drugs"));
            if (!items.isEmpty()) {
                return items;
            }
            return normalizeItemsList(raw.get("goodsList"));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String findShipmentRawRequestDataByShipmentId(String shipmentId) {
        if (haidianJdbcTemplate == null || !StringUtils.hasText(shipmentId)) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(
                    "SELECT raw_request_data FROM mcp_shipment_request_log WHERE shipment_id = ? LIMIT 1",
                    shipmentId.trim());
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            Object v = rows.get(0).get("raw_request_data");
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            log.warn("查询发货 raw_request_data 失败 shipmentId={}", shipmentId, e);
            return null;
        }
    }

    /**
     * 发货短时合并：同手机号在窗口期内重复提交，且原单仍为待发货，则视为「修改」并覆盖最近一条。
     */
    private String findRecentShipmentIdForShortDuplicateMerge(String phoneRaw, int windowMinutes) {
        if (haidianJdbcTemplate == null || !StringUtils.hasText(phoneRaw) || windowMinutes <= 0) {
            return null;
        }
        String canonNew = canonicalMobileForShortDuplicate(phoneRaw);
        if (!StringUtils.hasText(canonNew)) {
            return null;
        }
        try {
            java.sql.Timestamp since = new java.sql.Timestamp(System.currentTimeMillis() - windowMinutes * 60_000L);
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(
                    """
                            SELECT shipment_id, recipient_phone
                            FROM mcp_shipment_request_log
                            WHERE ship_status = 0
                              AND create_time >= ?
                            ORDER BY create_time DESC
                            LIMIT 50
                            """,
                    since);
            for (Map<String, Object> row : rows) {
                String sid = row.get("shipment_id") == null ? null : String.valueOf(row.get("shipment_id"));
                String oldPhone = row.get("recipient_phone") == null ? null : String.valueOf(row.get("recipient_phone"));
                if (!StringUtils.hasText(sid) || !StringUtils.hasText(oldPhone)) {
                    continue;
                }
                if (canonNew.equals(canonicalMobileForShortDuplicate(oldPhone))) {
                    return sid;
                }
            }
        } catch (Exception e) {
            log.warn("发货短时重复合并查询失败 phone={}", phoneRaw, e);
        }
        return null;
    }

    @Override
    public Map<String, Object> createShipment(Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        if (request == null) {
            result.put("code", 400);
            result.put("msg", "请求体不能为空");
            result.put("data", null);
            return result;
        }
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", null);
            return result;
        }
        String name = shipmentPickName(request);
        String phone = shipmentPickPhone(request);
        String address = shipmentPickAddress(request);
        String method = shipmentPickMethod(request);
        String remark = blankToNull(firstNonBlank(blankToNull(str(request.get("remark"))),
                blankToNull(getIgnoreCase(request, "remark"))));
        boolean hasItemsPayload = shipmentHasItemsPayload(request);
        List<Map<String, Object>> shipmentItems = shipmentNormalizeItems(request);
        if (hasItemsPayload && shipmentItems.isEmpty()) {
            result.put("code", 400);
            result.put("msg", "药品参数已传入但无有效明细（请传 items/drugs/goodsList，且每项至少包含 drugName/name）");
            result.put("data", null);
            return result;
        }
        if (!StringUtils.hasText(name)) {
            result.put("code", 400);
            result.put("msg", "收件人姓名为必填，可传 recipientName 或 name");
            result.put("data", null);
            return result;
        }
        if (!StringUtils.hasText(phone)) {
            result.put("code", 400);
            result.put("msg", "电话为必填，可传 recipientPhone、phone 或 mobile");
            result.put("data", null);
            return result;
        }
        if (!StringUtils.hasText(address)) {
            result.put("code", 400);
            result.put("msg", "地址为必填，可传 address");
            result.put("data", null);
            return result;
        }
        if (!StringUtils.hasText(method)) {
            result.put("code", 400);
            result.put("msg", "邮寄方式为必填，可传 shipMethod、mailMethod 或 mailingMethod");
            result.put("data", null);
            return result;
        }
        String hitShipmentId = findRecentShipmentIdForShortDuplicateMerge(phone, DUPLICATE_SUBMIT_WINDOW_MINUTES);
        String shipmentId = StringUtils.hasText(hitShipmentId) ? hitShipmentId : newMcpShipmentId();
        LinkedHashMap<String, Object> stored = new LinkedHashMap<>();
        stored.put("recipientName", name);
        stored.put("recipientPhone", phone.trim());
        stored.put("address", address);
        stored.put("shipMethod", method);
        if (StringUtils.hasText(remark)) {
            stored.put("remark", remark);
        }
        if (!shipmentItems.isEmpty()) {
            stored.put("items", shipmentItems);
        }
        String rawJson;
        try {
            rawJson = objectMapper.writeValueAsString(stored);
        } catch (Exception e) {
            rawJson = "{}";
        }
        boolean mergedByMcpDuplicate = false;
        try {
            if (StringUtils.hasText(hitShipmentId)) {
                int updated = haidianJdbcTemplate.update(
                        """
                                UPDATE mcp_shipment_request_log
                                SET recipient_name = ?, recipient_phone = ?, address = ?, ship_method = ?, remark = ?, raw_request_data = ?
                                WHERE shipment_id = ? AND ship_status = 0
                                """,
                        name,
                        phone.trim(),
                        address,
                        method,
                        StringUtils.hasText(remark) ? remark : null,
                        rawJson,
                        shipmentId);
                if (updated > 0) {
                    mergedByMcpDuplicate = true;
                } else {
                    log.warn("发货短时合并 UPDATE 未命中，回退为新建 shipmentId={}", shipmentId);
                    shipmentId = newMcpShipmentId();
                    int inserted = haidianJdbcTemplate.update("""
                                    INSERT INTO mcp_shipment_request_log (
                                        shipment_id, recipient_name, recipient_phone, address, ship_method, ship_status, remark, raw_request_data)
                                    VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                                    """,
                            shipmentId,
                            name,
                            phone.trim(),
                            address,
                            method,
                            StringUtils.hasText(remark) ? remark : null,
                            rawJson);
                    if (inserted <= 0) {
                        result.put("code", 500);
                        result.put("msg", "写入发货登记失败（影响行数0），请确认已执行 db/mcp_shipment_request_log.sql");
                        result.put("data", Map.of("shipmentId", shipmentId));
                        return result;
                    }
                }
            } else {
                int n = haidianJdbcTemplate.update("""
                                INSERT INTO mcp_shipment_request_log (
                                    shipment_id, recipient_name, recipient_phone, address, ship_method, ship_status, remark, raw_request_data)
                                VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                                """,
                        shipmentId,
                        name,
                        phone.trim(),
                        address,
                        method,
                        StringUtils.hasText(remark) ? remark : null,
                        rawJson);
                if (n <= 0) {
                    result.put("code", 500);
                    result.put("msg", "写入发货登记失败（影响行数0），请确认已执行 db/mcp_shipment_request_log.sql");
                    result.put("data", Map.of("shipmentId", shipmentId));
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("createShipment 落库失败", e);
            result.put("code", 500);
            result.put("msg", "写入发货登记失败：" + e.getMessage());
            result.put("data", Map.of("shipmentId", shipmentId));
            return result;
        }
        if (mcpOrderAuditRealtimePublisher != null) {
            if (mergedByMcpDuplicate) {
                mcpOrderAuditRealtimePublisher.publishMergedShipment(shipmentId, name, phone.trim());
            } else {
                mcpOrderAuditRealtimePublisher.publishNewShipment(shipmentId, name, phone.trim(), address, method);
            }
        }
        result.put("code", 0);
        result.put("msg", "ok");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shipmentId", shipmentId);
        data.put("merged", mergedByMcpDuplicate);
        data.put("itemCount", shipmentItems.size());
        data.put("message", mergedByMcpDuplicate ? "短时间同手机号重复提交，已合并为修改" : "发货信息已登记，等待发货");
        result.put("data", data);
        return result;
    }

    @Override
    public Map<String, Object> getShipmentAuditList(String shipStatus, String shipmentId, String recipientPhone,
                                                    String nameKeyword, String createDateStart, String createDateEnd) {
        Map<String, Object> result = new HashMap<>();
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", List.of());
            return result;
        }
        try {
            StringBuilder where = new StringBuilder("""
                    FROM mcp_shipment_request_log WHERE 1=1
                    """);
            List<Object> params = new ArrayList<>();
            if (StringUtils.hasText(shipStatus)) {
                try {
                    int st = Integer.parseInt(shipStatus.trim());
                    where.append(" AND ship_status = ?");
                    params.add(st);
                } catch (NumberFormatException ignored) {
                }
            }
            if (StringUtils.hasText(shipmentId)) {
                where.append(" AND shipment_id LIKE ?");
                params.add("%" + shipmentId.trim() + "%");
            }
            if (StringUtils.hasText(recipientPhone)) {
                where.append(" AND recipient_phone LIKE ?");
                params.add("%" + recipientPhone.trim() + "%");
            }
            if (StringUtils.hasText(nameKeyword)) {
                where.append(" AND recipient_name LIKE ?");
                params.add("%" + nameKeyword.trim() + "%");
            }
            LocalDateTime createFrom = parseFlexibleDateTimeStart(null, createDateStart);
            LocalDateTime createTo = parseFlexibleDateTimeEnd(null, createDateEnd);
            if (createFrom != null) {
                where.append(" AND create_time >= ?");
                params.add(java.sql.Timestamp.valueOf(createFrom));
            }
            if (createTo != null) {
                where.append(" AND create_time <= ?");
                params.add(java.sql.Timestamp.valueOf(createTo));
            }

            Integer totalCount = 0;
            try {
                totalCount = haidianJdbcTemplate.queryForObject("SELECT COUNT(1) " + where, params.toArray(), Integer.class);
            } catch (Exception e) {
                log.warn("getShipmentAuditList COUNT 失败，回退为当前返回条数", e);
            }

            StringBuilder sql = new StringBuilder("""
                    SELECT id, shipment_id, recipient_name, recipient_phone, address, ship_method, ship_status, remark,
                           raw_request_data, ship_time, create_time, update_time
                    """);
            sql.append(where);
            sql.append(" ORDER BY create_time DESC LIMIT 800");
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(sql.toString(), params.toArray());
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", row.get("id"));
                m.put("shipmentId", row.get("shipment_id"));
                m.put("recipientName", row.get("recipient_name"));
                m.put("recipientPhone", row.get("recipient_phone"));
                m.put("address", row.get("address"));
                m.put("shipMethod", row.get("ship_method"));
                m.put("shipStatus", row.get("ship_status"));
                m.put("remark", row.get("remark"));
                m.put("shipTime", row.get("ship_time"));
                m.put("createTime", row.get("create_time"));
                m.put("updateTime", row.get("update_time"));
                String raw = row.get("raw_request_data") == null ? null : String.valueOf(row.get("raw_request_data"));
                List<Map<String, Object>> items = extractShipmentItemsFromRawJson(raw);
                m.put("itemCount", items.size());
                if (!items.isEmpty()) {
                    m.put("items", items);
                }
                list.add(m);
            }
            result.put("code", 0);
            result.put("msg", "ok");
            result.put("data", list);
            result.put("totalCount", totalCount == null ? list.size() : totalCount);
            return result;
        } catch (Exception e) {
            log.error("getShipmentAuditList 失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败：" + e.getMessage());
            result.put("data", List.of());
            return result;
        }
    }

    @Override
    public Map<String, Object> updateShipmentAuditData(String shipmentId, Map<String, Object> fields) {
        Map<String, Object> result = new HashMap<>();
        if (!StringUtils.hasText(shipmentId)) {
            result.put("code", 400);
            result.put("msg", "shipmentId 不能为空");
            result.put("data", null);
            return result;
        }
        if (fields == null || fields.isEmpty()) {
            result.put("code", 400);
            result.put("msg", "fields 不能为空");
            result.put("data", null);
            return result;
        }
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", null);
            return result;
        }
        String name = shipmentPickName(fields);
        String phone = shipmentPickPhone(fields);
        String address = shipmentPickAddress(fields);
        String method = shipmentPickMethod(fields);
        String remark = blankToNull(firstNonBlank(blankToNull(str(fields.get("remark"))),
                blankToNull(getIgnoreCase(fields, "remark"))));
        boolean hasItemsPayload = shipmentHasItemsPayload(fields);
        List<Map<String, Object>> shipmentItems = shipmentNormalizeItems(fields);
        if (hasItemsPayload && shipmentItems.isEmpty()) {
            result.put("code", 400);
            result.put("msg", "药品参数已传入但无有效明细（请传 items/drugs/goodsList，且每项至少包含 drugName/name）");
            result.put("data", null);
            return result;
        }
        if (!StringUtils.hasText(name) || !StringUtils.hasText(phone) || !StringUtils.hasText(address) || !StringUtils.hasText(method)) {
            result.put("code", 400);
            result.put("msg", "recipientName、recipientPhone、address、shipMethod 均不能为空");
            result.put("data", null);
            return result;
        }
        try {
            if (!hasItemsPayload) {
                String oldRaw = findShipmentRawRequestDataByShipmentId(shipmentId);
                shipmentItems = extractShipmentItemsFromRawJson(oldRaw);
            }
            LinkedHashMap<String, Object> stored = new LinkedHashMap<>();
            stored.put("recipientName", name);
            stored.put("recipientPhone", phone.trim());
            stored.put("address", address);
            stored.put("shipMethod", method);
            if (StringUtils.hasText(remark)) {
                stored.put("remark", remark);
            }
            if (!shipmentItems.isEmpty()) {
                stored.put("items", shipmentItems);
            }
            String rawJson = objectMapper.writeValueAsString(stored);
            int u = haidianJdbcTemplate.update(
                    """
                            UPDATE mcp_shipment_request_log SET recipient_name=?, recipient_phone=?, address=?, ship_method=?, remark=?, raw_request_data=?
                            WHERE shipment_id=?
                            """,
                    name,
                    phone.trim(),
                    address,
                    method,
                    StringUtils.hasText(remark) ? remark : null,
                    rawJson,
                    shipmentId.trim());
            result.put("code", 0);
            result.put("msg", u > 0 ? "ok" : "未找到对应发货单");
            result.put("data", Map.of("shipmentId", shipmentId, "updated", u, "itemCount", shipmentItems.size()));
            return result;
        } catch (Exception e) {
            log.error("updateShipmentAuditData 失败 shipmentId={}", shipmentId, e);
            result.put("code", 500);
            result.put("msg", "更新失败：" + e.getMessage());
            result.put("data", null);
            return result;
        }
    }

    @Override
    public Map<String, Object> markShipmentShipped(String shipmentId) {
        Map<String, Object> result = new HashMap<>();
        if (!StringUtils.hasText(shipmentId)) {
            result.put("code", 400);
            result.put("msg", "shipmentId 不能为空");
            result.put("data", null);
            return result;
        }
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", null);
            return result;
        }
        try {
            java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
            int u = haidianJdbcTemplate.update(
                    "UPDATE mcp_shipment_request_log SET ship_status = 1, ship_time = ? WHERE shipment_id = ? AND ship_status = 0",
                    now, shipmentId.trim());
            if (u > 0) {
                result.put("code", 0);
                result.put("msg", "ok");
                result.put("data", Map.of("shipmentId", shipmentId, "shipStatus", 1));
                return result;
            }
            List<Map<String, Object>> r = haidianJdbcTemplate.queryForList(
                    "SELECT ship_status FROM mcp_shipment_request_log WHERE shipment_id = ?", shipmentId.trim());
            if (r.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "未找到发货单");
                result.put("data", null);
                return result;
            }
            Object stObj = r.get(0).get("ship_status");
            int st = stObj instanceof Number ? ((Number) stObj).intValue() : -1;
            if (st == 1) {
                result.put("code", 400);
                result.put("msg", "该单已发货");
                result.put("data", Map.of("shipmentId", shipmentId));
                return result;
            }
            result.put("code", 500);
            result.put("msg", "更新失败");
            result.put("data", null);
            return result;
        } catch (Exception e) {
            log.error("markShipmentShipped 失败 shipmentId={}", shipmentId, e);
            result.put("code", 500);
            result.put("msg", e.getMessage());
            result.put("data", null);
            return result;
        }
    }

    /**
     * 群名称形如「成都一丰百济百泽安-10389,10391」：后缀为门店编号，逗号分隔。
     * - 可见性：当前登录门店号在该集合中即可看见订单
     * - 编辑权限：仍以第一个门店号为准
     */
    private static final Pattern GROUP_NAME_STORE_SUFFIX = Pattern.compile("-([\\d,，\\s]+)$");

    private static List<String> storeIdsFromGroupNameSuffix(String groupName) {
        if (!StringUtils.hasText(groupName)) {
            return List.of();
        }
        String s = groupName.trim();
        Matcher m = GROUP_NAME_STORE_SUFFIX.matcher(s);
        if (!m.find()) {
            return List.of();
        }
        String tail = m.group(1).replaceAll("\\s+", "").replace('，', ',');
        if (!tail.matches("[\\d,]+")) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String p : tail.split(",")) {
            if (StringUtils.hasText(p)) {
                String v = p.trim();
                if (v.matches("\\d+") && !ids.contains(v)) {
                    ids.add(v);
                }
            }
        }
        return ids;
    }

    private static String firstStoreIdFromGroupNameSuffix(String groupName) {
        List<String> ids = storeIdsFromGroupNameSuffix(groupName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static String storeIdsCsvFromGroupNameSuffix(String groupName) {
        List<String> ids = storeIdsFromGroupNameSuffix(groupName);
        return ids.isEmpty() ? null : String.join(",", ids);
    }

    private String extractGroupNameForStoreGate(Map<String, Object> normalizedRequest) {
        if (normalizedRequest == null) {
            return null;
        }
        String g = str(normalizedRequest.get("groupName"));
        if (StringUtils.hasText(g)) {
            return g.trim();
        }
        Object chat = normalizedRequest.get("chatInfo");
        if (chat instanceof Map<?, ?> cm) {
            Object rn = cm.get("roomName");
            if (rn != null && StringUtils.hasText(String.valueOf(rn))) {
                return String.valueOf(rn).trim();
            }
        }
        return null;
    }

    private String currentMcpAuditStoreId() {
        try {
            if (!StpUtil.isLogin()) {
                return null;
            }
            Object sid = StpUtil.getSession().get("mcpAuditStoreId");
            if (sid == null) {
                return null;
            }
            String v = String.valueOf(sid).trim();
            return StringUtils.hasText(v) ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isMcpAuditAdminBypass() {
        try {
            if (!StpUtil.isLogin()) {
                return false;
            }
            Object v = StpUtil.getSession().get("mcpAuditAdminBypass");
            if (v instanceof Boolean b) {
                if (b) {
                    return true;
                }
            } else if ("true".equalsIgnoreCase(String.valueOf(v))) {
                return true;
            }
            // 兼容旧会话：未显式写 bypass 时，非 store:xxx 登录视作管理员。
            String loginId = StpUtil.getLoginIdAsString();
            return StringUtils.hasText(loginId) && !loginId.trim().startsWith("store:");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isMcpAuditStoreVisibleForGroupName(String groupName) {
        // 取消“按门店号限制可见范围”：
        // 列表查看权限放开，门店账号也可查看全部单子。
        // 具体编辑/下单权限仍由 isMcpAuditStoreAllowedForGroupName 控制。
        return true;
    }

    private boolean isMcpAuditStoreAllowedForGroupName(String groupName) {
        if (isMcpAuditAdminBypass()) {
            return true;
        }
        List<String> ids = storeIdsFromGroupNameSuffix(groupName);
        if (ids.isEmpty()) {
            return false;
        }
        try {
            String sessionStore = currentMcpAuditStoreId();
            if (!StringUtils.hasText(sessionStore)) {
                return false;
            }
            return ids.contains(sessionStore);
        } catch (Exception e) {
            log.warn("门店会话校验异常 groupName={}", groupName, e);
            return false;
        }
    }

    /** 私聊单（身份证触发）优先按 storeId 控制可操作；群单仍按群名称后缀控制。 */
    private boolean isMcpAuditStoreVisibleForRequest(Map<String, Object> normalizedRequest, String groupNameFallback) {
        // 列表可见性不再按门店过滤；仅在操作（编辑/下单）时校验门店权限。
        return true;
    }

    private boolean isMcpAuditStoreAllowedForRequest(Map<String, Object> normalizedRequest, String groupNameFallback) {
        if (isMcpAuditAdminBypass()) {
            return true;
        }
        String sessionStore = currentMcpAuditStoreId();
        if (!StringUtils.hasText(sessionStore)) {
            return false;
        }
        String reqTrigger = normalizedRequest == null ? null : str(normalizedRequest.get("requestTriggerType"));
        String storeId = extractStoreIdForGate(normalizedRequest, groupNameFallback);
        if ("idcard".equalsIgnoreCase(reqTrigger)) {
            return StringUtils.hasText(storeId) && sessionStore.equals(storeId);
        }
        return isMcpAuditStoreAllowedForGroupName(groupNameFallback);
    }

    private static String escapeMysqlLike(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String pickHospitalNameColumn(List<String> varcharCols) {
        if (varcharCols == null || varcharCols.isEmpty()) {
            return null;
        }
        String[] priority = new String[]{
                "hospital_name", "hospitalname", "hosp_name", "hospname", "hospital",
                "yyname", "yiyuanmingcheng", "mingcheng", "mc", "title", "name"
        };
        for (String p : priority) {
            for (String c : varcharCols) {
                if (c.equalsIgnoreCase(p)) {
                    return c;
                }
            }
        }
        for (String c : varcharCols) {
            if (c.toLowerCase(Locale.ROOT).contains("hospital")) {
                return c;
            }
        }
        for (String c : varcharCols) {
            if (c.contains("医院")) {
                return c;
            }
        }
        for (String c : varcharCols) {
            String lo = c.toLowerCase(Locale.ROOT);
            if (!lo.equals("id") && !lo.endsWith("_id") && !lo.equals("code") && !lo.endsWith("code")) {
                return c;
            }
        }
        return varcharCols.get(0);
    }

    /**
     * 探测海典库 hospitallist 表及医院名称列（失败则缓存 -1，避免每次打库）。
     */
    private boolean ensureHospitallistMeta() {
        if (hospitallistMetaState == 1) {
            return true;
        }
        if (hospitallistMetaState == -1) {
            return false;
        }
        synchronized (hospitallistMetaLock) {
            if (hospitallistMetaState != 0) {
                return hospitallistMetaState == 1;
            }
            if (haidianJdbcTemplate == null) {
                hospitallistMetaState = -1;
                return false;
            }
            try {
                List<Map<String, Object>> tr = haidianJdbcTemplate.queryForList("SHOW TABLES LIKE 'hospitallist'");
                if (tr.isEmpty()) {
                    tr = haidianJdbcTemplate.queryForList("SHOW TABLES LIKE 'Hospitallist'");
                }
                if (tr.isEmpty()) {
                    log.warn("海典库未找到 hospitallist 表");
                    hospitallistMetaState = -1;
                    return false;
                }
                String tableName = null;
                for (Object v : tr.get(0).values()) {
                    if (v != null) {
                        tableName = String.valueOf(v).trim();
                        break;
                    }
                }
                if (!StringUtils.hasText(tableName) || !MCP_SAFE_SQL_IDENT.matcher(tableName).matches()) {
                    hospitallistMetaState = -1;
                    return false;
                }
                List<Map<String, Object>> colRows = haidianJdbcTemplate.queryForList("SHOW COLUMNS FROM `" + tableName + "`");
                List<String> varcharCols = new ArrayList<>();
                for (Map<String, Object> row : colRows) {
                    String field = row.get("Field") == null ? null : String.valueOf(row.get("Field")).trim();
                    String type = row.get("Type") == null ? "" : String.valueOf(row.get("Type")).toLowerCase(Locale.ROOT);
                    if (!StringUtils.hasText(field) || !MCP_SAFE_SQL_IDENT.matcher(field).matches()) {
                        continue;
                    }
                    if (type.contains("varchar") || type.contains("char") || type.contains("text")) {
                        varcharCols.add(field);
                    }
                }
                String nameCol = pickHospitalNameColumn(varcharCols);
                if (!StringUtils.hasText(nameCol)) {
                    log.warn("hospitallist 表未识别到医院名称列");
                    hospitallistMetaState = -1;
                    return false;
                }
                hospitallistTableNameCache = tableName;
                hospitallistNameColumnCache = nameCol;
                hospitallistMetaState = 1;
                return true;
            } catch (Exception e) {
                log.warn("探测 hospitallist 失败: {}", e.getMessage());
                hospitallistMetaState = -1;
                return false;
            }
        }
    }

    @Override
    public Map<String, Object> searchHospitalList(String keyword) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", List.of());
            return result;
        }
        if (!ensureHospitallistMeta()) {
            result.put("code", 0);
            result.put("msg", "hospitallist 不可用或未配置");
            result.put("data", List.of());
            return result;
        }
        String tbl = hospitallistTableNameCache;
        String col = hospitallistNameColumnCache;
        if (!StringUtils.hasText(tbl) || !StringUtils.hasText(col)) {
            result.put("code", 0);
            result.put("msg", "hospitallist 元数据异常");
            result.put("data", List.of());
            return result;
        }
        try {
            String kw = keyword == null ? "" : keyword.trim();
            String sql;
            List<Object> args = new ArrayList<>();
            if (!StringUtils.hasText(kw)) {
                sql = "SELECT `" + col + "` AS n FROM `" + tbl + "` WHERE `" + col + "` IS NOT NULL AND TRIM(`" + col + "`) <> '' ORDER BY `" + col + "` ASC LIMIT 100";
            } else {
                String lik = "%" + escapeMysqlLike(kw) + "%";
                sql = "SELECT DISTINCT `" + col + "` AS n FROM `" + tbl + "` WHERE `" + col + "` LIKE ? ESCAPE '\\\\' AND TRIM(`" + col + "`) <> '' ORDER BY `" + col + "` ASC LIMIT 100";
                args.add(lik);
            }
            List<Map<String, Object>> rows = args.isEmpty()
                    ? haidianJdbcTemplate.queryForList(sql)
                    : haidianJdbcTemplate.queryForList(sql, args.toArray());
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<String> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object n = row.get("n");
                if (n == null) {
                    continue;
                }
                String s = String.valueOf(n).trim();
                if (!StringUtils.hasText(s) || seen.contains(s)) {
                    continue;
                }
                seen.add(s);
                out.add(s);
                if (out.size() >= 100) {
                    break;
                }
            }
            result.put("code", 0);
            result.put("msg", "ok");
            result.put("data", out);
            return result;
        } catch (org.springframework.jdbc.CannotGetJdbcConnectionException e) {
            log.warn("searchHospitalList 海典库连接失败");
            result.put("code", 0);
            result.put("msg", "海典库连接失败");
            result.put("data", List.of());
            return result;
        } catch (Exception e) {
            log.error("searchHospitalList 失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败：" + e.getMessage());
            result.put("data", List.of());
            return result;
        }
    }

    @Override
    public Map<String, Object> listPatientEducationOptions() {
        Map<String, Object> result = new HashMap<>();
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", List.of());
            return result;
        }
        try {
            // 兼容不同客户库字段命名：先查全量，再在 Java 侧做列名兼容与患教过滤。
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(
                    "SELECT * FROM corecmsuser ORDER BY id DESC LIMIT 3000");
            List<Map<String, Object>> list = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Map<String, Object> row : rows) {
                String userType = firstNonBlank(
                        blankToNull(str(row.get("userType"))),
                        blankToNull(getIgnoreCase(row, "usertype")),
                        blankToNull(getIgnoreCase(row, "user_type")),
                        blankToNull(getIgnoreCase(row, "roleType"))
                );
                String typeNorm = userType == null ? "" : userType.replace(" ", "").replace("　", "");
                boolean isPatientEdu = "患教".equals(typeNorm) || "销售&患教".equals(typeNorm)
                        || "销售患教".equals(typeNorm) || typeNorm.contains("患教");
                if (!isPatientEdu) {
                    continue;
                }
                String id = firstNonBlank(
                        blankToNull(str(row.get("id"))),
                        blankToNull(getIgnoreCase(row, "userId")),
                        blankToNull(getIgnoreCase(row, "userid")),
                        blankToNull(getIgnoreCase(row, "user_id"))
                );
                if (!StringUtils.hasText(id)) {
                    continue;
                }
                if (seen.contains(id)) {
                    continue;
                }
                seen.add(id);
                String name = firstNonBlank(
                        blankToNull(str(row.get("name"))),
                        blankToNull(getIgnoreCase(row, "userName")),
                        blankToNull(getIgnoreCase(row, "username")),
                        blankToNull(getIgnoreCase(row, "nickName")),
                        blankToNull(getIgnoreCase(row, "nickname")),
                        blankToNull(getIgnoreCase(row, "realName"))
                );
                String mobile = firstNonBlank(
                        blankToNull(str(row.get("mobile"))),
                        blankToNull(getIgnoreCase(row, "phone")),
                        blankToNull(getIgnoreCase(row, "telephone")),
                        blankToNull(getIgnoreCase(row, "tel"))
                );
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("name", name);
                m.put("mobile", mobile);
                m.put("userType", userType);
                StringBuilder label = new StringBuilder();
                label.append(StringUtils.hasText(name) ? name : "未命名患教");
                label.append("（").append(id).append("）");
                if (StringUtils.hasText(mobile)) {
                    label.append(" ").append(mobile);
                }
                m.put("label", label.toString());
                list.add(m);
                if (list.size() >= 800) {
                    break;
                }
            }
            result.put("code", 0);
            result.put("msg", "ok");
            result.put("data", list);
            return result;
        } catch (org.springframework.jdbc.CannotGetJdbcConnectionException e) {
            // 单接口降级：海典库连接失败时返回空列表，避免审核页下拉因 500 阻断。
            log.warn("listPatientEducationOptions 查询失败（海典库连接不可用，已降级为空列表）");
            result.put("code", 0);
            result.put("msg", "海典库连接失败，患教下拉已降级为空列表");
            result.put("data", List.of());
            return result;
        } catch (Exception e) {
            log.error("listPatientEducationOptions 查询失败", e);
        }
        result.put("code", 500);
        result.put("msg", "无法读取 corecmsuser 患教数据，请确认 corecmsuser 表存在且可访问");
        result.put("data", List.of());
        return result;
    }

    @Override
    public Map<String, Object> getStockInfoList(Integer page, Integer limit,
                                                String wareName, String storeId, String produceBatchNo,
                                                String productEntName, String approvalNo, String packageSpec,
                                                Integer minCode, Integer maxCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", List.of());
            result.put("count", 0);
            return result;
        }
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = limit == null || limit < 1 ? 30 : Math.min(limit, 200);
        int minQty = minCode == null ? 0 : minCode;
        int maxQty = maxCode == null ? 0 : maxCode;
        int offset = (pageNo - 1) * pageSize;
        try {
            List<Object> whereArgs = new ArrayList<>();
            StringBuilder where = new StringBuilder(" WHERE t.isUse = 0 ");
            if (StringUtils.hasText(storeId)) {
                where.append(" AND t.storeId LIKE ? ");
                whereArgs.add("%" + storeId.trim() + "%");
            }
            if (StringUtils.hasText(produceBatchNo)) {
                where.append(" AND t.produceBatchNo LIKE ? ");
                whereArgs.add("%" + produceBatchNo.trim() + "%");
            }
            if (StringUtils.hasText(wareName)) {
                where.append(" AND EXISTS (SELECT 1 FROM haidianGoods g WHERE g.warecode=t.warecode AND g.warename LIKE ?) ");
                whereArgs.add("%" + wareName.trim() + "%");
            }
            if (StringUtils.hasText(productEntName)) {
                where.append(" AND EXISTS (SELECT 1 FROM haidianGoods g WHERE g.warecode=t.warecode AND g.factoryname LIKE ?) ");
                whereArgs.add("%" + productEntName.trim() + "%");
            }
            if (StringUtils.hasText(approvalNo)) {
                where.append(" AND EXISTS (SELECT 1 FROM haidianGoods g WHERE g.warecode=t.warecode AND g.fileno LIKE ?) ");
                whereArgs.add("%" + approvalNo.trim() + "%");
            }
            if (StringUtils.hasText(packageSpec)) {
                where.append(" AND EXISTS (SELECT 1 FROM haidianGoods g WHERE g.warecode=t.warecode AND g.warespec LIKE ?) ");
                whereArgs.add("%" + packageSpec.trim() + "%");
            }
            StringBuilder having = new StringBuilder(" HAVING 1=1 ");
            List<Object> havingArgs = new ArrayList<>();
            if (minQty > 0) {
                having.append(" AND COUNT(t.id) >= ? ");
                havingArgs.add(minQty);
            }
            if (maxQty > 0) {
                having.append(" AND COUNT(t.id) <= ? ");
                havingArgs.add(maxQty);
            }

            String groupedFrom = """
                    FROM antisTraceableCode t
                    """ + where + """
                    GROUP BY t.storeId, t.warecode, t.produceBatchNo
                    """ + having;
            List<Object> pageArgs = new ArrayList<>(whereArgs);
            pageArgs.addAll(havingArgs);
            pageArgs.add(pageSize);
            pageArgs.add(offset);
            String pageSql = """
                    SELECT t.storeId AS storeId, t.warecode AS warecode, t.produceBatchNo AS produceBatchNo,
                           COUNT(t.id) AS stockQty
                    """ + groupedFrom + """
                    ORDER BY stockQty DESC, t.storeId ASC, t.warecode ASC, t.produceBatchNo ASC
                    LIMIT ? OFFSET ?
                    """;
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(pageSql, pageArgs.toArray());
            List<Object> countArgs = new ArrayList<>(whereArgs);
            countArgs.addAll(havingArgs);
            Integer total = haidianJdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM (SELECT 1 " + groupedFrom + ") x",
                    Integer.class,
                    countArgs.toArray());

            List<String> storeIds = rows.stream().map(x -> str(x.get("storeId"))).filter(StringUtils::hasText).distinct().toList();
            List<String> warecodes = rows.stream().map(x -> str(x.get("warecode"))).filter(StringUtils::hasText).distinct().toList();
            List<String> batchNos = rows.stream().map(x -> str(x.get("produceBatchNo"))).filter(StringUtils::hasText).distinct().toList();

            Map<String, Map<String, Object>> goodsDict = queryGoodsByWarecodes(warecodes);
            Map<String, String> storeDict = queryStoreNamesByStoreIds(storeIds);
            Map<String, Integer> useQtyDict = queryQtyByStoreWareBatch(
                    "SELECT storeId, warecode, produceBatchNo, COUNT(1) cnt FROM antisTraceableCode WHERE isUse=1 %s GROUP BY storeId, warecode, produceBatchNo",
                    storeIds, warecodes, batchNos);
            // 按当前业务要求：库存页暂不展示「导入/外流」，因此不再依赖 SysUser/sys_user 做用户归属门店映射。
            Map<String, Integer> importQtyDict = Map.of();
            Map<String, Integer> outQtyDict = Map.of();
            Map<String, Integer> inQtyDict = queryInQty(storeIds, warecodes, batchNos);
            Map<String, Integer> shQtyDict = queryShQty(storeIds, batchNos);

            List<Map<String, Object>> data = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                String sid = str(r.get("storeId"));
                String wc = str(r.get("warecode"));
                String batch = str(r.get("produceBatchNo"));
                String key3 = stockKey3(sid, wc, batch);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("storeId", sid);
                out.put("storeName", storeDict.getOrDefault(sid, ""));
                out.put("warecode", wc);
                out.put("produceBatchNo", batch);
                out.put("stockQty", toIntSafe(r.get("stockQty")));
                Map<String, Object> g = goodsDict.get(wc);
                out.put("wareName", g == null ? "" : str(g.get("warename")));
                out.put("productEntName", g == null ? "" : str(g.get("factoryname")));
                out.put("approvalNo", g == null ? "" : str(g.get("fileno")));
                out.put("packageSpec", g == null ? "" : str(g.get("warespec")));
                out.put("importQty", importQtyDict.getOrDefault(key3, 0));
                out.put("useQty", useQtyDict.getOrDefault(key3, 0));
                out.put("outQty", outQtyDict.getOrDefault(key3, 0));
                out.put("inQty", inQtyDict.getOrDefault(key3, 0));
                out.put("shQty", shQtyDict.getOrDefault(stockKey2(sid, batch), 0));
                data.add(out);
            }
            result.put("code", 0);
            result.put("msg", "数据调用成功");
            result.put("data", data);
            result.put("count", total == null ? 0 : total);
            result.put("page", pageNo);
            result.put("limit", pageSize);
            return result;
        } catch (Exception e) {
            log.error("getStockInfoList 查询失败", e);
            result.put("code", 500);
            result.put("msg", "库存信息查询失败: " + e.getMessage());
            result.put("data", List.of());
            result.put("count", 0);
            return result;
        }
    }

    @Override
    public Map<String, Object> syncStockInfoListDaily() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化");
            result.put("data", Map.of("inserted", 0, "pages", 0));
            return result;
        }
        try {
            // 全量覆盖：每日快照表，先清空再重建
            haidianJdbcTemplate.execute("TRUNCATE TABLE stock_info_list");

            final int pageSize = 200;
            int page = 1;
            int totalInserted = 0;
            int pages = 0;
            // 全量同步期间去重：避免分页排序边界导致重复键冲突
            Set<String> seenKeysGlobal = new HashSet<>();

            while (true) {
                Map<String, Object> pageRes = getStockInfoList(page, pageSize,
                        null, null, null,
                        null, null, null,
                        0, 0);
                if (!(pageRes.get("code") instanceof Number) || ((Number) pageRes.get("code")).intValue() != 0) {
                    result.put("code", 500);
                    result.put("msg", "分页拉取库存失败：" + pageRes.get("msg"));
                    result.put("data", Map.of("inserted", totalInserted, "pages", pages));
                    return result;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = pageRes.get("data") instanceof List ? (List<Map<String, Object>>) pageRes.get("data") : List.of();
                if (rows.isEmpty()) {
                    break;
                }
                pages++;

                // 已在同步开始处 TRUNCATE，全量覆盖不需要 ON DUPLICATE（且 MySQL 5.7 不支持 INSERT ... AS new）
                String sql = """
                        INSERT INTO stock_info_list (
                          store_id, store_name,
                          warecode, ware_name,
                          produce_batch_no,
                          stock_qty,
                          product_ent_name, approval_no, package_spec,
                          import_qty, use_qty, out_qty, in_qty, sh_qty,
                          sync_time
                        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
                        """;

                List<Object[]> args = new ArrayList<>(rows.size());
                for (Map<String, Object> r : rows) {
                    String key = stockKey3(str(r.get("storeId")), str(r.get("warecode")), str(r.get("produceBatchNo")));
                    if (seenKeysGlobal.contains(key)) {
                        continue;
                    }
                    seenKeysGlobal.add(key);
                    args.add(new Object[]{
                            str(r.get("storeId")),
                            str(r.get("storeName")),
                            str(r.get("warecode")),
                            str(r.get("wareName")),
                            str(r.get("produceBatchNo")),
                            toIntSafe(r.get("stockQty")),
                            str(r.get("productEntName")),
                            str(r.get("approvalNo")),
                            str(r.get("packageSpec")),
                            toIntSafe(r.get("importQty")),
                            toIntSafe(r.get("useQty")),
                            toIntSafe(r.get("outQty")),
                            toIntSafe(r.get("inQty")),
                            toIntSafe(r.get("shQty"))
                    });
                }
                int[] affected = haidianJdbcTemplate.batchUpdate(sql, args);
                for (int a : affected) {
                    if (a > 0) {
                        totalInserted += a;
                    }
                }

                if (rows.size() < pageSize) {
                    break;
                }
                page++;
            }

            result.put("code", 0);
            result.put("msg", "ok");
            result.put("data", Map.of("writtenRows", totalInserted, "pages", pages));
            return result;
        } catch (Exception e) {
            log.warn("syncStockInfoListDaily 同步失败：{}", e.getMessage(), e);
            result.put("code", 500);
            result.put("msg", "同步失败：" + e.getMessage());
            result.put("data", Map.of("writtenRows", 0));
            return result;
        }
    }

    private Map<String, Map<String, Object>> queryGoodsByWarecodes(List<String> warecodes) {
        if (warecodes == null || warecodes.isEmpty()) {
            return Map.of();
        }
        String in = String.join(",", Collections.nCopies(warecodes.size(), "?"));
        List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(
                "SELECT warecode, warename, factoryname, fileno, warespec FROM haidianGoods WHERE warecode IN (" + in + ")",
                warecodes.toArray());
        Map<String, Map<String, Object>> dict = new HashMap<>();
        for (Map<String, Object> row : rows) {
            dict.put(str(row.get("warecode")), row);
        }
        return dict;
    }

    private Map<String, String> queryStoreNamesByStoreIds(List<String> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Map.of();
        }
        String in = String.join(",", Collections.nCopies(storeIds.size(), "?"));
        List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(
                "SELECT busno, storeName FROM corecmsstore WHERE busno IN (" + in + ")",
                storeIds.toArray());
        Map<String, String> dict = new HashMap<>();
        for (Map<String, Object> row : rows) {
            dict.put(str(row.get("busno")), str(row.get("storeName")));
        }
        return dict;
    }

    private Map<String, Integer> queryQtyByStoreWareBatch(String sqlTpl, List<String> storeIds, List<String> warecodes, List<String> batchNos) {
        if (storeIds.isEmpty() || warecodes.isEmpty() || batchNos.isEmpty()) {
            return Map.of();
        }
        String storeIn = String.join(",", Collections.nCopies(storeIds.size(), "?"));
        String wareIn = String.join(",", Collections.nCopies(warecodes.size(), "?"));
        String batchIn = String.join(",", Collections.nCopies(batchNos.size(), "?"));
        String sql = String.format(sqlTpl, " AND storeId IN (" + storeIn + ") AND warecode IN (" + wareIn + ") AND produceBatchNo IN (" + batchIn + ")");
        List<Object> args = new ArrayList<>();
        args.addAll(storeIds);
        args.addAll(warecodes);
        args.addAll(batchNos);
        List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(sql, args.toArray());
        Map<String, Integer> dict = new HashMap<>();
        for (Map<String, Object> row : rows) {
            dict.put(stockKey3(str(row.get("storeId")), str(row.get("warecode")), str(row.get("produceBatchNo"))),
                    toIntSafe(row.get("cnt")));
        }
        return dict;
    }

    private Map<Integer, String> queryUserStoreMap(List<String> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        String in = String.join(",", Collections.nCopies(storeIds.size(), "?"));
        List<Map<String, Object>> rows;
        try {
            rows = haidianJdbcTemplate.queryForList(
                    "SELECT id, busno FROM sys_user WHERE busno IN (" + in + ")",
                    storeIds.toArray());
        } catch (Exception ignore) {
            rows = haidianJdbcTemplate.queryForList(
                    "SELECT id, busno FROM SysUser WHERE busno IN (" + in + ")",
                    storeIds.toArray());
        }
        Map<Integer, String> dict = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Integer uid = toIntSafe(row.get("id"));
            String sid = str(row.get("busno"));
            if (uid != null && StringUtils.hasText(sid) && !dict.containsKey(uid)) {
                dict.put(uid, sid);
            }
        }
        return dict;
    }

    private List<Map<String, Object>> queryAtcLight(List<String> warecodes, List<String> batchNos) {
        if (warecodes.isEmpty() || batchNos.isEmpty()) {
            return List.of();
        }
        String wareIn = String.join(",", Collections.nCopies(warecodes.size(), "?"));
        String batchIn = String.join(",", Collections.nCopies(batchNos.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.addAll(warecodes);
        args.addAll(batchNos);
        return haidianJdbcTemplate.queryForList(
                "SELECT storeId, warecode, produceBatchNo, addUser FROM antisTraceableCode WHERE warecode IN (" + wareIn + ") AND produceBatchNo IN (" + batchIn + ")",
                args.toArray());
    }

    private Map<String, Integer> queryImportQtyByUserStore(Map<Integer, String> userStoreMap, List<String> warecodes, List<String> batchNos) {
        if (userStoreMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> dict = new HashMap<>();
        for (Map<String, Object> row : queryAtcLight(warecodes, batchNos)) {
            Integer uid = toIntSafe(row.get("addUser"));
            String belongStore = uid == null ? null : userStoreMap.get(uid);
            if (!StringUtils.hasText(belongStore)) {
                continue;
            }
            String key = stockKey3(belongStore, str(row.get("warecode")), str(row.get("produceBatchNo")));
            dict.put(key, dict.getOrDefault(key, 0) + 1);
        }
        return dict;
    }

    private Map<String, Integer> queryOutQtyByUserStore(Map<Integer, String> userStoreMap, List<String> warecodes, List<String> batchNos) {
        if (userStoreMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> dict = new HashMap<>();
        for (Map<String, Object> row : queryAtcLight(warecodes, batchNos)) {
            Integer uid = toIntSafe(row.get("addUser"));
            String belongStore = uid == null ? null : userStoreMap.get(uid);
            String actualStore = str(row.get("storeId"));
            if (!StringUtils.hasText(belongStore) || belongStore.equals(actualStore)) {
                continue;
            }
            String key = stockKey3(belongStore, str(row.get("warecode")), str(row.get("produceBatchNo")));
            dict.put(key, dict.getOrDefault(key, 0) + 1);
        }
        return dict;
    }

    private Map<String, Integer> queryInQty(List<String> storeIds, List<String> warecodes, List<String> batchNos) {
        if (storeIds.isEmpty() || warecodes.isEmpty() || batchNos.isEmpty()) {
            return Map.of();
        }
        String storeIn = String.join(",", Collections.nCopies(storeIds.size(), "?"));
        String wareIn = String.join(",", Collections.nCopies(warecodes.size(), "?"));
        String batchIn = String.join(",", Collections.nCopies(batchNos.size(), "?"));
        String sql = """
                SELECT ar.storeS AS storeId, atc.warecode AS warecode, atc.produceBatchNo AS produceBatchNo, COUNT(ari.code) AS cnt
                FROM antisRequisitionItem ari
                JOIN antisRequisition ar ON ari.requisitionId = ar.code
                JOIN antisTraceableCode atc ON ari.code = atc.code
                WHERE ar.storeS IN (%s) AND ar.status='已完成' AND atc.warecode IN (%s) AND atc.produceBatchNo IN (%s)
                GROUP BY ar.storeS, atc.warecode, atc.produceBatchNo
                """.formatted(storeIn, wareIn, batchIn);
        List<Object> args = new ArrayList<>();
        args.addAll(storeIds);
        args.addAll(warecodes);
        args.addAll(batchNos);
        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(sql, args.toArray());
            Map<String, Integer> dict = new HashMap<>();
            for (Map<String, Object> row : rows) {
                dict.put(stockKey3(str(row.get("storeId")), str(row.get("warecode")), str(row.get("produceBatchNo"))),
                        toIntSafe(row.get("cnt")));
            }
            return dict;
        } catch (Exception e) {
            log.warn("库存调入统计查询失败，已降级为0", e);
            return Map.of();
        }
    }

    private Map<String, Integer> queryShQty(List<String> storeIds, List<String> batchNos) {
        if (storeIds.isEmpty() || batchNos.isEmpty()) {
            return Map.of();
        }
        String storeIn = String.join(",", Collections.nCopies(storeIds.size(), "?"));
        String batchIn = String.join(",", Collections.nCopies(batchNos.size(), "?"));
        String sql = """
                SELECT g.busno AS storeId, g.makeno AS batch, COUNT(o.orderId) AS cnt
                FROM CoreCmsGoods g
                JOIN CoreCmsOrderItem oi ON g.id = oi.goodsId
                JOIN CoreCmsOrder o ON oi.orderId = o.orderId
                WHERE g.busno IN (%s) AND g.makeno IN (%s) AND o.is_Y3=1 AND o.status<>3
                GROUP BY g.busno, g.makeno
                """.formatted(storeIn, batchIn);
        List<Object> args = new ArrayList<>();
        args.addAll(storeIds);
        args.addAll(batchNos);
        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(sql, args.toArray());
            Map<String, Integer> dict = new HashMap<>();
            for (Map<String, Object> row : rows) {
                dict.put(stockKey2(str(row.get("storeId")), str(row.get("batch"))), toIntSafe(row.get("cnt")));
            }
            return dict;
        } catch (Exception e) {
            log.warn("库存待审统计查询失败，已降级为0", e);
            return Map.of();
        }
    }

    private Integer toIntSafe(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            String s = String.valueOf(value).trim();
            if (!StringUtils.hasText(s)) {
                return null;
            }
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String stockKey3(String storeId, String warecode, String batch) {
        return (storeId == null ? "" : storeId) + "|" + (warecode == null ? "" : warecode) + "|" + (batch == null ? "" : batch);
    }

    private String stockKey2(String storeId, String batch) {
        return (storeId == null ? "" : storeId) + "|" + (batch == null ? "" : batch);
    }

    @Override
    public Map<String, Object> listMiniProgramStores() {
        Map<String, Object> result = new HashMap<>();
        if (mcpStoreLoginService != null) {
            try {
                mcpStoreLoginService.ensureLocalTable();
                List<Map<String, Object>> local = mcpStoreLoginService.listSyncedStores();
                if (!local.isEmpty()) {
                    result.put("code", 0);
                    result.put("msg", "ok");
                    result.put("data", local);
                    result.put("source", "local");
                    return result;
                }
            } catch (Exception e) {
                log.debug("listMiniProgramStores 本地 jm_mcp_store_login 不可用，回退海典", e);
            }
        }
        if (haidianJdbcTemplate == null) {
            result.put("code", 500);
            result.put("msg", "海典同步库未初始化且本地无门店缓存，请检查 haidian.datasource 或等待同步");
            result.put("data", List.of());
            return result;
        }
        String[] candidates = new String[]{
                "SELECT id, storeName AS label FROM corecmsstore ORDER BY id ASC LIMIT 800",
                "SELECT id, name AS label FROM corecmsstore ORDER BY id ASC LIMIT 800",
                "SELECT storeId AS id, storeName AS label FROM corecmsstore ORDER BY storeId ASC LIMIT 800"
        };
        for (String sql : candidates) {
            try {
                List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(sql);
                List<Map<String, Object>> list = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Object id = row.get("id");
                    if (id == null) {
                        continue;
                    }
                    Object label = row.get("label");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(id).trim());
                    m.put("label", label != null ? String.valueOf(label) : String.valueOf(id));
                    list.add(m);
                }
                result.put("code", 0);
                result.put("msg", "ok");
                result.put("data", list);
                result.put("source", "haidian");
                return result;
            } catch (Exception e) {
                log.debug("listMiniProgramStores 尝试 SQL 失败: {}", sql, e);
            }
        }
        result.put("code", 500);
        result.put("msg", "无法读取 corecmsstore，请确认表存在且包含 id + storeName（或 name / storeId）等字段");
        result.put("data", List.of());
        return result;
    }
}
