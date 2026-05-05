package com.jeecg.modules.jmreport.scheduler;

import com.jeecg.modules.jmreport.service.McpStoreLoginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时将海典 antis_yifengdata_hub.corecmsstore 同步到本地 jm_mcp_store_login。
 */
@Slf4j
@Component
public class McpStoreSyncScheduler {

    @Autowired(required = false)
    private McpStoreLoginService mcpStoreLoginService;

    @Scheduled(fixedDelayString = "${jmreport.login.store-sync-interval-ms:1800000}")
    public void syncStores() {
        if (mcpStoreLoginService == null) {
            return;
        }
        try {
            mcpStoreLoginService.syncFromHaidian();
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            if (root instanceof java.net.ConnectException) {
                log.warn("定时门店同步失败：主数据源无法连接（{}）。请启动 MySQL 并核对 application.yml 中 spring.datasource（默认 localhost:3306/jimureport），或通过环境变量 MYSQL-HOST / MYSQL-PORT 等指向可用实例。", root.getMessage());
                log.debug("定时门店同步异常详情", e);
            } else {
                log.warn("定时门店同步失败", e);
            }
        }
    }
}
