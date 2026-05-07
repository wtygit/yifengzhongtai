package com.jeecg.modules.jmreport.controller;

import com.jeecg.modules.jmreport.common.ApiResult;
import com.jeecg.modules.jmreport.dto.association.GenerateSqlRequest;
import com.jeecg.modules.jmreport.dto.association.PreviewRequest;
import com.jeecg.modules.jmreport.dto.association.SaveAssociationRequest;
import com.jeecg.modules.jmreport.dto.association.ValidateSqlRequest;
import com.jeecg.modules.jmreport.service.TableAssociationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/table-association")
public class TableAssociationController {

    @Autowired
    private TableAssociationService tableAssociationService;

    @PostMapping("/generate-sql")
    public Map<String, Object> generateSql(@RequestBody GenerateSqlRequest request) {
        try {
            boolean forPreview = request != null && Boolean.TRUE.equals(request.getForPreview());
            String sql = tableAssociationService.generateSql(request != null ? request.getConfig() : null, forPreview);
            return ApiResult.ok(Map.of("sql", sql));
        } catch (IllegalArgumentException e) {
            log.warn("生成 SQL 参数校验失败: {}", e.getMessage());
            return ApiResult.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("生成 SQL 失败", e);
            return ApiResult.error(500, "生成 SQL 失败：" + e.getMessage());
        }
    }

    @PostMapping("/validate-sql")
    public Map<String, Object> validateSql(@RequestBody ValidateSqlRequest request) {
        return ApiResult.ok(tableAssociationService.validateSql(request != null ? request.getSql() : null));
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody PreviewRequest request) {
        return tableAssociationService.preview(request != null ? request.getConfig() : null, request != null ? request.getLimit() : null,
                request != null ? request.getCustomSql() : null);
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody SaveAssociationRequest request) {
        if (request == null) {
            return ApiResult.error(400, "request不能为空");
        }
        return tableAssociationService.saveAsDataset(request.getConfig(), request.getDatasetName(), request.getDatasetParentId(), request.getTableName(),
                request.getCustomSql());
    }

    /**
     * 按数据源列出通过“多表关联与字段组合”模块创建的物化表，
     * 便于在数据源详情里标识哪些表是系统自动生成的数据集。
     */
    @GetMapping("/by-data-source")
    public Map<String, Object> listByDataSource(@RequestParam String dataSourceId) {
        return ApiResult.ok(tableAssociationService.listDatasetsByDataSource(dataSourceId));
    }
}

