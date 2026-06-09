package com.jeecg.modules.jmreport.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jeecg.modules.jmreport.config.DashScopeProperties;
import com.jeecg.modules.jmreport.service.AiSqlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 云雾大模型接入实现：
 * - 使用 chat completions 接口生成 SELECT SQL；
 * - 在本地做安全校验（仅允许单条 SELECT + 关键字黑名单 + LIMIT 保护）；
 * - 执行 SQL 并返回统一结构 {code, msg, data}；
 * - 任意环节失败则返回 null，由调用方继续走兜底 SQL。
 */
@Slf4j
@Service
public class AiSqlServiceImpl implements AiSqlService {

    @Autowired
    private DashScopeProperties dashScopeProperties;

    /**
     * 复用海典同步数据源，保持与现有 MCP 查询一致
     */
    @Autowired
    @Qualifier("haidianJdbcTemplate")
    private JdbcTemplate haidianJdbcTemplate;

    @Autowired
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 基于《table_structure_documentation.md》整理的数据库结构摘要。
     * 重点保留 AI 生成 SQL 时常用的表、关键字段和表间关系，方便大模型正确理解业务含义。
     */
    private static final String DB_SCHEMA_SUMMARY = """
        【总体说明】
        当前连接的库为 antis_yifengdata_hub，核心业务包括：订单 & 收货地址、零售销售流水 & 支付、门店库存、药品基础信息、药品追溯、采购价格调整、组织与基础数据等。
        下文仅列出 AI 生成 SQL 时最常用、最有业务含义的字段，不要求完全覆盖所有列；如需其它字段，可在此基础上类推。

        一、订单与会员 / 收货地址（corecms*）
        1）corecmsorder（订单表：存储订单金额、支付状态、发货状态等信息）
           - orderId：订单号（主键）
           - goodsAmount：商品总价
           - payedAmount：已支付金额
           - orderAmount：订单实际销售总额
           - payStatus：支付状态
           - shippingStatus / shipStatus：发货状态
           - status：订单状态
           - orderType：订单类型
           - sfwaybillNo：顺丰订单号
           - receiptType：收货方式
           - payType：支付方式
           - paymentCode：支付方式代码
           - paymentTime / payTime：支付时间
           - logisticsId：配送方式ID
           - logisticsName：配送方式名称
           - saleno：海典订单号
           - costFreight：配送费用
           - userId：用户ID
           - sellerId：店铺ID
           - storeId：自提门店ID
           - shipAreaId：收货地区ID
           - shipAddress：收货详细地址
           - shipName：收货人姓名
           - shipMobile / phone：收货电话
           - createTime：创建时间

        2）corecmsorderitem（订单明细表：每张订单下的商品明细）
           - id：序号，主键
           - orderId：订单ID，关联 corecmsorder.orderId
           - goodsId：商品ID
           - productId：货品ID
           - sn：货品编码
           - bn：商品编码
           - name：商品名称
           - price：货品单价
           - costprice：货品成本价
           - mktprice：市场价
           - nums：数量
           - amount：总价
           - promotionAmount：优惠金额
           - weight：总重量

        3）corecmsuser（用户表：存储患者/会员等用户信息）
           - id：用户ID（主键）
           - userName：用户名
           - passWord：密码
           - mobile：手机号
           - sex：性别（1男 2女 3未知）
           - birthday：生日
           - avatarImage：头像
           - nickName：昵称
           - balance：余额
           - point：积分
           - grade：用户等级
           - createTime：创建时间
           - updataTime：更新时间
           - status：状态（1正常 2停用）
           - parentId：推荐人
           - isDelete：删除标志
           - ShopId：门店id

        4）corecmsusership（用户收货地址表：患者/医保档案 + 收货地址）
           - id：主键
           - userId：用户ID（关联 corecmsuser.id）
           - areaId / regionId：收货地区ID
           - address：收货详细地址
           - name / consignee：收货人姓名（也可用作患者姓名）
           - mobile / phone：收货电话
           - bmrSfz：帮买人身份证
           - bmrCustomerId：帮买人法大大编号
           - hzgx：与患者关系
           - isDefault：是否默认
           - createTime：创建时间
           - updateTime：更新时间
           - transactionNo：实名认证序号
           - customerId：法大大id
           - idCard：身份证号
           - sex：性别

        使用建议：
        - 通过手机号/姓名查患者信息：优先使用 corecmsuser.mobile + corecmsusership.name/mobile，并结合 corecmsorder 过滤近期开单记录。
        - 订单 + 明细：corecmsorder LEFT JOIN corecmsorderitem ON corecmsorder.orderId = corecmsorderitem.orderId。

        二、零售销售流水 & 支付（t_sale_*）
        1）t_sale_h（零售销售表头：每一笔零售销售/小票）
           - SALENO：销售单号（主键，关联明细与支付记录）
           - BUSNO：门店编号（业务机构编码）
           - POSNO：POS机号
           - ACCDATE：记账日期（营业日期）
           - STARTTIME：开始交易时间
           - FINALTIME：结束交易时间（完成收银时间）
           - PAYEE：收银员工号
           - DISCOUNTER：折扣员工号
           - CREDITER：赊销审核人/挂账审核人
           - RETURNER：退货审核人
           - STDSUM：应收金额合计（原价合计）
           - NETSUM：实收金额合计（折后实际收款）
           - LOSS：抹零/舍入差额
           - MEMBERCARDNO：会员卡号/会员编码
           - PRECASH：预收款（备用）
           - SHIFTID：班次号
           - SHIFTDATE：班次日期
           - COMPID：公司ID
           - DOCTORID：医生编码（处方销售）
           - OLSHOPID：线上店铺ID
           - OLPICKNO：线上提货单号
           - NOTES：备注
           - ORDERNO：中台/外部订单号
           - HUB_FLAG：是否同步至中台标志

        2）t_sale_d（零售销售明细：每张小票下的商品明细）
           - SALENO：销售单号，关联 t_sale_h.SALENO
           - ROWNO：行号（同一销售单内的明细序号）
           - BUSNO：门店编号
           - ACCDATE：记账日期
           - WAREID：商品ID（关联商品维度表 t_ware / t_ware_base）
           - STALLNO：柜台/货架号
           - MAKENO：生产批号/内部批次号
           - STDPRICE：标准单价（原价）
           - NETPRICE：成交单价（折后价）
           - MINPRICE：最低限价
           - WAREQTY：销售数量（标准单位）
           - MINQTY：销售数量（最小单位）
           - STDTOMIN：标准单位与最小单位换算系数
           - GROUPID：组合编号/套餐组号
           - SALER：营业员/销售员工号
           - TIMES：销售时间
           - ROWTYPE：行类型（普通行/赠品行等）
           - PURPRICE：进货单价
           - AVGPURPRICE：加权平均进价
           - NETAMT：实收金额（本行成交金额）
           - DISRATE：折扣率
           - NOTINTEGRAL：是否不参与积分（1 表示不积分）
           - BATCHNO：外部批号/追溯批次号

        3）t_sale_pay（零售支付方式明细：一张销售单可拆分多种支付）
           - SALENO：销售单号，关联 t_sale_h.SALENO
           - PAYTYPE：支付方式编码（如现金、银联、微信、支付宝、医保、第三方平台等）
           - CARDNO：卡号（银行卡号/会员卡号/医保卡号等）
           - NETSUM：本支付方式实收金额
           - NETSUM_BAK：备份金额
           - PRICETYPE：价格类型（如会员价、协议价）
           - EXT_STR1 ~ EXT_STR5：扩展字符串字段
           - EXT_NUM1 ~ EXT_NUM5：扩展数值字段
           - EXT_DATE1 ~ EXT_DATE3：扩展日期字段
           - CASH_INTEGRAL：现金积分/本次支付产生或抵扣的积分金额

        使用建议：
        - 统计门店销售额：用 t_sale_h（BUSNO + ACCDATE + NETSUM）聚合即可，必要时 JOIN t_sale_pay 做支付方式拆分。
        - 统计销售明细：t_sale_h LEFT JOIN t_sale_d ON SALENO，再按 WAREID/药品名称等维度聚合。

        三、门店库存与商品（t_store_h、t_ware_base、t_ware）
        1）t_store_h（门店商品库存汇总表：按公司+门店+商品维度的库存快照）
           - COMPID：公司ID
           - BUSNO：门店编号
           - WAREID：商品ID（关联商品表）
           - SUMQTY：当前库存数量（门店维度当前可售库存的核心字段）
           - AUTOCOMPUTEMAXSTORE / AUTOCOMPUTEMINSTORE：是否自动计算最高/最低库存
           - MAXDAY / MINDAY：最高/最低库存天数（可供天数）
           - MAXSTORE / MINSTORE：最高/最低库存量
           - STOREPURPRICE：库存进价
           - LASTSALEDATE：最近销售日期
           - LASTAPPLYQTY / LASTAPPLYDATE：最近要货数量/日期
           - LASTDISTQTY / LASTDISTDATE：最近配货数量/日期
           - SUMAWAITQTY：待到货数量（已订未到的汇总，用于判断未来补货）
           - SUMPENDINGQTY：待处理数量
           - LOWESTQTY：历史最低库存量
           - SALEDAYQTY / DAYAVGQTY：日均销量/日均出库量
           - OOSDAYS：缺货天数

        2）t_ware_base（药品基础表：药品主数据，药品查询首选表）
           - WAREID：药品ID
           - WARENAME：药品商品名称
           - WAREGENERALNAME：通用名
           - WARESPEC：规格
           - FACTORYID：厂家ID
           - WAREUNIT：包装单位
           - FILENO：批准文号
           - WAREABC：名称简码
           - WARECODE：商品编码
           - AREACODE：区域编码
           - BARCODE：条形码（可包含多个条码）
           - LASTTIME：最后变更时间
           - STATUS：状态

        3）t_ware（部分库中存在的商品表，可视作商品维度的补充）
           - COMPID：公司ID
           - WAREID：商品ID
           - BARCODE：条形码
           - WAREABC：名称简码
           - WARENAME：商品名称
           - WAREGENERALNAME：通用名
           - WARESPEC：规格
           - FACTORYID：厂家ID
           - WAREUNIT：包装单位
           - WARECODE：商品编码
           - FILENO：批准文号

        使用建议：
        - 药品信息查询（keyword / barCode）优先使用 t_ware_base（按 WARENAME / WAREGENERALNAME / BARCODE / WAREABC 搜索）。
        - 库存相关查询，使用 t_store_h（BUSNO + WAREID + SUMQTY/SALEDAYQTY 等）；如需带出药品名称，再 JOIN t_ware_base。

        四、药品追溯（antistraceablecode）
        1）antistraceablecode（药品追溯码表：存储药品追溯码、批准文号、批次等信息）
           - id：序号，主键
           - code：追溯码
           - parent_code：父码
           - approvalNo：批准文号
           - warecode：商品编码
           - produceBatchNo：批号
           - productEntName：生产企业名称
           - pkgUnitDesc：包装单位描述
           - prepnSpec：制剂规格
           - produceDate：生产日期
           - prodName：药品商品名
           - packageSpec：产品包装规格
           - exprie_date：药品有效期至
           - storeId：门店编号
           - orderId：使用订单号

        使用建议：
        - 若通过追溯码（code）查药品信息，可先在 antistraceablecode 里查到 warecode / prodName，再用 warecode 对应到药品维度。

        五、医保 OCR 表（医保报销/就医记录，按身份证关联患者）
        当前库中实际存在的医保信息表为 ocrsichuanyibao（四川医保）。该表字段为下划线命名，身份证号字段为 shen_fen_zheng（不是 idCard），姓名为 xing_ming，创建时间为 create_time（不是 createTime）。其它字段示例：xing_bie（性别）、ren_ding_ji_gou_ming_cheng（认定机构名称）、can_bao_di（参保地）、shen_qing_bing_zhong（申请认定病种）、yi_shi_xing_ming（医师姓名）、yao_pin_tong_yong_ming（药品通用名）、lian_xi_dian_hua（联系电话）、lian_xi_di_zhi（联系地址）、create_time、up_time、remark、addUser。
        使用建议：按身份证查询医保记录时，必须写 WHERE shen_fen_zheng = '...'，排序用 ORDER BY create_time DESC，例如 SELECT * FROM ocrsichuanyibao WHERE shen_fen_zheng = '...' ORDER BY create_time DESC LIMIT 100。

        六、采购价格调整（t_adjust_purprice_h / t_adjust_purprice_d）
        1）t_adjust_purprice_h（采购价格调整表头：整体调整信息）
           - BILLNO：单据编号（主键）
           - BILLDATE：单据日期
           - SUPPLIERID / SUPPLIERNAME：供应商ID/名称
           - STATUS：状态
           - PROCESSORID / PROCESSOR：处理人ID/名称
           - PROCESSDATE：处理日期
           - AUDITORID / AUDITOR：审核人ID/名称
           - AUDITDATE：审核日期
           - CHECKERID / CHECKER：检查人ID/名称
           - CHECKDATE：检查日期
           - NOTES：备注
           - CREATEUSER / CREATETIME：创建人 / 创建时间
           - COMPID：公司ID

        2）t_adjust_purprice_d（采购价格调整明细：单品级调整）
           - BILLNO：单据编号（关联 t_adjust_purprice_h.BILLNO）
           - SEQNO：序列号
           - ITEMID / ITEMNAME：商品ID/名称
           - SPEC：规格
           - UNIT：单位
           - PRICE / NEWPRICE：原价格 / 新价格
           - QTY：数量
           - AMOUNT / NEWAMOUNT / DIFFAMOUNT：原金额 / 新金额 / 差异金额

        使用建议：
        - 分析采购调价记录：t_adjust_purprice_h JOIN t_adjust_purprice_d ON BILLNO，用 SUPPLIERID + BILLDATE + ITEMID 做统计。

        七、组织与基础数据（公司、门店、部门、地区、字典等）
        1）s_company（公司表）
           - COMPNAME：公司名称
           - COMPID：公司ID
           - COMPABC：公司简码
           - COMPSTATUS：公司状态
           - C_COMPCODE：公司编码

        2）s_busi（业务机构/门店表）
           - BUSNO：业务机构编号（门店编号）
           - BUSNAME：业务机构名称
           - BUSFULLNAME：业务机构全称
           - ADDRESS：地址
           - STATUS：状态

        3）s_dept_base（部门基础表）
           - DEPTID：部门ID
           - DEPTNAME：部门名称

        4）s_zone（库区表）
           - BUSNO：业务机构编号
           - ZONENO / ZONENAME：库区编号 / 名称

        5）t_area（地区表）
           - AREACODE：地区编码
           - AREANAME：地区名称
           - PROVINCE / CITY：省份 / 城市

        6）t_busno_class_base（门店分类）
           - CLASSCODE：分类编码
           - CLASSNAME：分类名称

        7）s_dddw_list（字典数据表）
           - DDDWNAME：字典名称
           - DDDWLISTDATA / DDDWLISTDISPLAY：字典数据及显示值

        八、用户与权限相关（s_user_*）
        1）s_user_base（用户基础表）
           - USERID：用户ID
           - USERNAME：用户姓名
           - DEPTID：部门ID

        2）s_user（用户状态表）
           - COMPID：公司ID
           - USERID：用户ID
           - STATUS：状态

        3）s_user_busi（用户与业务机构关系表）
           - COMPID：公司ID
           - USERID：用户ID
           - BUSNO：业务机构编号

        九、通用字段约定（多表通用的审计/状态字段）
        - 多数业务表都会包含 STATUS（状态）、STAMP（时间戳）、CREATEUSER/CREATETIME（创建人/时间）、LASTMODIFY/LASTTIME（最后修改人/时间）、NOTES（备注）等字段，
          生成 SQL 时可用于过滤有效记录或按时间排序。

        十、使用注意事项（提示 AI 模型）
        - 所有 SQL 必须是单条 SELECT 语句，不能包含 INSERT/UPDATE/DELETE/DDL。
        - 涉及大表（如 t_sale_h / t_sale_d / t_store_h）时，务必加 LIMIT（例如 LIMIT 100 或 LIMIT 200），并优先按时间/门店等条件过滤。
        - 药品信息查询：优先使用 t_ware_base；antistraceablecode 仅用于追溯码相关查询。
        - 患者/用户信息：优先通过 corecmsuser + corecmsusership，结合手机号（mobile）、姓名（name）和身份证（idCard）筛选。
        - 零售销售汇总：使用 t_sale_h / t_sale_d / t_sale_pay，而不是 corecmsorder（corecmsorder 更偏向中台/电商订单）。
        - 医保查询(core_insurance_query)：必须从 ocrsichuanyibao 表按 shen_fen_zheng（身份证）查询，WHERE 条件用 shen_fen_zheng = '...'，排序用 create_time DESC，不要用 idCard/createTime（该表无此列）。
        """;

