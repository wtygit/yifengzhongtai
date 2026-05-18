package com.jeecg.modules.jmreport.service;

import java.util.Map;

/**
 * MCP 核心服务：订单查询 & 医保/患者档案查询 & 药品查询 & 回访策略 & 下单占位
 */
public interface McpCoreQueryService {

    /**
     * 根据订单号查询核心订单信息
     *
     * @param orderId 订单号
     * @return 统一返回结构：{code, msg, data}
     */
    Map<String, Object> queryOrderByOrderId(String orderId);

    /**
     * 根据 userId / mobile / idCard 查询医保/患者档案信息
     *
     * @param userId 海典用户ID
     * @param mobile 手机号
     * @param idCard 身份证号
     * @return 统一返回结构：{code, msg, data}
     */
    Map<String, Object> queryInsurance(Long userId, String mobile, String idCard);

    /**
     * 药品查询
     *
     * @param keyword  关键字（药品名称、拼音码等，支持模糊匹配）
     * @param barCode  条码（精准匹配，可为空）
     * @return 统一返回结构：{code, msg, data}
     */
    Map<String, Object> queryDrug(String keyword, String barCode);

    /**
     * 回访策略查询（当前无真实数据，固定返回“暂无数据支持”）
     *
     * @param businessId 业务标识（如订单号、患者ID 等，可为空）
     * @return 统一返回结构：{code, msg, data}
     */
    Map<String, Object> queryVisitStrategy(String businessId);

    /**
     * 下单接口：
     * - 支持结构化入参（推荐）：患者信息 + 药品列表；
     * - 兼容 requestJson（自然语言/字符串）方式：由服务端做弱结构解析（不推荐，容易解析失败）。
     *
     * @param requestJson 兼容参数：业务侧下单请求字符串（可为自然语言描述）
     * @param request     结构化请求对象（Map），字段见 tools/list 的 inputSchema
     * @return 统一返回结构：{code, msg, data}
     */
    Map<String, Object> createOrderPlaceholder(String requestJson);

    /**
     * 下单接口（结构化入参，推荐）。
     *
     * @param request 结构化入参：至少手机号（或 mobile/phone）、非空 items、或 requestJson 之一；姓名与身份证可选
     * @return 统一返回结构：{code, msg, data}
     */
    Map<String, Object> createOrder(Map<String, Object> request);

    /**
     * 小程序回调更新订单状态（更新海典同步库 mcp_order_create_order_log）。
     *
     * @param orderId   对外订单号（优先使用）
     * @param pendingId 本服务生成的 pendingId（可选）
     * @param status    订单状态：预下单 / 下单成功 / 退单
     * @return 统一返回结构：{code, msg, data}
     */
    Map<String, Object> updateOrderStatus(String orderId, String pendingId, Integer statusCode, String status,
                                          String invoiceInfo, String callbackData, String receiverName, String completionImagesJson);

    /**
     * 建档接口：写入 ocrsichuanyibao。仅传 11 位手机号即可；无手机号时需姓名与身份证两项。
     *
     * @param name   用户姓名（可选）
     * @param idCard 18位身份证号（可选）
     * @param mobile 11位手机号（有则优先作为建档门槛）
     * @return 统一返回结构：{code, msg, data}，data 为插入后的记录摘要
     */
    Map<String, Object> createProfile(String name, String idCard, String mobile);

    /**
     * 创建患者信息接口：写入海典数据库 corecmsuser 表
     *
     * @param name    患者姓名（必填）
     * @param phone   手机号（必填）
     * @param idCard  身份证号（可选）
     * @param gender  性别（可选）：男/女
     * @param age     年龄（可选）
     * @param address 地址（可选）
     * @param remark  备注（可选）
     * @return 统一返回结构：{code, msg, data}，data 为插入后的记录摘要
     */
    Map<String, Object> createPatient(String name, String phone, String idCard, String gender, Integer age, String address, String remark);

    /**
     * 测试海典数据源连接（调试用）
     *
     * @return 数据库连接信息
     */
    Map<String, Object> testHaidianDbConnection();

    /**
     * 审核通过：从表A读取数据，调用中台接口，写入表B，更新表A状态
     *
     * @param pendingId 待审核订单的pendingId
     * @param auditRemark 审核备注（可选）
     * @return 统一返回结构：{code, msg, data}
     */
    Map<String, Object> approveOrder(String pendingId, String auditRemark);

