package com.jeecg.modules.jmreport.service;

import java.util.List;
import java.util.Map;

/**
 * 海典 corecmsstore → 本地 jm_mcp_store_login 同步，以及门店号登录校验。
 */
public interface McpStoreLoginService {

    void ensureLocalTable();

    /**
     * 从海典库拉取门店写入本地表，返回写入/更新行数（失败返回 0）。
     */
    int syncFromHaidian();

    /**
     * 管理员账号校验：优先本地数据库配置（可改密），无配置时回退 yml。
     */
    boolean validateAdminLogin(String username, String password);

    /**
     * 用户名即门店编号时：校验密码为配置的门店统一密码，且门店存在于本地同步表。
     */
    boolean validateStoreLogin(String storeId, String password);

    /**
     * 供 /mcp/mini-program-stores 使用：优先本地同步结果。
     */
    List<Map<String, Object>> listSyncedStores();

    /**
     * 管理员：门店账号列表（含是否已设专属密码），不含明文密码。
     */
    List<Map<String, Object>> listStoreAccountsForAdmin();

    /**
     * 管理员：设置/清空某门店登录密码；newPassword 为空则清空，回退为门店默认密码。
     */
    int setStoreLoginPassword(String storeId, String newPassword);

    /**
     * 管理员：修改所有门店默认登录密码（jm_mcp_login_config.store_default_password）。
     */
    void setStoreDefaultPassword(String newPassword);

    /**
     * 管理员：修改管理员登录密码（需校验当前密码）。
     */
    boolean setAdminPassword(String currentPassword, String newPassword);

    /**
     * 读取门店专属密码明文（login_password）；未设置时返回 null。仅应由管理员接口调用。
     */
    String getStoreCustomPasswordPlain(String storeId);

    /**
     * 手动新增或更新门店账号（写入 jm_mcp_store_login），用于海典尚未同步到的门店号。
     */
    int upsertStoreAccount(String storeId, String storeName);
}