    @Override
    public Map<String, Object> queryByAi(String toolName, Map<String, Object> params) throws Exception {
        // 1. 未配置 API Key：视为未启用 AI，走兜底 SQL
        String yunwuApiKey = dashScopeProperties.getApiKey();
        if (yunwuApiKey == null || yunwuApiKey.isEmpty()) {
            log.debug("Yunwu API key 未配置，跳过 AI SQL");
            return null;
        }

        // 2. 仅对核心查询工具启用 AI，其它工具不走 AI
        if (!"core_order_query".equals(toolName)
                && !"core_insurance_query".equals(toolName)
                && !"core_drug_query".equals(toolName)) {
            return null;
        }

        // 3. 组装 System / User 提示词
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(toolName, params);

        Map<String, Object> payload = Map.of(
                "model", dashScopeProperties.getModel(),
                "temperature", 0,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String requestBody = objectMapper.writeValueAsString(payload);
        String responseBody;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 云雾通常兼容 Bearer 方式，如有差异按官方文档调整
            headers.setBearerAuth(yunwuApiKey);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            responseBody = restTemplate.postForObject(dashScopeProperties.getUrl(), entity, String.class);
        } catch (Exception e) {
            log.warn("调用云雾大模型失败，toolName={}, params={}", toolName, params, e);
            return null;
        }

        String sql;
        try {
            sql = extractSqlFromResponse(responseBody);
        } catch (Exception e) {
            log.warn("解析云雾返回结果失败，toolName={}, params={}", toolName, params, e);
            return null;
        }
        if (sql == null || sql.isBlank()) {
            log.warn("AI 未生成 SQL，toolName={}, params={}", toolName, params);
            return null;
        }

        String safeSql;
        try {
            safeSql = toSafeSelectSql(sql);
        } catch (Exception e) {
            log.warn("AI 生成 SQL 未通过安全校验，toolName={}, params={}, sql={}", toolName, params, sql, e);
            return null;
        }

        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(safeSql);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("rows", rows);
            data.put("sql", safeSql);
            return Map.of("code", 0, "msg", "ok", "data", data);
        } catch (Exception e) {
            log.warn("执行 AI 生成的 SQL 失败，sql={}", safeSql, e);
            return null;
        }
    }

    private String buildSystemPrompt() {
        return """
            你是海典报表系统的 SQL 生成助手。
            目标：根据给定的业务场景和参数，结合下方提供的数据库表结构，生成一条只读查询 SQL。
            
            严格规则：
            - 只能生成一条 SQL 语句；
            - 只能使用 SELECT，不允许任何 INSERT / UPDATE / DELETE / REPLACE / TRUNCATE / ALTER / DROP / CREATE 等写操作；
            - 不要返回解释文字，只返回 SQL 本身；
            - 如需多表查询，请使用合适的 JOIN；
            - 字段名和表名必须来自下面提供的表结构说明，不要虚构。
            - 药品信息查询（keyword / barCode）优先使用 t_ware_base；antistraceablecode 是追溯码表，不是药品主数据表。
            
            数据库表结构说明（重要参考）：
            """ + DB_SCHEMA_SUMMARY;
    }

    private String buildUserPrompt(String toolName, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前 MCP 工具名: ").append(toolName).append("\\n");
        sb.append("当前请求参数: ").append(params).append("\\n");
        sb.append("请基于上述业务场景和参数，结合提供的表结构，生成一条符合 MySQL 语法的 SELECT 查询语句。\\n");
        sb.append("WHERE 条件必须包含这些参数的约束（例如 orderId、idCard、mobile、keyword、barCode 等）。\\n");
        sb.append("如果没有额外条件，请尽量限制返回记录数量（如按时间倒序取最近记录）。\\n");
        sb.append("禁止使用 mcp_user_profile 表（该表已下线，不允许出现在 SQL 中）。\\n");
        if ("core_order_query".equals(toolName)) {
            sb.append("对于订单查询(core_order_query)，只能从 corecmsorder 表查询，不能虚构其它表。")
              .append(" 当提供 orderId 时，必须使用 orderId 字段做等值匹配；")
              .append(" 如需按时间排序，请使用 createTime 或 payTime 字段按倒序排序；")
              .append(" 只选择与订单概览直接相关的字段（例如 orderId, orderNo, userId, totalAmount, payStatus, shippingStatus, createTime, payTime, status）。\\n");
        } else if ("core_insurance_query".equals(toolName)) {
            sb.append("对于医保查询(core_insurance_query)，需要同时查询两类信息：")
              .append("（1）患者档案信息：corecmsuser（用户基础档案） + corecmsusership（患者/医保档案+收货信息）；")
              .append("（2）医保报销/就医记录：ocrsichuanyibao（四川医保 OCR）。")
              .append(" 你只能输出一条 SELECT SQL，因此建议使用 JOIN：")
              .append(" corecmsusership LEFT JOIN corecmsuser ON corecmsusership.userId = corecmsuser.id；")
              .append(" 并在可能情况下，把 ocrsichuanyibao 也 LEFT JOIN 进来：")
              .append(" ocrsichuanyibao.shen_fen_zheng = corecmsusership.idCard（或 bmrSfz）。")
              .append(" 参数约束：userId 用 corecmsusership.userId / corecmsuser.id；mobile 用 corecmsusership.mobile 或 corecmsuser.mobile；idCard 用 corecmsusership.idCard 或 bmrSfz；")
              .append(" 重要：不要使用姓名字段 name 做 WHERE 条件（禁止按姓名查询）。")
              .append(" 若同时提供 mobile 与 idCard，应使用 OR（idCard/bmrSfz 命中 或 mobile 命中）保证能查到。")
              .append(" ocrsichuanyibao 的身份证列名是 shen_fen_zheng，时间列名是 create_time。")
              .append(" 结果请按 ocrsichuanyibao.create_time DESC 或 corecmsusership.updateTime DESC 排序，并 LIMIT 100。只返回一条 SELECT 语句。\\n");
        } else if ("core_drug_query".equals(toolName)) {
            sb.append("对于药品查询(core_drug_query)，请以 t_ware_base 作为主表，必要时结合 t_store_h 做库存统计，不要使用 antistraceablecode。")
              .append(" 当提供 keyword 参数时，请使用 WARENAME 和 WAREABC 字段做模糊匹配；")
              .append(" 当提供 barCode 参数时，请使用 BARCODE 字段做等值匹配或模糊匹配(= 或 LIKE)。")
              .append(" 查询结果中，除了药品基础字段（WAREID, WARENAME, WAREGENERALNAME, WARESPEC, FACTORYID, WAREUNIT, FILENO, WAREABC, WARECODE, BARCODE, LASTTIME），")
              .append(" 还应通过对 t_store_h 按 WAREID 聚合，补充返回库存及预计到货相关字段：")
              .append(" totalStock（SUM(SUMQTY)）、enrouteQty（SUM(ENROUTEQTY)）、awaitQty（SUM(SUMAWAITQTY)）、lastDistDate（MAX(LASTDISTDATE)）。")
              .append(" 结果请按 LASTTIME 字段倒序排序，并限制返回行数。\\n");
        }
        sb.append("只返回 SQL 语句本身，不要返回多余文字。");
        return sb.toString();
    }

    private String extractSqlFromResponse(String resp) throws Exception {
        if (resp == null || resp.isBlank()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(resp);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode msg = choices.get(0).path("message");
        String content = msg.path("content").asText(null);
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        // 处理 ```sql ... ``` 形式
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first >= 0 && last > first) {
                trimmed = trimmed.substring(first + 1, last).trim();
            }
        }
        return trimmed;
    }

    private String toSafeSelectSql(String sql) {
        String s = sql == null ? "" : sql.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("SQL 为空");
        }
        // 去掉尾部分号
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        String upper = s.toUpperCase(Locale.ROOT);
        // 不允许多语句
        if (upper.contains(";")) {
            throw new IllegalArgumentException("不允许多条 SQL 语句");
        }
        // 必须以 SELECT 开头
        if (!upper.startsWith("SELECT")) {
            throw new IllegalArgumentException("只允许 SELECT 语句，实际为: " + s);
        }
        // 简单危险关键字检查
        String[] forbidden = {" INSERT ", " UPDATE ", " DELETE ", " TRUNCATE ", " ALTER ", " DROP ", " CREATE ", " REPLACE "};
        for (String f : forbidden) {
            if (upper.contains(f)) {
                throw new IllegalArgumentException("生成的 SQL 包含危险关键字: " + f);
            }
        }
        if (upper.contains("MCP_USER_PROFILE")) {
            throw new IllegalArgumentException("生成的 SQL 不允许查询 mcp_user_profile（该表已下线）");
        }
        // 针对核心表做字段名/列清单兼容处理，避免引用不存在的列
        s = normalizeCoreOrderSql(s);
        s = normalizeAntistraceablecodeSql(s);
        s = normalizeCoreInsuranceSql(s);
        s = normalizeOcrSichuanyibaoSql(s);
        // 包一层 LIMIT，防止一次性查太多
        int maxRows = 200;
        return "SELECT * FROM (" + s + ") t LIMIT " + maxRows;
    }

    /**
     * 将 AI 可能生成的旧字段名（medId/medName 等）转换为实际表 antistraceablecode 中存在的字段名。
     */
    private String normalizeAntistraceablecodeSql(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.contains("FROM ANTISTRACEABLECODE")) {
            return sql;
        }
        String out = sql;
        out = replaceColumnWord(out, "medId", "warecode");
        out = replaceColumnWord(out, "traceableCode", "code");
        out = replaceColumnWord(out, "medName", "prodName");
        out = replaceColumnWord(out, "spec", "prepnSpec");
        out = replaceColumnWord(out, "unit", "pkgUnitDesc");
        out = replaceColumnWord(out, "barcode", "code");
        out = replaceColumnWord(out, "factoryName", "productEntName");
        out = replaceColumnWord(out, "batchNo", "produceBatchNo");
        out = replaceColumnWord(out, "productionDate", "produceDate");
        out = replaceColumnWord(out, "expiryDate", "exprie_date");
        out = replaceColumnWord(out, "createTime", "addTime");
        out = replaceColumnWord(out, "updateTime", "updatetime");
        return out;
    }

    /**
     * 规范 corecmsusership（医保/患者档案）相关 SQL：
     * - 有些大模型会选择旧版本字段名（如 consignee/phone/regionId），这里统一转换为当前表结构中的 name/mobile/areaId。
     */
    private String normalizeCoreInsuranceSql(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.contains("FROM CORECMSUSERSHIP")) {
            return sql;
        }
        String out = sql;
        out = replaceColumnWord(out, "consignee", "name");
        out = replaceColumnWord(out, "phone", "mobile");
        out = replaceColumnWord(out, "regionId", "areaId");
        return out;
    }

    /**
     * 规范 ocrsichuanyibao（四川医保 OCR）相关 SQL：
     * - 实际表结构使用 shen_fen_zheng（身份证）、create_time（创建时间），无 idCard/createTime 列。
     */
    private String normalizeOcrSichuanyibaoSql(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.contains("FROM OCRSICHUANYIBAO")) {
            return sql;
        }
        String out = sql;
        out = replaceColumnWord(out, "idCard", "shen_fen_zheng");
        out = replaceColumnWord(out, "createTime", "create_time");
        out = replaceColumnWord(out, "updateTime", "up_time");
        return out;
    }

    /**
     * 规范 corecmsorder 相关 SQL：
     * - 有些大模型会选择不存在的列（如 orderNo、createTime、shippingStatus），这里统一改成 SELECT *，
     *   保留原有 FROM / WHERE / ORDER BY / LIMIT 结构，避免 Unknown column 报错。
     */
    private String normalizeCoreOrderSql(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.contains("FROM CORECMSORDER")) {
            return sql;
        }
        // 将 "SELECT ... FROM corecmsorder" 规范为 "SELECT * FROM corecmsorder"
        // (?i) 忽略大小写，.+? 为非贪婪匹配，尽量只替换第一段 SELECT 列表
        return sql.replaceAll("(?is)SELECT\\s+.+?\\s+FROM\\s+corecmsorder", "SELECT * FROM corecmsorder");
    }

    /**
     * 仅在完整单词边界处替换列名，避免误伤别的标识符。
     */
    private String replaceColumnWord(String sql, String oldCol, String newCol) {
        return sql.replaceAll("(?i)(\\b)" + oldCol + "(\\b)", "$1" + newCol + "$2");
    }
}
