package com.jeecg.modules.jmreport.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jeecg.modules.jmreport.service.GroupNameTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GroupNameTokenServiceImpl implements GroupNameTokenService {

    @Autowired(required = false)
    @Qualifier("haidianJdbcTemplate")
    private JdbcTemplate haidianJdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final List<String> GENERIC_SUFFIXES = List.of(
            "患者服务群", "患者交流群", "患者群", "服务群", "交流群", "通知群", "福利群", "业务群", "工作群",
            "旗舰门店", "旗舰店", "门店", "店", "群");

    private static final Set<String> NOISE_TOKENS = Set.of(
            "患者", "服务", "患者群", "服务群", "交流群", "通知群", "福利群", "业务群", "工作群", "旗舰", "门店", "群");
    private static final Pattern HOSPITAL_TOKEN_PATTERN =
            Pattern.compile("([一二三四五六七八九十0-9]{1,3}(医院|院区|分院|总院|院))");

    private static String stripGenericSuffix(String input) {
        if (!StringUtils.hasText(input)) {
            return input;
        }
        String t = input.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String s : GENERIC_SUFFIXES) {
                if (t.length() > s.length() + 1 && t.endsWith(s)) {
                    t = t.substring(0, t.length() - s.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        return t;
    }

    private static boolean isUsefulToken(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        String t = token.trim();
        if (t.length() < 2 || t.length() > 8) {
            return false;
        }
        if (NOISE_TOKENS.contains(t)) {
            return false;
        }
        return !t.matches("^\\d+$");
    }

    private static List<String> autoSegmentTokens(String groupName) {
        if (!StringUtils.hasText(groupName)) {
            return List.of();
        }
        String gn = groupName.trim();
        Set<String> out = new LinkedHashSet<>();

        // 先按常见分隔符切分（空格、横杠、括号、中文顿号等）
        String[] parts = gn.split("[\\s\\-_/|,，、;；:：()（）\\[\\]【】]+");
        for (String p : parts) {
            if (!StringUtils.hasText(p)) {
                continue;
            }
            String t = p.trim().replace("（", "").replace("）", "");
            if (isUsefulToken(t)) {
                out.add(t);
            }

            // 去掉“患者服务群/旗舰店/群”等泛化后缀，提取核心词
            String core = stripGenericSuffix(t);
            if (isUsefulToken(core)) {
                out.add(core);
            }

            // 显式提取机构词：二医院/三医院/一院区/二分院 等
            Matcher hm = HOSPITAL_TOKEN_PATTERN.matcher(t);
            while (hm.find()) {
                String hospitalToken = hm.group(1);
                if (isUsefulToken(hospitalToken)) {
                    out.add(hospitalToken);
                }
            }

            // 对 4 字品牌串做 2+2 切分（例如 一丰恒瑞 -> 一丰、恒瑞）
            if (core != null && core.matches("[\\u4e00-\\u9fa5]{4}")) {
                out.add(core.substring(0, 2));
                out.add(core.substring(2, 4));
            }

            // 对“成都旗舰店”这类保留前缀地名词
            if (t.matches("[\\u4e00-\\u9fa5]{2,6}(旗舰店|门店|药房|医院|院区|分院|总院|店)$")) {
                String prefix = t.replaceAll("(旗舰店|门店|药房|医院|院区|分院|总院|店)$", "");
                if (isUsefulToken(prefix)) {
                    out.add(prefix);
                }
            }
        }
        if (out.isEmpty() && gn.length() >= 2) {
            String core = stripGenericSuffix(gn);
            if (isUsefulToken(core)) {
                out.add(core);
            }
        }
        return new ArrayList<>(out);
    }

    private List<String> parseSegmentWords(Object segObj) {
        if (segObj == null || !StringUtils.hasText(String.valueOf(segObj))) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(String.valueOf(segObj));
            if (!arr.isArray() || arr.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) {
                if (n != null && n.isTextual() && isUsefulToken(n.asText().trim())) {
                    out.add(n.asText().trim());
                } else if (n != null && n.isNumber() && isUsefulToken(n.asText())) {
                    out.add(n.asText());
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> matchGlobalConfiguredTokens(String groupName) {
        if (haidianJdbcTemplate == null || !StringUtils.hasText(groupName)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList("SELECT segment_words FROM mcp_chat_group_config");
            Set<String> all = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                all.addAll(parseSegmentWords(row.get("segment_words")));
            }
            List<String> hit = new ArrayList<>();
            for (String token : all) {
                if (StringUtils.hasText(token) && groupName.contains(token)) {
                    hit.add(token);
                }
            }
            hit.sort(Comparator.comparingInt(String::length).reversed());
            return hit;
        } catch (Exception e) {
            log.debug("全局配置分词匹配失败，groupName={}", groupName, e);
            return List.of();
        }
    }

    @Override
    public List<String> resolveTokens(String groupName) {
        if (!StringUtils.hasText(groupName)) {
            return List.of();
        }
        String gn = groupName.trim();
        if (haidianJdbcTemplate == null) {
            List<String> auto = autoSegmentTokens(gn);
            return auto.isEmpty() ? List.of(gn) : auto;
        }
        try {
            List<Map<String, Object>> rows = haidianJdbcTemplate.queryForList(
                    "SELECT segment_words FROM mcp_chat_group_config WHERE group_name = ? LIMIT 1", gn);
            if (rows != null && !rows.isEmpty()) {
                List<String> exactCfg = parseSegmentWords(rows.get(0).get("segment_words"));
                if (!exactCfg.isEmpty()) {
                    return Collections.unmodifiableList(exactCfg);
                }
            }

            // 无精确配置时：先用“全局配置词库命中”，再用规则分词兜底，避免出现大量无意义碎片词
            Set<String> out = new LinkedHashSet<>(matchGlobalConfiguredTokens(gn));
            out.addAll(autoSegmentTokens(gn));
            if (out.isEmpty()) {
                return List.of(gn);
            }
            return new ArrayList<>(out);
        } catch (Exception e) {
            log.debug("解析群分词失败，回退自动分词 groupName={}", gn, e);
            List<String> auto = autoSegmentTokens(gn);
            return auto.isEmpty() ? List.of(gn) : auto;
        }
    }

    @Override
    public void attachGroupTokens(Map<String, Object> normalized) {
        if (normalized == null) {
            return;
        }
        String gn = normalized.get("groupName") == null ? "" : String.valueOf(normalized.get("groupName")).trim();
        if (!StringUtils.hasText(gn)) {
            normalized.put("groupTokens", new ArrayList<String>());
            return;
        }
        List<String> tokens = resolveTokens(gn);
        normalized.put("groupTokens", new ArrayList<>(tokens));
    }
}
