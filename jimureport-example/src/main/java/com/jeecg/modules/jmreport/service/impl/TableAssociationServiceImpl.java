package com.jeecg.modules.jmreport.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jeecg.modules.jmreport.dto.association.AssociationConfigDto;
import com.jeecg.modules.jmreport.service.TableAssociationService;
import com.jeecg.modules.jmreport.service.DataSourceMetaService;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSetMetaData;
import java.util.*;

@Slf4j
@Service
public class TableAssociationServiceImpl implements TableAssociationService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSourceMetaService dataSourceMetaService;
    @Autowired
    private com.jeecg.modules.jmreport.mapper.JmTableAssociationMapper jmTableAssociationMapper;

    /**
     * 多表关联「刷新预览」SQL 执行超时（秒），0 表示不设置。避免大 JOIN 拖死连接池。
     */
    @Value("${jmreport.table-association.preview-query-timeout-seconds:240}")
    private int previewQueryTimeoutSeconds;

    /**
     * 预览时对 FROM 第一张表先 LIMIT 抽样（仅预览执行用，保存数据集仍为全量 SQL）。0 表示关闭。
     */
    @Value("${jmreport.table-association.preview-main-row-cap:1200}")
    private int previewMainRowCap;

    /**
     * 预览时右表经关联键 IN 子查询过滤后再 LIMIT，避免「左表几千行 × 右表同一键海量行」拖垮 JOIN。
     */
    @Value("${jmreport.table-association.preview-join-side-row-cap:8000}")
    private int previewJoinSideRowCap;

    /**
     * 预览「仅两表 + 单条 INNER + 等值关联」时，改为两侧 ORDER BY 关联键后 LIMIT 再 JOIN，避免 EXISTS/IN 重复扫大表超时。
     */
    @Value("${jmreport.table-association.preview-dual-key-limited-join:true}")
    private boolean previewDualKeyLimitedJoinEnabled;

    private static final int DEFAULT_LIMIT = 200;
    /**
     * 多表关联物化表统一前缀，确保在目标库中易于识别且不与业务表冲突
     */
    private static final String TABLE_PREFIX = "zzzz_jm_ta_";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 主库未创建 jm_table_association（未执行 db/table_association_init.sql）时，列表类接口降级为空，避免 500。
     */
    private static boolean isJmTableAssociationMissing(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLSyntaxErrorException) {
                String m = t.getMessage();
                if (m != null && m.contains("jm_table_association")
                        && (m.contains("doesn't exist") || m.contains("不存在"))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String generateSql(AssociationConfigDto config, boolean forPreview) {
        return buildAssociationSql(config, forPreview, null);
    }

    /**
     * @param previewMainRowCap 非空且大于 0 时：将 FROM 首表包成 {@code (SELECT 所需列 FROM 首表 LIMIT n) 别名}，仅用于减轻预览 JOIN 超时；正式保存请传 null。
     */
    private String buildAssociationSql(AssociationConfigDto config, boolean forPreview, Integer previewMainRowCap) {
        if (config == null) {
            throw new IllegalArgumentException("config不能为空");
        }
        if (config.getTables() == null || config.getTables().isEmpty()) {
            throw new IllegalArgumentException("至少选择1张表");
        }

        String schema = forPreview ? getSchemaByDataSourceId(config.getDataSourceId()) : "";

        // alias -> tableName
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        for (AssociationConfigDto.TableDto t : config.getTables()) {
            if (!isSafeName(t.getTableName())) {
                throw new IllegalArgumentException("非法表名: " + t.getTableName());
            }
            if (t.getAlias() == null || t.getAlias().trim().isEmpty()) {
                throw new IllegalArgumentException("表别名不能为空");
            }
            if (!isSafeAlias(t.getAlias())) {
                throw new IllegalArgumentException("非法表别名: " + t.getAlias());
            }
            if (aliasToTable.containsKey(t.getAlias())) {
                throw new IllegalArgumentException("存在重复表别名: " + t.getAlias());
            }
            aliasToTable.put(t.getAlias(), t.getTableName());
        }

        List<AssociationConfigDto.FieldDto> fields = config.getFields() == null ? Collections.emptyList() : config.getFields();
        List<AssociationConfigDto.FieldDto> selected = new ArrayList<>();
        for (AssociationConfigDto.FieldDto f : fields) {
            if (Boolean.TRUE.equals(f.getSelected())) {
                selected.add(f);
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("请至少选择1个字段");
        }
        selected.sort(Comparator.comparingInt(o -> o.getOrderNum() == null ? 0 : o.getOrderNum()));

        // 计算字段配置
        List<AssociationConfigDto.ComputedFieldDto> computedFields =
                config.getComputedFields() == null ? Collections.emptyList() : config.getComputedFields();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");

        // 1) 普通字段
        List<String> groupByColumns = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            AssociationConfigDto.FieldDto f = selected.get(i);
            if (!isSafeAlias(f.getTableAlias()) || !aliasToTable.containsKey(f.getTableAlias())) {
                throw new IllegalArgumentException("字段所属表别名不存在: " + f.getTableAlias());
            }
            if (!isSafeName(f.getColumnName())) {
                throw new IllegalArgumentException("非法字段名: " + f.getColumnName());
            }
            String qualified = q(f.getTableAlias()) + "." + q(f.getColumnName());
            sql.append(qualified);
            String alias = f.getAlias();
            if (alias != null && !alias.trim().isEmpty()) {
                sql.append(" AS ").append(qAlias(alias));
            } else {
                // 默认别名: tableAlias_column
                sql.append(" AS ").append(qAlias(f.getTableAlias() + "_" + f.getColumnName()));
            }
            if (i < selected.size() - 1 || !computedFields.isEmpty()) sql.append(", ");
            // 参与 group by 的列使用别名，便于阅读（也兼容大多数 MySQL 8 场景）
            groupByColumns.add(f.getAlias() != null && !f.getAlias().trim().isEmpty()
                    ? f.getAlias().trim()
                    : f.getTableAlias() + "_" + f.getColumnName());
        }

        // 2) 计算字段（聚合 或 多字段运算表达式）
        List<String> aggFuncsWhitelist = Arrays.asList(
                "SUM", "AVG", "COUNT", "MAX", "MIN",
                "GROUP_CONCAT", "STDDEV", "STDDEV_SAMP", "VARIANCE", "VAR_SAMP"
        );
        List<String> allowedExprOps = Arrays.asList("+", "-", "*", "/");
        boolean hasAggregates = false;
        List<String> aggParts = new ArrayList<>();
        List<String> exprParts = new ArrayList<>();
        for (AssociationConfigDto.ComputedFieldDto cf : computedFields) {
            if (cf == null || !Boolean.TRUE.equals(cf.getEnabled() == null ? Boolean.TRUE : cf.getEnabled())) {
                continue;
            }
            String aliasName = cf.getAlias() == null ? "" : cf.getAlias().trim();
            if (aliasName.isEmpty()) {
                throw new IllegalArgumentException("计算字段必须指定别名");
            }
            String type = cf.getType() != null ? cf.getType().trim().toLowerCase(Locale.ROOT) : "aggregation";

            if ("expression".equals(type)) {
                // 多字段运算：字段A + 字段B、字段A * 字段B 等
                List<AssociationConfigDto.ExpressionOperandDto> operands = cf.getExpressionOperands() == null ? Collections.emptyList() : cf.getExpressionOperands();
                List<String> operators = cf.getExpressionOperators() == null ? Collections.emptyList() : cf.getExpressionOperators();
                if (operands.size() < 2) {
                    throw new IllegalArgumentException("字段运算至少需要 2 个参与运算的字段");
                }
                if (operators.size() != operands.size() - 1) {
                    throw new IllegalArgumentException("运算符数量应为参与运算字段数减 1");
                }
                StringBuilder expr = new StringBuilder();
                for (int i = 0; i < operands.size(); i++) {
                    AssociationConfigDto.ExpressionOperandDto op = operands.get(i);
                    if (op == null || op.getTableAlias() == null || op.getColumnName() == null) {
                        continue;
                    }
                    if (!aliasToTable.containsKey(op.getTableAlias()) || !isSafeName(op.getColumnName())) {
                        throw new IllegalArgumentException("字段运算中存在非法表别名或字段名");
                    }
                    if (expr.length() > 0) {
                        String opStr = i - 1 < operators.size() ? operators.get(i - 1) : "+";
                        if (!allowedExprOps.contains(opStr)) {
                            opStr = "+";
                        }
                        expr.append(" ").append(opStr).append(" ");
                    }
                    expr.append(q(op.getTableAlias())).append(".").append(q(op.getColumnName()));
                }
                if (expr.length() > 0) {
                    exprParts.add("(" + expr + ") AS " + qAlias(aliasName));
                }
                continue;
            }

            // 聚合：SUM/AVG/COUNT 等单字段
            String func = cf.getFunc() == null ? "" : cf.getFunc().trim().toUpperCase(Locale.ROOT);
            if (!aggFuncsWhitelist.contains(func)) {
                continue;
            }
            if (!isSafeAlias(cf.getTableAlias()) || !aliasToTable.containsKey(cf.getTableAlias())) {
                throw new IllegalArgumentException("计算字段所属表别名不存在: " + cf.getTableAlias());
            }
            if (!isSafeName(cf.getColumnName())) {
                throw new IllegalArgumentException("计算字段字段名非法: " + cf.getColumnName());
            }
            String expr;
            if ("COUNT".equals(func) && "*".equals(cf.getColumnName())) {
                expr = "COUNT(*)";
            } else {
                expr = func + "(" + q(cf.getTableAlias()) + "." + q(cf.getColumnName()) + ")";
            }
            aggParts.add(expr + " AS " + qAlias(aliasName));
            hasAggregates = true;
        }
        if (!aggParts.isEmpty() || !exprParts.isEmpty()) {
            List<String> allParts = new ArrayList<>(aggParts);
            allParts.addAll(exprParts);
            for (int i = 0; i < allParts.size(); i++) {
                sql.append(allParts.get(i));
                if (i < allParts.size() - 1) {
                    sql.append(", ");
                }
            }
        }

        AssociationConfigDto.TableDto main = config.getTables().get(0);

        List<AssociationConfigDto.JoinDto> joins = config.getJoins() == null ? Collections.emptyList() : config.getJoins();

        // 将同一组 JOIN（同 joinType + 左右别名）的多个条件合并到一个 ON 子句，避免重复别名 JOIN（须在 FROM 之前构建，供预览 EXISTS 抽样使用）
        Map<String, JoinBuildGroup> joinGroups = new LinkedHashMap<>();
        for (AssociationConfigDto.JoinDto j : joins) {
            String joinType = normalizeJoinType(j.getJoinType());
            if (!isSafeAlias(j.getLeftTableAlias()) || !isSafeAlias(j.getRightTableAlias())) {
                throw new IllegalArgumentException("JOIN 表别名非法");
            }
            if (!aliasToTable.containsKey(j.getLeftTableAlias()) || !aliasToTable.containsKey(j.getRightTableAlias())) {
                throw new IllegalArgumentException("JOIN 表别名不存在");
            }
            if (!isSafeName(j.getLeftField()) || !isSafeName(j.getRightField())) {
                throw new IllegalArgumentException("JOIN 字段名非法");
            }
            String leftAlias = j.getLeftTableAlias();
            String rightAlias = j.getRightTableAlias();
            String rightTableName = aliasToTable.get(rightAlias);
            String groupKey = joinType + "|" + leftAlias + "|" + rightAlias;
            JoinBuildGroup group = joinGroups.computeIfAbsent(groupKey,
                    k -> new JoinBuildGroup(joinType, rightAlias, rightTableName));

            if (!rightTableName.equals(group.rightTableName)) {
                throw new IllegalArgumentException("JOIN 右表别名映射冲突: " + rightAlias);
            }
            group.conditions.add(
                    q(leftAlias) + "." + q(j.getLeftField()) + " = " + q(rightAlias) + "." + q(j.getRightField())
            );
            group.eqs.add(new JoinEq(leftAlias, j.getLeftField(), rightAlias, j.getRightField()));
        }

        boolean previewDualKeyLimitedJoin = previewDualKeyLimitedJoinEnabled
                && previewMainRowCap != null && previewMainRowCap > 0
                && previewJoinSideRowCap > 0
                && joinGroups.size() == 1
                && aliasToTable.size() == 2;
        if (previewDualKeyLimitedJoin) {
            JoinBuildGroup g = joinGroups.values().iterator().next();
            if (!"INNER JOIN".equals(g.joinType) || g.eqs.isEmpty()
                    || !g.eqs.stream().allMatch(e -> main.getAlias().equals(e.leftAlias))) {
                previewDualKeyLimitedJoin = false;
            }
        }

        // FROM 主表：取 tables[0]
        sql.append(" FROM ");
        if (previewDualKeyLimitedJoin) {
            JoinBuildGroup g = joinGroups.values().iterator().next();
            appendPreviewDualKeyLimitedFromJoin(sql, schema, main, g, config);
        } else if (previewMainRowCap != null && previewMainRowCap > 0) {
            LinkedHashSet<String> mainCols = collectColumnsNeededFromAlias(config, main.getAlias());
            if (mainCols.isEmpty()) {
                throw new IllegalArgumentException("预览抽样需要能从首表抽取列（选中字段/筛选/关联键等），当前为空");
            }
            sql.append("( SELECT ");
            int ci = 0;
            for (String col : mainCols) {
                if (!isSafeName(col)) {
                    throw new IllegalArgumentException("非法字段名: " + col);
                }
                if (ci++ > 0) {
                    sql.append(", ");
                }
                sql.append(q(col));
            }
            sql.append(" FROM ").append(qTable(schema, main.getTableName())).append(" ").append(q(main.getAlias()));
            appendPreviewMainMatchExistsClauses(sql, schema, main, joinGroups);
            sql.append(" LIMIT ").append(previewMainRowCap)
                    .append(" ) ").append(q(main.getAlias()));
        } else {
            sql.append(qTable(schema, main.getTableName())).append(" ").append(q(main.getAlias()));
        }

        // 记录已经出现在 FROM/JOIN 中的表别名（避免重复）
        Set<String> usedAliases = new LinkedHashSet<>();
        usedAliases.add(main.getAlias());
        if (previewDualKeyLimitedJoin) {
            usedAliases.add(joinGroups.values().iterator().next().rightAlias);
        }

        for (JoinBuildGroup group : joinGroups.values()) {
            if (previewDualKeyLimitedJoin) {
                continue;
            }
            sql.append(" ").append(group.joinType).append(" ");
            /*
             * 预览优化：仅 INNER JOIN 且 ON 条件全部来自「首表 = 右表」等值时，将右表限制为
             * 关联键落在首表抽样 DISTINCT 键集合上的行，再 LIMIT，避免一对多爆炸。
             * LEFT/RIGHT JOIN 不改变语义故不做此裁剪。
             */
            boolean previewBoundJoin = previewMainRowCap != null && previewMainRowCap > 0
                    && previewJoinSideRowCap > 0
                    && "INNER JOIN".equals(group.joinType)
                    && !group.eqs.isEmpty()
                    && group.eqs.stream().allMatch(e -> main.getAlias().equals(e.leftAlias));
            if (previewBoundJoin) {
                appendPreviewBoundedJoin(sql, schema, main, group, config, joinGroups);
            } else {
                sql.append(qTable(schema, group.rightTableName)).append(" ").append(q(group.rightAlias));
            }
            sql.append(" ON ").append(String.join(" AND ", group.conditions));
            usedAliases.add(group.rightAlias);
        }

        // 对于未在 FROM/JOIN 中出现，但在字段中被引用的表别名，追加到 FROM 列表中（隐式 CROSS JOIN），避免出现 u.xxx 无表的情况
        if (aliasToTable.size() > usedAliases.size()) {
            for (Map.Entry<String, String> entry : aliasToTable.entrySet()) {
                String alias = entry.getKey();
                if (usedAliases.contains(alias)) {
                    continue;
                }
                String tableName = entry.getValue();
                sql.append(", ").append(qTable(schema, tableName)).append(" ").append(q(alias));
            }
        }

        // 3) 筛选条件（WHERE）：支持时间、数值、字符串等，时间仅输入年份时自动补全为日期（须在 GROUP BY 之前）
        List<AssociationConfigDto.FilterDto> filters = config.getFilters() == null ? Collections.emptyList() : config.getFilters();
        List<String> whereParts = new ArrayList<>();
        for (AssociationConfigDto.FilterDto filter : filters) {
            if (filter == null || filter.getTableAlias() == null || filter.getColumnName() == null || filter.getOperator() == null) {
                continue;
            }
            String val = filter.getValue();
            if (val == null) {
                val = "";
            }
            if (!aliasToTable.containsKey(filter.getTableAlias())) {
                continue;
            }
            if (!isSafeName(filter.getColumnName())) {
                continue;
            }
            String qualified = q(filter.getTableAlias()) + "." + q(filter.getColumnName());
            String op = filter.getOperator().trim().toLowerCase(Locale.ROOT);
            String sqlValue = normalizeFilterValue(val, filter.getColumnName(), op);
            switch (op) {
                case "eq":
                    whereParts.add(qualified + " = " + sqlValue);
                    break;
                case "neq":
                    whereParts.add(qualified + " <> " + sqlValue);
                    break;
                case "gt":
                    whereParts.add(qualified + " > " + sqlValue);
                    break;
                case "gte":
                    whereParts.add(qualified + " >= " + sqlValue);
                    break;
                case "lt":
                    whereParts.add(qualified + " < " + sqlValue);
                    break;
                case "lte":
                    whereParts.add(qualified + " <= " + sqlValue);
                    break;
                case "contains":
                    whereParts.add(qualified + " LIKE " + likeValue(sqlValue, true));
                    break;
                case "not_contains":
                    whereParts.add(qualified + " NOT LIKE " + likeValue(sqlValue, true));
                    break;
                default:
                    break;
            }
        }
        if (!whereParts.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", whereParts));
        }

        // 4) 如果存在聚合字段，则自动拼接 GROUP BY
        if (hasAggregates && !groupByColumns.isEmpty()) {
            sql.append(" GROUP BY ");
            for (int i = 0; i < groupByColumns.size(); i++) {
                String col = groupByColumns.get(i);
                if (!isSafeAlias(col) && !isSafeName(col)) {
                    throw new IllegalArgumentException("GROUP BY 字段名非法: " + col);
                }
                sql.append(q(col));
                if (i < groupByColumns.size() - 1) {
                    sql.append(", ");
                }
            }
        }

        return sql.toString();
    }

    /**
     * 收集某表别名在 SELECT / WHERE / JOIN / 计算字段中引用到的物理列名（用于预览时对首表做 LIMIT 子查询）。
     */
    private static LinkedHashSet<String> collectColumnsNeededFromAlias(AssociationConfigDto config, String targetAlias) {
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        if (config.getFields() != null) {
            for (AssociationConfigDto.FieldDto f : config.getFields()) {
                if (f != null && Boolean.TRUE.equals(f.getSelected()) && targetAlias.equals(f.getTableAlias())
                        && isSafeName(f.getColumnName())) {
                    cols.add(f.getColumnName());
                }
            }
        }
        if (config.getFilters() != null) {
            for (AssociationConfigDto.FilterDto filter : config.getFilters()) {
                if (filter != null && targetAlias.equals(filter.getTableAlias()) && isSafeName(filter.getColumnName())) {
                    cols.add(filter.getColumnName());
                }
            }
        }
        if (config.getJoins() != null) {
            for (AssociationConfigDto.JoinDto j : config.getJoins()) {
                if (j == null) {
                    continue;
                }
                if (targetAlias.equals(j.getLeftTableAlias()) && isSafeName(j.getLeftField())) {
                    cols.add(j.getLeftField());
                }
                if (targetAlias.equals(j.getRightTableAlias()) && isSafeName(j.getRightField())) {
                    cols.add(j.getRightField());
                }
            }
        }
        if (config.getComputedFields() != null) {
            for (AssociationConfigDto.ComputedFieldDto cf : config.getComputedFields()) {
                if (cf == null || !Boolean.TRUE.equals(cf.getEnabled() == null ? Boolean.TRUE : cf.getEnabled())) {
                    continue;
                }
                String type = cf.getType() != null ? cf.getType().trim().toLowerCase(Locale.ROOT) : "aggregation";
                if ("expression".equals(type)) {
                    List<AssociationConfigDto.ExpressionOperandDto> ops = cf.getExpressionOperands();
                    if (ops != null) {
                        for (AssociationConfigDto.ExpressionOperandDto op : ops) {
                            if (op != null && targetAlias.equals(op.getTableAlias()) && isSafeName(op.getColumnName())) {
                                cols.add(op.getColumnName());
                            }
                        }
                    }
                } else if (targetAlias.equals(cf.getTableAlias()) && cf.getColumnName() != null
                        && !"*".equals(cf.getColumnName()) && isSafeName(cf.getColumnName())) {
                    cols.add(cf.getColumnName());
                }
            }
        }
        return cols;
    }

    /**
     * 规范化筛选值：对时间类字段且仅输入年份（如 2024）时，转为完整日期便于正确比较
     */
    private static String normalizeFilterValue(String value, String columnName, String operator) {
        if (value == null) {
            return "NULL";
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return "''";
        }
        boolean looksLikeDateTimeColumn = columnName != null && (
                columnName.toLowerCase(Locale.ROOT).endsWith("_at")
                || columnName.toLowerCase(Locale.ROOT).endsWith("_time")
                || columnName.toLowerCase(Locale.ROOT).contains("date")
                || columnName.toLowerCase(Locale.ROOT).contains("time")
        );
        // 仅年份（4位数字）且为时间相关列：转为日期边界，便于正确比较
        if (looksLikeDateTimeColumn && v.matches("^\\d{4}$")) {
            int year = Integer.parseInt(v);
            switch (operator) {
                case "gte":
                    v = year + "-01-01 00:00:00";
                    break;
                case "gt":
                    v = year + "-12-31 23:59:59";
                    break;
                case "lt":
                    v = year + "-01-01 00:00:00";
                    break;
                case "lte":
                    v = year + "-12-31 23:59:59";
                    break;
                default:
                    v = year + "-01-01 00:00:00";
                    break;
            }
        }
        // 若已是 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss，保持并加引号
        String escaped = v.replace("\\", "\\\\").replace("'", "''");
        return "'" + escaped + "'";
    }

    private static String likeValue(String quotedValue, boolean wrapPercent) {
        if (quotedValue == null || quotedValue.length() < 2 || !quotedValue.startsWith("'") || !quotedValue.endsWith("'")) {
            return "'%" + (quotedValue != null ? quotedValue.replace("'", "''") : "") + "%'";
        }
        String inner = quotedValue.substring(1, quotedValue.length() - 1).replace("%", "\\%").replace("_", "\\_");
        return "'%" + inner + "%'";
    }

    @Override
    public Map<String, Object> validateSql(String sql) {
        Map<String, Object> res = new LinkedHashMap<>();
        if (sql == null || sql.trim().isEmpty()) {
            res.put("valid", false);
            res.put("message", "SQL不能为空");
            return res;
        }
        String cleaned = stripTrailingSemicolon(sql);
        if (cleaned.contains(";")) {
            res.put("valid", false);
            res.put("message", "不允许多语句SQL");
            return res;
        }
        try {
            Statement stmt = CCJSqlParserUtil.parse(cleaned);
            if (!(stmt instanceof Select)) {
                res.put("valid", false);
                res.put("message", "仅允许SELECT语句");
                return res;
            }
            res.put("valid", true);
            res.put("message", "ok");
            return res;
        } catch (Exception e) {
            res.put("valid", false);
            res.put("message", e.getMessage());
            return res;
        }
    }

    @Override
    public Map<String, Object> preview(AssociationConfigDto config, Integer limit) {
        int lim = (limit == null || limit <= 0 || limit > 200) ? 50 : limit;
        final String canonicalSql;
        try {
            canonicalSql = buildAssociationSql(config, true, null);
        } catch (IllegalArgumentException e) {
            return Map.of("code", 400, "msg", e.getMessage(), "data", null);
        }

        final String execSql;
        try {
            execSql = previewMainRowCap > 0
                    ? buildAssociationSql(config, true, previewMainRowCap)
                    : canonicalSql;
        } catch (IllegalArgumentException e) {
            return Map.of("code", 400, "msg", e.getMessage(), "data", null);
        }

        Map<String, Object> valid = validateSql(execSql);
        if (!Boolean.TRUE.equals(valid.get("valid"))) {
            return Map.of("code", 400, "msg", "SQL校验失败：" + valid.get("message"), "data", null);
        }

        /*
         * 将 LIMIT 下推到内层：MySQL 在执行 SELECT * FROM (大JOIN) t LIMIT n 时仍会完整物化 JOIN 结果，
         * 大表关联易导致超时/内存压力。预览已对首表抽样（preview-main-row-cap）时再 LIMIT 输出行数。
         */
        String innerLimited = stripTrailingSemicolon(execSql) + " LIMIT " + lim;
        String wrapped = "SELECT * FROM (" + innerLimited + ") t";
        // 使用目标数据源执行预览查询：支持外部数据库
        org.springframework.jdbc.core.JdbcTemplate targetJdbc = dataSourceMetaService.resolveJdbcTemplate(config.getDataSourceId());
        if (targetJdbc == null) {
            // 兜底：无法解析数据源时，退回主库连接（兼容旧数据）
            targetJdbc = jdbcTemplate;
        }
        List<Map<String, Object>> rows;
        try {
            if (previewQueryTimeoutSeconds > 0) {
                rows = targetJdbc.query(wrapped, ps -> ps.setQueryTimeout(previewQueryTimeoutSeconds), rs -> {
                    List<Map<String, Object>> list = new ArrayList<>();
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    int colCount = md.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            String label = md.getColumnLabel(i);
                            if (label == null || label.isEmpty()) {
                                label = md.getColumnName(i);
                            }
                            row.put(label, rs.getObject(i));
                        }
                        list.add(row);
                    }
                    return list;
                });
            } else {
                rows = targetJdbc.queryForList(wrapped);
            }
        } catch (DataAccessException e) {
            log.warn("多表关联预览查询失败：{}", e.getMessage());
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            String hint = "预览查询失败（多为 JOIN 结果过大或执行超时）。可尝试：增加 WHERE/日期筛选、减少字段、调整关联键；或调高 preview-main-row-cap / preview-join-side-row-cap / preview-query-timeout-seconds（见 application 中 jmreport.table-association）。";
            Map<String, Object> errData = new LinkedHashMap<>();
            errData.put("sql", canonicalSql);
            if (!canonicalSql.equals(execSql)) {
                errData.put("previewSql", execSql);
            }
            return Map.of("code", 500, "msg", hint + " 详情：" + root.getMessage(), "data", errData);
        }
        List<String> columns = new ArrayList<>();
        if (!rows.isEmpty()) {
            columns.addAll(rows.get(0).keySet());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sql", canonicalSql);
        if (!canonicalSql.equals(execSql)) {
            data.put("previewSql", execSql);
            data.put("previewMainRowCap", previewMainRowCap);
        }
        data.put("columns", columns);
        data.put("rows", rows);
        data.put("limit", lim);
        return Map.of("code", 200, "msg", "success", "data", data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveAsDataset(AssociationConfigDto config, String datasetName, String datasetParentId, String tableName) {
        if (config == null) {
            return Map.of("code", 400, "msg", "config不能为空", "data", null);
        }
        // 提前生成数据集ID，后续用于默认表名等
        String datasetId = uuid32();
        if (datasetName == null || datasetName.trim().isEmpty()) {
            datasetName = config.getName();
        }
        if (datasetName == null || datasetName.trim().isEmpty()) {
            return Map.of("code", 400, "msg", "datasetName不能为空", "data", null);
        }

        String physicalTableName = tableName;
        if (physicalTableName == null || physicalTableName.trim().isEmpty()) {
            physicalTableName = buildPhysicalTableName(datasetId);
        }
        // 基本校验：只能包含字母数字下划线，且以字母开头
        if (!physicalTableName.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            return Map.of("code", 400, "msg", "目标表名不合法，仅支持字母开头的字母/数字/下划线组合", "data", null);
        }
        physicalTableName = physicalTableName.toLowerCase(Locale.ROOT);
        // 统一为多表关联物化表增加固定前缀，避免与业务表重名
        if (!physicalTableName.startsWith(TABLE_PREFIX)) {
            physicalTableName = TABLE_PREFIX + physicalTableName;
        }

        // 生成保存用SQL：带上 schema 前缀，确保在主库连接或外部数据源连接下都能正确访问目标库中的表
        String sql = generateSql(config, true);
        Map<String, Object> valid = validateSql(sql);
        if (!Boolean.TRUE.equals(valid.get("valid"))) {
            return Map.of("code", 400, "msg", "SQL校验失败：" + valid.get("message"), "data", null);
        }

        Date now = new Date();

        // 1) 写入自定义关联配置表（用于二次编辑）——表在下一步 db-schema 中创建
        String configJson;
        try {
            configJson = objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            configJson = "{}";
        }
        com.jeecg.modules.jmreport.entity.JmTableAssociation entity = new com.jeecg.modules.jmreport.entity.JmTableAssociation();
        entity.setId(datasetId);
        entity.setName(datasetName);
        entity.setDescription(safeStr(config.getDescription()));
        entity.setDataSourceId(config.getDataSourceId());
        entity.setSqlTemplate(sql);
        entity.setConfigJson(configJson);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        try {
            // 尝试为表增加 physical_table 字段（首次运行时）
            jdbcTemplate.execute("ALTER TABLE jm_table_association ADD COLUMN physical_table varchar(128) NULL COMMENT '物化表名'");
        } catch (Exception ignored) {
        }
        entity.setPhysicalTable(physicalTableName);
        jmTableAssociationMapper.insert(entity);

        // 2) 写入大屏数据集 head（保留原有能力）
        jdbcTemplate.update(
                "INSERT INTO onl_drag_dataset_head (id, name, code, parent_id, db_source, query_sql, content, iz_agent, data_type, api_method, create_time, create_by, update_time, update_by, low_app_id, tenant_id) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                datasetId,
                datasetName,
                "",
                datasetParentId,
                safeStr(config.getDataSourceId()),
                sql,
                safeStr(config.getDescription()),
                "0",
                "sql",
                "get",
                now,
                "admin",
                now,
                "admin",
                null,
                null
        );

        // 3) 写入字段 item（仅保存选中字段），并推断一个主键列（优先 *_id / id），同时收集列名供建表使用
        String primaryKeyColumn = null;
        List<String> columnNames = new ArrayList<>();
        List<AssociationConfigDto.FieldDto> fields = config.getFields() == null ? Collections.emptyList() : config.getFields();
        List<AssociationConfigDto.FieldDto> selected = new ArrayList<>();
        for (AssociationConfigDto.FieldDto f : fields) {
            if (Boolean.TRUE.equals(f.getSelected())) selected.add(f);
        }
        selected.sort(Comparator.comparingInt(o -> o.getOrderNum() == null ? 0 : o.getOrderNum()));
        for (int i = 0; i < selected.size(); i++) {
            AssociationConfigDto.FieldDto f = selected.get(i);
            String fieldName = f.getAlias();
            if (fieldName == null || fieldName.trim().isEmpty()) {
                fieldName = f.getTableAlias() + "_" + f.getColumnName();
            }
            columnNames.add(fieldName);
            // 记录第一个看起来像主键的字段（id / *_id）
            if (primaryKeyColumn == null) {
                String lower = fieldName.toLowerCase(Locale.ROOT);
                if ("id".equals(lower) || lower.endsWith("_id")) {
                    primaryKeyColumn = fieldName;
                }
            }
            jdbcTemplate.update(
                    "INSERT INTO onl_drag_dataset_item (id, head_id, field_name, field_txt, field_type, widget_type, dict_code, dict_table, dict_text, iz_show, iz_search, iz_total, search_mode, order_num, create_by, create_time, update_by, update_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    uuid32(),
                    datasetId,
                    safeStr(fieldName),
                    safeStr(fieldName),
                    "String",
                    null,
                    null,
                    null,
                    null,
                    "Y",
                    null,
                    null,
                    null,
                    i,
                    "admin",
                    now,
                    null,
                    null
            );
        }

        // 4) 在目标数据源中生成物化表（物理表）
        try {
            materializeTableForAssociation(datasetId, sql, config.getDataSourceId(), physicalTableName, primaryKeyColumn, columnNames);
        } catch (Exception e) {
            log.warn("多表关联数据集物化表创建失败，datasetId={}，错误：{}", datasetId, e.getMessage());
            return Map.of(
                    "code", 500,
                    "msg", "在目标数据源中创建表失败：" + e.getMessage(),
                    "data", java.util.Collections.emptyMap()
            );
        }

        return Map.of("code", 200, "msg", "success",
                "data", Map.of("datasetId", datasetId, "sql", sql, "tableName", physicalTableName));
    }

    @Override
    public Map<String, Object> queryDataset(String datasetId, Integer limit) {
        if (datasetId == null || datasetId.trim().isEmpty()) {
            return Map.of("code", 400, "msg", "datasetId不能为空", "data", null);
        }
        com.jeecg.modules.jmreport.entity.JmTableAssociation assoc = jmTableAssociationMapper.selectById(datasetId);
        if (assoc == null) {
            return Map.of("code", 404, "msg", "未找到对应的数据集", "data", null);
        }
        String sql = assoc.getSqlTemplate();
        Map<String, Object> valid = validateSql(sql);
        if (!Boolean.TRUE.equals(valid.get("valid"))) {
            return Map.of("code", 400, "msg", "SQL校验失败：" + valid.get("message"), "data", null);
        }
        int lim = (limit == null || limit <= 0 || limit > DEFAULT_LIMIT) ? DEFAULT_LIMIT : limit;
        String wrapped = "SELECT * FROM (" + stripTrailingSemicolon(sql) + ") t LIMIT " + lim;
        // 使用数据集对应的数据源执行查询，支持外部数据库
        org.springframework.jdbc.core.JdbcTemplate targetJdbc = dataSourceMetaService.resolveJdbcTemplate(assoc.getDataSourceId());
        if (targetJdbc == null) {
            targetJdbc = jdbcTemplate;
        }
        List<Map<String, Object>> rows;
        try {
            rows = targetJdbc.queryForList(wrapped);
        } catch (Exception e) {
            return Map.of("code", 500, "msg", "执行SQL失败：" + e.getMessage(), "data", null);
        }
        List<String> columns = new ArrayList<>();
        if (!rows.isEmpty()) {
            columns.addAll(rows.get(0).keySet());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", assoc.getId());
        data.put("name", assoc.getName());
        data.put("description", safeStr(assoc.getDescription()));
        data.put("dataSourceId", safeStr(assoc.getDataSourceId()));
        data.put("sql", sql);
        data.put("columns", columns);
        data.put("rows", rows);
        data.put("limit", lim);
        return Map.of("code", 200, "msg", "success", "data", data);
    }

    @Override
    public List<Map<String, Object>> listDatasets() {
        // 直接读取 jm_table_association，作为多表关联数据集清单
        // 这里使用简单 SQL，避免为列表再扩展 Mapper 方法
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT id, name, description, data_source_id, physical_table, create_time, update_time FROM jm_table_association ORDER BY update_time DESC"
            );
        } catch (DataAccessException e) {
            if (isJmTableAssociationMissing(e)) {
                log.warn("jm_table_association 表不存在，返回空列表。使用多表关联请在主库执行 db/table_association_init.sql");
                return Collections.emptyList();
            }
            throw e;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(r.get("id")));
            m.put("name", String.valueOf(r.get("name")));
            m.put("description", r.get("description") == null ? "" : String.valueOf(r.get("description")));
            m.put("dataSourceId", r.get("data_source_id") == null ? "" : String.valueOf(r.get("data_source_id")));
            m.put("physicalTable", r.get("physical_table") == null ? "" : String.valueOf(r.get("physical_table")));
            m.put("createTime", r.get("create_time"));
            m.put("updateTime", r.get("update_time"));
            out.add(m);
        }
        return out;
    }

    @Override
    public void refreshAllMaterializedTables() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, data_source_id, sql_template, physical_table FROM jm_table_association"
            );
            for (Map<String, Object> r : rows) {
                String id = String.valueOf(r.get("id"));
                String dataSourceId = r.get("data_source_id") == null ? "" : String.valueOf(r.get("data_source_id"));
                String sql = r.get("sql_template") == null ? "" : String.valueOf(r.get("sql_template"));
                String tableName = r.get("physical_table") == null ? null : String.valueOf(r.get("physical_table"));
                if (sql == null || sql.trim().isEmpty()) {
                    continue;
                }
                try {
                    materializeTableForAssociation(id, sql, dataSourceId, tableName, null, null);
                    log.info("多表关联数据集物化表已刷新，id={}，table={}", id, tableName);
                } catch (Exception e) {
                    log.warn("多表关联数据集物化表刷新失败，id={}，错误：{}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            if (isJmTableAssociationMissing(e)) {
                log.warn("jm_table_association 表不存在，跳过物化表刷新。需要时请执行 db/table_association_init.sql");
                return;
            }
            // 兼容老库没有 physical_table 字段的情况，退回到自动表名
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT id, data_source_id, sql_template FROM jm_table_association"
                );
                for (Map<String, Object> r : rows) {
                    String id = String.valueOf(r.get("id"));
                    String dataSourceId = r.get("data_source_id") == null ? "" : String.valueOf(r.get("data_source_id"));
                    String sql = r.get("sql_template") == null ? "" : String.valueOf(r.get("sql_template"));
                    if (sql == null || sql.trim().isEmpty()) {
                        continue;
                    }
                    try {
                        materializeTableForAssociation(id, sql, dataSourceId, null, null, null);
                        log.info("多表关联数据集物化表已刷新(兼容模式)，id={}", id);
                    } catch (Exception ex) {
                        log.warn("多表关联数据集物化表刷新失败(兼容模式)，id={}，错误：{}", id, ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                if (isJmTableAssociationMissing(ex)) {
                    log.warn("jm_table_association 表不存在，跳过物化表刷新");
                    return;
                }
                throw new RuntimeException(ex);
            }
        }
    }

    @Override
    public List<Map<String, Object>> listDatasetsByDataSource(String dataSourceId) {
        if (dataSourceId == null || dataSourceId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT id, name, description, data_source_id, sql_template, config_json, physical_table, create_time, update_time " +
                            "FROM jm_table_association WHERE data_source_id = ? ORDER BY update_time DESC",
                    dataSourceId
            );
        } catch (DataAccessException e) {
            if (isJmTableAssociationMissing(e)) {
                log.warn("jm_table_association 表不存在，by-data-source 返回空列表。请在主库 jimureport 执行 db/table_association_init.sql");
                return Collections.emptyList();
            }
            throw e;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(r.get("id")));
            m.put("name", String.valueOf(r.get("name")));
            m.put("description", r.get("description") == null ? "" : String.valueOf(r.get("description")));
            m.put("dataSourceId", r.get("data_source_id") == null ? "" : String.valueOf(r.get("data_source_id")));
            m.put("sql", r.get("sql_template") == null ? "" : String.valueOf(r.get("sql_template")));
            m.put("configJson", r.get("config_json") == null ? "" : String.valueOf(r.get("config_json")));
            m.put("physicalTable", r.get("physical_table") == null ? "" : String.valueOf(r.get("physical_table")));
            m.put("createTime", r.get("create_time"));
            m.put("updateTime", r.get("update_time"));
            out.add(m);
        }
        return out;
    }

    private String getSchemaByDataSourceId(String dataSourceId) {
        if (dataSourceId == null || dataSourceId.trim().isEmpty()) {
            return "";
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT db_url FROM jimu_report_data_source WHERE id = ? LIMIT 1",
                dataSourceId
        );
        if (rows.isEmpty()) {
            return "";
        }
        return DataSourceMetaServiceImpl.parseSchemaFromJdbcUrl(String.valueOf(rows.get(0).get("db_url")));
    }

    /**
     * 根据关联配置，在目标数据源中创建/重建物化表。
     *
     * @param primaryKeyColumn 可选的主键列名（已是物化表中的列名），为空则不建主键
     * @param columnNames      可选的列名列表；如果为空则从数据库元数据解析
     */
    private void materializeTableForAssociation(String associationId, String sql, String dataSourceId, String physicalTableName, String primaryKeyColumn, List<String> columnNames) {
        JdbcTemplate targetJdbc = dataSourceMetaService.resolveJdbcTemplate(dataSourceId);
        if (targetJdbc == null) {
            throw new IllegalStateException("无法获取数据源连接，dataSourceId=" + dataSourceId);
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("物化表SQL不能为空");
        }
        String tableName = physicalTableName;
        if (tableName == null || tableName.trim().isEmpty()) {
            tableName = buildPhysicalTableName(associationId);
        }
        String schema = getSchemaByDataSourceId(dataSourceId);
        String fqTable = (schema == null || schema.isEmpty()) ? q(tableName) : q(schema) + "." + q(tableName);
        String baseSql = stripTrailingSemicolon(sql);

        // 1) 获取列名：优先使用调用方传入的列名列表；为空时再从数据库元数据解析
        List<String> columns = new ArrayList<>();
        if (columnNames != null && !columnNames.isEmpty()) {
            columns.addAll(columnNames);
        } else {
            try {
                String metaSql = "SELECT * FROM (" + baseSql + ") t LIMIT 0";
                targetJdbc.query(metaSql, rs -> {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    for (int i = 1; i <= n; i++) {
                        String label = md.getColumnLabel(i);
                        if (label == null || label.isEmpty()) {
                            label = md.getColumnName(i);
                        }
                        columns.add(label);
                    }
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException("解析查询列信息失败：" + e.getMessage(), e);
            }
            if (columns.isEmpty()) {
                throw new IllegalStateException("无法从SQL中解析出任何列，无法创建物化表");
            }
        }

        String pk = primaryKeyColumn;
        // 注意：如果没有明确识别到主键列（id / *_id），则不强制创建主键，
        // 避免将第一个字段误判为主键导致 Duplicate entry 报错。
        if (pk != null && pk.trim().isEmpty()) {
            pk = null;
        }

        // 2) 重建表结构（统一使用 VARCHAR(255)，主键列 NOT NULL）
        targetJdbc.execute("DROP TABLE IF EXISTS " + fqTable);
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(fqTable).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            String col = columns.get(i);
            ddl.append(q(col)).append(" varchar(255)");
            if (pk != null && col.equals(pk)) {
                ddl.append(" NOT NULL");
            }
            if (i < columns.size() - 1 || pk != null) {
                ddl.append(", ");
            }
        }
        if (pk != null) {
            ddl.append("PRIMARY KEY (").append(q(pk)).append(")");
        } else {
            // 无主键时，移除末尾多余的逗号空格（如果存在）
            if (ddl.length() >= 2) {
                String tail = ddl.substring(ddl.length() - 2);
                if (", ".equals(tail)) {
                    ddl.setLength(ddl.length() - 2);
                }
            }
        }
        ddl.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        targetJdbc.execute(ddl.toString());

        // 3) 插入数据
        String colsJoined = String.join(", ", columns.stream().map(TableAssociationServiceImpl::q).toList());
        String insertSql = "INSERT INTO " + fqTable + " (" + colsJoined + ") " + baseSql;
        targetJdbc.execute(insertSql);
    }

    private static String buildPhysicalTableName(String associationId) {
        // 前缀 + 32位ID，只包含字母数字和下划线，兼容大多数 MySQL 配置
        String id = associationId == null ? "" : associationId.replaceAll("[^a-zA-Z0-9]", "");
        if (id.isEmpty()) {
            id = uuid32();
        }
        return TABLE_PREFIX + id.toLowerCase(Locale.ROOT);
    }

    /**
     * 预览：仅两表、单 INNER、等值 ON 时，两侧按关联键 ORDER BY 后各自 LIMIT，再 JOIN。
     * 避免 correlated EXISTS + 右表 IN 子查询反复扫大表导致超时。
     */
    private void appendPreviewDualKeyLimitedFromJoin(StringBuilder sql, String schema,
            AssociationConfigDto.TableDto main, JoinBuildGroup group, AssociationConfigDto config) {
        LinkedHashSet<String> mainCols = collectColumnsNeededFromAlias(config, main.getAlias());
        LinkedHashSet<String> rightCols = collectColumnsNeededFromAlias(config, group.rightAlias);
        if (mainCols.isEmpty() || rightCols.isEmpty()) {
            throw new IllegalArgumentException("预览双键 LIMIT JOIN 需要两侧均能抽取列");
        }
        sql.append("( SELECT ");
        int ci = 0;
        for (String col : mainCols) {
            if (!isSafeName(col)) {
                throw new IllegalArgumentException("非法字段名: " + col);
            }
            if (ci++ > 0) {
                sql.append(", ");
            }
            sql.append(q(col));
        }
        sql.append(" FROM ").append(qTable(schema, main.getTableName())).append(" ").append(q(main.getAlias()));
        sql.append(" ORDER BY ");
        for (int i = 0; i < group.eqs.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(q(main.getAlias())).append(".").append(q(group.eqs.get(i).leftField));
        }
        sql.append(" LIMIT ").append(previewMainRowCap).append(" ) ").append(q(main.getAlias()));

        sql.append(" INNER JOIN ");

        sql.append("( SELECT ");
        int ri = 0;
        for (String col : rightCols) {
            if (!isSafeName(col)) {
                throw new IllegalArgumentException("非法字段名: " + col);
            }
            if (ri++ > 0) {
                sql.append(", ");
            }
            sql.append(q(group.rightAlias)).append(".").append(q(col));
        }
        sql.append(" FROM ").append(qTable(schema, group.rightTableName)).append(" ").append(q(group.rightAlias));
        sql.append(" ORDER BY ");
        for (int i = 0; i < group.eqs.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(q(group.rightAlias)).append(".").append(q(group.eqs.get(i).rightField));
        }
        sql.append(" LIMIT ").append(previewJoinSideRowCap).append(" ) ").append(q(group.rightAlias));

        sql.append(" ON ").append(String.join(" AND ", group.conditions));
    }

    /**
     * 预览抽样：仅保留在 INNER JOIN 右表中至少存在等值匹配的首表行，避免「任意 LIMIT」与右表零交集导致预览空结果。
     */
    private static void appendPreviewMainMatchExistsClauses(StringBuilder sql, String schema,
            AssociationConfigDto.TableDto main, Map<String, JoinBuildGroup> joinGroups) {
        List<String> existsParts = new ArrayList<>();
        int pv = 0;
        for (JoinBuildGroup g : joinGroups.values()) {
            if (!"INNER JOIN".equals(g.joinType) || g.eqs.isEmpty()) {
                continue;
            }
            if (!g.eqs.stream().allMatch(e -> main.getAlias().equals(e.leftAlias))) {
                continue;
            }
            String pvAlias = "_pv" + (pv++);
            StringBuilder ex = new StringBuilder();
            ex.append("EXISTS ( SELECT 1 FROM ")
                    .append(qTable(schema, g.rightTableName)).append(" ").append(q(pvAlias))
                    .append(" WHERE ");
            for (int i = 0; i < g.eqs.size(); i++) {
                if (i > 0) {
                    ex.append(" AND ");
                }
                JoinEq eq = g.eqs.get(i);
                ex.append(q(pvAlias)).append(".").append(q(eq.rightField))
                        .append(" = ").append(q(main.getAlias())).append(".").append(q(eq.leftField));
            }
            ex.append(" LIMIT 1 )");
            existsParts.add(ex.toString());
        }
        if (!existsParts.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", existsParts));
        }
    }

    /**
     * 预览子查询：从首表取若干列 + EXISTS + LIMIT，供右表 IN / DISTINCT 使用。
     */
    private void appendInnerLimitedMainSelect(StringBuilder sql, String schema,
            AssociationConfigDto.TableDto main, Map<String, JoinBuildGroup> joinGroups,
            List<String> projectedColsInOrder) {
        sql.append("( SELECT ");
        for (int i = 0; i < projectedColsInOrder.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            if (!isSafeName(projectedColsInOrder.get(i))) {
                throw new IllegalArgumentException("非法字段名: " + projectedColsInOrder.get(i));
            }
            sql.append(q(projectedColsInOrder.get(i)));
        }
        sql.append(" FROM ").append(qTable(schema, main.getTableName())).append(" ").append(q(main.getAlias()));
        appendPreviewMainMatchExistsClauses(sql, schema, main, joinGroups);
        sql.append(" LIMIT ").append(previewMainRowCap).append(" ) `_pkj`");
    }

    /**
     * 预览用：将 INNER JOIN 右表变为「关联键 ∈ 首表抽样键集」的子查询并 LIMIT，降低一对多 JOIN 耗时。
     */
    private void appendPreviewBoundedJoin(StringBuilder sql, String schema,
            AssociationConfigDto.TableDto main, JoinBuildGroup group, AssociationConfigDto config,
            Map<String, JoinBuildGroup> joinGroups) {
        LinkedHashSet<String> rightCols = collectColumnsNeededFromAlias(config, group.rightAlias);
        if (rightCols.isEmpty()) {
            throw new IllegalArgumentException("预览裁剪右表失败：右表未抽取到任何列");
        }
        sql.append("( SELECT ");
        int ri = 0;
        for (String col : rightCols) {
            if (!isSafeName(col)) {
                throw new IllegalArgumentException("非法字段名: " + col);
            }
            if (ri++ > 0) {
                sql.append(", ");
            }
            sql.append(q(group.rightAlias)).append(".").append(q(col));
        }
        sql.append(" FROM ").append(qTable(schema, group.rightTableName)).append(" ").append(q(group.rightAlias));
        sql.append(" WHERE ");
        if (group.eqs.size() == 1) {
            JoinEq eq = group.eqs.get(0);
            sql.append(q(group.rightAlias)).append(".").append(q(eq.rightField))
                    .append(" IN ( SELECT DISTINCT ").append(q(eq.leftField))
                    .append(" FROM ");
            appendInnerLimitedMainSelect(sql, schema, main, joinGroups, Collections.singletonList(eq.leftField));
            sql.append(" ) ");
        } else {
            sql.append("(");
            for (int i = 0; i < group.eqs.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(q(group.rightAlias)).append(".").append(q(group.eqs.get(i).rightField));
            }
            sql.append(") IN ( SELECT DISTINCT ");
            for (int i = 0; i < group.eqs.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(q(group.eqs.get(i).leftField));
            }
            sql.append(" FROM ");
            List<String> leftProj = new ArrayList<>();
            for (JoinEq eq : group.eqs) {
                leftProj.add(eq.leftField);
            }
            appendInnerLimitedMainSelect(sql, schema, main, joinGroups, leftProj);
            sql.append(" ) ");
        }
        sql.append(" LIMIT ").append(previewJoinSideRowCap)
                .append(" ) ").append(q(group.rightAlias));
    }

    private static String normalizeJoinType(String joinType) {
        String jt = joinType == null ? "" : joinType.trim().toUpperCase(Locale.ROOT);
        if (jt.isEmpty()) return "INNER JOIN";
        if (jt.equals("INNER")) return "INNER JOIN";
        if (jt.equals("LEFT")) return "LEFT JOIN";
        if (jt.equals("RIGHT")) return "RIGHT JOIN";
        if (jt.equals("FULL")) return "FULL JOIN";
        if (jt.endsWith("JOIN")) return jt;
        if (jt.contains("LEFT")) return "LEFT JOIN";
        if (jt.contains("RIGHT")) return "RIGHT JOIN";
        if (jt.contains("FULL")) return "FULL JOIN";
        return "INNER JOIN";
    }

    private static class JoinEq {
        private final String leftAlias;
        private final String leftField;
        private final String rightAlias;
        private final String rightField;

        private JoinEq(String leftAlias, String leftField, String rightAlias, String rightField) {
            this.leftAlias = leftAlias;
            this.leftField = leftField;
            this.rightAlias = rightAlias;
            this.rightField = rightField;
        }
    }

    private static class JoinBuildGroup {
        private final String joinType;
        private final String rightAlias;
        private final String rightTableName;
        private final List<String> conditions = new ArrayList<>();
        private final List<JoinEq> eqs = new ArrayList<>();

        private JoinBuildGroup(String joinType, String rightAlias, String rightTableName) {
            this.joinType = joinType;
            this.rightAlias = rightAlias;
            this.rightTableName = rightTableName;
        }
    }

    private static boolean isSafeName(String name) {
        // 允许中文、数字、下划线、连字符、$（如 GROUP$CLASSCODE061级别）；
        // 由于 SQL 统一使用反引号包裹，以上字符在 MySQL 中可安全使用。
        // 明确禁止反引号、点号、空白等高风险/歧义字符。
        return name != null && name.matches("^[\\p{L}\\p{N}_\\-$]+$");
    }

    private static boolean isSafeAlias(String alias) {
        return alias != null && alias.matches("^[a-zA-Z][a-zA-Z0-9_]*$");
    }

    private static String q(String ident) {
        return "`" + ident.replace("`", "``") + "`";
    }

    private static String qAlias(String alias) {
        // alias 允许中文/空格时，用反引号包裹；反引号内部转义
        return "`" + alias.replace("`", "``") + "`";
    }

    private static String qTable(String schema, String table) {
        if (schema == null || schema.isEmpty()) {
            return q(table);
        }
        return q(schema) + "." + q(table);
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql == null ? "" : sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private static String uuid32() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

