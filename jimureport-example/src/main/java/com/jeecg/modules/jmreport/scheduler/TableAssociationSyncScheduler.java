package com.jeecg.modules.jmreport.scheduler;

import com.jeecg.modules.jmreport.service.TableAssociationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 多表关联数据集物化表每日同步任务：
 * 每天定时全量重建一次所有关联配置对应的物理表。
 */
@Slf4j
@Component
public class TableAssociationSyncScheduler {

    @Autowired
    private TableAssociationService tableAssociationService;

    /**
     * 每天凌晨 3 点执行一次全量同步。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void dailySync() {
        log.info("开始执行多表关联数据集物化表每日同步任务...");
        try {
            tableAssociationService.refreshAllMaterializedTables();
            log.info("多表关联数据集物化表每日同步任务执行完成");
        } catch (Exception e) {
            log.warn("多表关联数据集物化表每日同步任务执行失败：{}", e.getMessage(), e);
        }
    }
}

