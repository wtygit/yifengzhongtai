package com.jeecg.modules.jmreport.controller;

import com.jeecg.modules.jmreport.common.ApiResult;
import com.jeecg.modules.jmreport.service.TableAssociationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 多表关联数据集统一查询接口：
 * - 提供数据集列表，供各工作台选择
 * - 根据数据集ID执行查询，始终返回数据库最新数据
 */
@Slf4j
@RestController
@RequestMapping("/api/table-association/dataset")
public class TableAssociationDatasetController {

    @Autowired
    private TableAssociationService tableAssociationService;

    /**
     * 列出当前系统中所有多表关联数据集
     */
    @GetMapping("/list")
    public Map<String, Object> list() {
        return ApiResult.ok(tableAssociationService.listDatasets());
    }

    /**
     * 按数据集ID查询数据
     *
     * @param datasetId 多表关联数据集ID
     * @param limit     最大行数，可选
     */
    @GetMapping("/query")
    public Map<String, Object> query(@RequestParam String datasetId,
                                     @RequestParam(required = false) Integer limit) {
        return tableAssociationService.queryDataset(datasetId, limit);
    }
}

