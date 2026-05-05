package com.jeecg.modules.jmreport.controller;

import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import com.jeecg.modules.jmreport.common.ApiResult;
import com.jeecg.modules.jmreport.service.McpStoreLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 管理员维护门店登录密码、门店默认密码、管理员密码（需管理员会话）。
 */
@Controller
@RequiredArgsConstructor
public class McpStoreLoginAdminController {

    private final McpStoreLoginService mcpStoreLoginService;

    @GetMapping("/mcp/store-login-admin")
    public String page() {
        if (!isMcpAdmin()) {
            return "redirect:/login/login.html?redirect=" + URLEncoder.encode("/mcp/store-login-admin", StandardCharsets.UTF_8);
        }
        return "mcp-store-login-admin";
    }

    @GetMapping("/mcp/store-login-admin/api/stores")
    @ResponseBody
    public Map<String, Object> listStores() {
        if (!isMcpAdmin()) {
            return ApiResult.error(403, "需要管理员登录");
        }
        return ApiResult.ok(mcpStoreLoginService.listStoreAccountsForAdmin());
    }

    /**
     * 查看门店专属密码明文（仅管理员会话；请勿对外暴露）。
     */
    @GetMapping("/mcp/store-login-admin/api/store-password-view")
    @ResponseBody
    public Map<String, Object> viewStorePassword(@RequestParam String storeId) {
        if (!isMcpAdmin()) {
            return ApiResult.error(403, "需要管理员登录");
        }
        if (!StringUtils.hasText(storeId)) {
            return ApiResult.error(400, "storeId 不能为空");
        }
        String plain = mcpStoreLoginService.getStoreCustomPasswordPlain(storeId.trim());
        if (plain == null) {
            return ApiResult.error(404, "该门店未设置专属密码（使用门店默认密码）");
        }
        return ApiResult.ok(Map.of("storeId", storeId.trim(), "password", plain));
    }

    @PostMapping("/mcp/store-login-admin/api/store-password")
    @ResponseBody
    public Map<String, Object> updateStorePassword(@RequestBody Map<String, Object> body) {
        if (!isMcpAdmin()) {
            return ApiResult.error(403, "需要管理员登录");
        }
        String storeId = body != null && body.get("storeId") != null ? String.valueOf(body.get("storeId")).trim() : "";
        String newPassword = body != null && body.get("newPassword") != null ? String.valueOf(body.get("newPassword")) : "";
        if (!StringUtils.hasText(storeId)) {
            return ApiResult.error(400, "storeId 不能为空");
        }
        int n = mcpStoreLoginService.setStoreLoginPassword(storeId, newPassword);
        if (n <= 0) {
            return ApiResult.error(404, "未找到该门店，请先同步海典门店表或检查门店编号");
        }
        return ApiResult.okMsg("已更新", Map.of("storeId", storeId));
    }

    @PostMapping("/mcp/store-login-admin/api/default-password")
    @ResponseBody
    public Map<String, Object> updateDefaultPassword(@RequestBody Map<String, Object> body) {
        if (!isMcpAdmin()) {
            return ApiResult.error(403, "需要管理员登录");
        }
        String pwd = body != null && body.get("newPassword") != null ? String.valueOf(body.get("newPassword")).trim() : "";
        try {
            mcpStoreLoginService.setStoreDefaultPassword(pwd);
        } catch (IllegalArgumentException e) {
            return ApiResult.error(400, e.getMessage());
        }
        return ApiResult.okMsg("门店默认密码已更新", null);
    }

    @PostMapping("/mcp/store-login-admin/api/admin-password")
    @ResponseBody
    public Map<String, Object> updateAdminPassword(@RequestBody Map<String, Object> body) {
        if (!isMcpAdmin()) {
            return ApiResult.error(403, "需要管理员登录");
        }
        String cur = body != null && body.get("currentPassword") != null ? String.valueOf(body.get("currentPassword")) : "";
        String next = body != null && body.get("newPassword") != null ? String.valueOf(body.get("newPassword")) : "";
        if (!mcpStoreLoginService.setAdminPassword(cur, next)) {
            return ApiResult.error(400, "当前管理员密码不正确或新密码无效");
        }
        return ApiResult.okMsg("管理员密码已更新，请重新登录", null);
    }

    private boolean isMcpAdmin() {
        try {
            if (!StpUtil.isLogin()) {
                return false;
            }
            Object v = StpUtil.getSession().get("mcpAuditAdminBypass");
            if (v instanceof Boolean b) {
                if (b) {
                    return true;
                }
            } else if ("true".equalsIgnoreCase(String.valueOf(v))) {
                return true;
            }
            String loginId = StpUtil.getLoginIdAsString();
            return StringUtils.hasText(loginId) && !loginId.trim().startsWith("store:");
        } catch (SaTokenContextException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