    /**
     * 审核驳回：更新表A状态为已驳回
     *
     * @param pendingId 待审核订单的pendingId
     * @param auditRemark 驳回原因（必填）
     * @return 统一返回结构：{code, msg, data}
     */
    Map<String, Object> rejectOrder(String pendingId, String auditRemark);

    /**
     * 作废订单（audit_status=4），作废后不可编辑/下单
     */
    Map<String, Object> voidOrder(String pendingId, String auditRemark);

    /**
     * 获取订单审核列表（供前端页面使用）
     *
     * @param status     审核状态筛选（0待审核 1已通过 2已驳回，为空则返回全部）
     * @param groupToken 可选：按群分词检索，订单 group_tokens_search 需包含该词（与配置表分词一致）
     * @return 统一返回结构：{code, msg, data}，data为订单列表
     */
    Map<String, Object> getOrderAuditList(String status, String groupToken,
                                          String pendingId, String patientName, String patientPhone, String patientIdCard, String groupName,
                                          String storeId,
                                          String createDateStart, String createDateEnd,
                                          String createTimeStart, String createTimeEnd,
                                          String requestTriggerType, String orderBizType);

    /**
     * 按群分词查询待审核相关订单（与审核列表结构一致，默认仅 audit_status=0）
     */
    Map<String, Object> queryOrdersByGroupToken(String groupToken);

    /**
     * 群名称配置列表（统一维护分词）
     */
    Map<String, Object> listChatGroupConfigs();

    /**
     * 群分词下拉选项（含使用次数，按使用次数倒序）
     */
    Map<String, Object> listGroupTokenOptions();

    /**
     * 患教下拉选项（来自海典 corecmsuser，userType 为「患教」或「销售&患教」）
     */
    Map<String, Object> listPatientEducationOptions();

    /**
     * 海典同步库 hospitallist 医院名称模糊查询（仅返回名称列表，供审核页 datalist）
     */
    Map<String, Object> searchHospitalList(String keyword);

    /**
     * 新增或更新群配置（body 含 id 可选、groupName 必填、segmentWords 为字符串数组）
     */
    Map<String, Object> saveChatGroupConfig(Map<String, Object> body);

    /**
     * 删除群配置
     */
    Map<String, Object> deleteChatGroupConfig(Long id);

    /**
     * 更新订单请求数据（保存用户修改的参数）
     *
     * @param pendingId 订单pendingId
     * @param userRequestData 修改后的用户请求数据
     * @return 统一返回结构：{code, msg, data}
     */
    Map<String, Object> updateOrderRequestData(String pendingId, Map<String, Object> userRequestData);

    /**
     * MCP 发货登记：姓名、地址、电话、邮寄方式必填；落库海典库 mcp_shipment_request_log。
     */
    Map<String, Object> createShipment(Map<String, Object> request);

    /**
     * 发货登记列表（页面）
     */
    Map<String, Object> getShipmentAuditList(String shipStatus, String shipmentId, String recipientPhone,
                                             String nameKeyword, String createDateStart, String createDateEnd);

    /**
     * 更新发货登记（待发货或已发货均可改信息；已发货仍可修正地址等）
     */
    Map<String, Object> updateShipmentAuditData(String shipmentId, Map<String, Object> fields);

    /**
     * 标记已发货：ship_status 0→1，写入 ship_time
     */
    Map<String, Object> markShipmentShipped(String shipmentId);

    /**
     * 库存信息列表（海典库）：按门店/商品/批次汇总可用追溯码库存，并补充导入、使用、外流、调入、待审核等统计。
     */
    Map<String, Object> getStockInfoList(Integer page, Integer limit,
                                         String wareName, String storeId, String produceBatchNo,
                                         String productEntName, String approvalNo, String packageSpec,
                                         Integer minCode, Integer maxCode);

    /**
     * 每日同步库存汇总到海典库 stock_info_list（全量覆盖）。
     *
     * @return 统一返回结构：{code, msg, data}，data含写入行数等信息
     */
    Map<String, Object> syncStockInfoListDaily();

    /**
     * 小程序门店表 corecmsstore 列表（供登录页 / MCP 审核页 选择门店）
     */
    Map<String, Object> listMiniProgramStores();
}

