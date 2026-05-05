package com.jeecg.modules.jmreport.config;

import com.jeecg.modules.jmreport.service.McpStoreLoginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 启动后异步同步海典门店表，避免登录页长期依赖直连海典拉下拉。
 */
@Slf4j
@Component
@Order(100)
public class McpStoreStartupSync implements ApplicationRunner {

    @Autowired(required = false)
    private McpStoreLoginService mcpStoreLoginService;

    @Override
    public void run(ApplicationArguments args) {
        if (mcpStoreLoginService == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                mcpStoreLoginService.ensureLocalTable();
                mcpStoreLoginService.syncFromHaidian();
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                if (root instanceof java.net.ConnectException) {
                    log.warn("启动时门店同步失败：主数据源无法连接（{}）。请启动 MySQL 并核对 spring.datasource；同步将稍后由定时任务重试。", root.getMessage());
                    log.debug("启动门店同步异常详情", e);
                } else {
                    log.warn("启动时门店同步失败，将依赖定时任务或直连海典回退: {}", e.getMessage());
                }
            }
        });
    }
}
