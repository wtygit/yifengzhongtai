package com.jeecg.modules.jmreport.controller;

import com.jeecg.modules.jmreport.common.ApiResult;
import com.jeecg.modules.jmreport.service.DataSourceMetaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/data-source")
public class DataSourceMetaController {

    @Autowired
    private DataSourceMetaService dataSourceMetaService;

    @GetMapping("/list")
    public Map<String, Object> list() {
        return ApiResult.ok(dataSourceMetaService.listDataSources());
    }

    @GetMapping("/tables")
    public Map<String, Object> tables(@RequestParam String dataSourceId) {
        return ApiResult.ok(dataSourceMetaService.listTables(dataSourceId));
    }

    @GetMapping("/columns")
    public Map<String, Object> columns(@RequestParam String dataSourceId, @RequestParam String tableName) {
        return ApiResult.ok(dataSourceMetaService.listColumns(dataSourceId, tableName));
    }
}

