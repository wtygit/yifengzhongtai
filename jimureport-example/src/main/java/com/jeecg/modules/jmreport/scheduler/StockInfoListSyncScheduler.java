package com.jeecg.modules.jmreport.scheduler;

import com.jeecg.modules.jmreport.service.McpCoreQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 每日同步库存汇总到海典库 stock_info_list。
 * 触发时间：每天 02:00。
 */
@Slf4j
@Component
public class StockInfoListSyncScheduler {

    @Autowired(required = false)
    private McpCoreQueryService mcpCoreQueryService;

    @Scheduled(cron = "0 0 2 * * ?")
    public void dailySync() {
        if (mcpCoreQueryService == null) {
            return;
        }
        try {
            Map<String, Object> res = mcpCoreQueryService.syncStockInfoListDaily();
            Object code = res != null ? res.get("code") : null;
            if (code instanceof Number && ((Number) code).intValue() == 0) {
                log.info("库存 stock_info_list 每日同步完成：{}", res.get("data"));
            } else {
                log.warn("库存 stock_info_list 每日同步失败：{}", res);
            }
        } catch (Exception e) {
            log.warn("库存 stock_info_list 每日同步异常：{}", e.getMessage(), e);
        }
    }
}

