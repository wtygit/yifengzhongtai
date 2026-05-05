package com.jeecg.modules.jmreport.service;

import java.util.List;
import java.util.Map;

/**
 * 根据群名称从统一配置表解析分词；无配置时退化为整段群名作为单一分词。
 */
public interface GroupNameTokenService {

    List<String> resolveTokens(String groupName);

    /**
     * 向归一化下单 Map 写入 groupTokens（List&lt;String&gt;），依赖已有 groupName。
     */
    void attachGroupTokens(Map<String, Object> normalized);

    static String buildSearchPipe(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("|");
        for (String t : tokens) {
            if (t == null) {
                continue;
            }
            String x = t.trim();
            if (x.isEmpty()) {
                continue;
            }
            x = x.replace("|", "");
            sb.append(x).append("|");
        }
        return sb.length() > 1 ? sb.toString() : null;
    }
}
