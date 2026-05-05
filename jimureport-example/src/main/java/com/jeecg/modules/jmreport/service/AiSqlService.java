package com.jeecg.modules.jmreport.service;

import java.util.Map;

/**
 * 面向 MCP 核心查询接口的 AI SQL 生成与执行入口。
 *
 * 设计目标：
 * - 不改变现有 MCP 工具名/URL，对外协议保持不变；
 * - 在各个业务查询方法内部，优先尝试通过 AI 生成更灵活的 SELECT SQL；
 * - 如 AI 不可用或生成失败，则回退到当前写死的兜底 SQL。
 *
 * 注意：当前默认实现仅返回 null，即“不开启 AI”，行为与旧版一致。
 *       真正对接大模型时，请替换为可用的实现。
 */
public interface AiSqlService {

    /**
     * 针对指定 MCP 工具执行“智能 SQL 查询”。
     *
     * @param toolName MCP 工具名，例如 core_order_query / core_insurance_query / core_drug_query
     * @param params   业务参数（orderId / idCard / keyword 等），用于向大模型描述约束条件
     * @return 统一返回结构：{code, msg, data}；返回 null 或抛异常时，由调用方自行兜底
     * @throws Exception 实现可抛出任何异常，调用方会统一捕获并回退到兜底 SQL
     */
    Map<String, Object> queryByAi(String toolName, Map<String, Object> params) throws Exception;
}

