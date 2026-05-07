package com.jeecg.modules.jmreport.dto.association;

import lombok.Data;

import java.util.List;

/**
 * 多表关联配置（前端建模提交）
 */
@Data
public class AssociationConfigDto {
    /** 保存到数据集时使用的数据源ID（jimu_report_data_source.id） */
    private String dataSourceId;
    /** 配置名称（保存时可用） */
    private String name;
    /** 描述（保存时可用） */
    private String description;

    private List<TableDto> tables;
    private List<JoinDto> joins;
    private List<FieldDto> fields;
    /** 计算字段（聚合/表达式字段） */
    private List<ComputedFieldDto> computedFields;
    /** 筛选条件（参与 SQL WHERE，如时间、状态等） */
    private List<FilterDto> filters;
    /** 显式 GROUP BY：表别名 + 物理列；有聚合时若非空则按此处分组，否则仍按「全部勾选普通字段」的输出别名分组 */
    private List<GroupByFieldDto> groupByFields;

    @Data
    public static class TableDto {
        private String tableName;  // 不含库名
        private String alias;      // 表别名，如 t1
        private Integer x;
        private Integer y;
    }

    @Data
    public static class JoinDto {
        private String joinType;       // INNER JOIN / LEFT JOIN ...
        private String leftTableAlias;
        private String leftField;
        private String rightTableAlias;
        private String rightField;
    }

    @Data
    public static class FieldDto {
        private String tableAlias;
        private String columnName;
        private String alias;          // 字段别名（可为空）
        private Integer orderNum;
        private Boolean selected;
    }

    /**
     * 计算字段：聚合（SUM/AVG 等）或 多字段运算（字段A + 字段B、字段A * 字段B 等）
     */
    @Data
    public static class ComputedFieldDto {
        /** 类型：aggregation=聚合单字段，expression=多字段运算 */
        private String type;
        /** 聚合/函数名（type=aggregation 时），如 SUM、AVG、COUNT、MAX、MIN 等 */
        private String func;
        /** 来源表别名（type=aggregation 时） */
        private String tableAlias;
        /** 来源字段名（type=aggregation 时） */
        private String columnName;
        /** 结果别名 */
        private String alias;
        /** 是否启用 */
        private Boolean enabled;
        /** 参与运算的字段列表（type=expression 时），至少 2 个 */
        private List<ExpressionOperandDto> expressionOperands;
        /** 运算符列表（type=expression 时），长度 = expressionOperands.size()-1，如 ["+", "*"] */
        private List<String> expressionOperators;
    }

    /** 表达式中的一项：表别名 + 列名 */
    @Data
    public static class ExpressionOperandDto {
        private String tableAlias;
        private String columnName;
    }

    /** 筛选条件：用于生成 SQL WHERE 子句 */
    @Data
    public static class FilterDto {
        private String tableAlias;
        private String columnName;
        /** 操作符：eq, neq, gt, gte, lt, lte, contains, not_contains */
        private String operator;
        private String value;
    }

    /** GROUP BY 一项：物理表别名 + 列名 */
    @Data
    public static class GroupByFieldDto {
        private String tableAlias;
        private String columnName;
    }
}

