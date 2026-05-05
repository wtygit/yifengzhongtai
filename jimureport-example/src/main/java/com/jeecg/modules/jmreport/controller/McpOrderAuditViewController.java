package com.jeecg.modules.jmreport.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * MCP订单审核页面视图控制器
 */
@Controller
public class McpOrderAuditViewController {

    @Value("${mcp.order-audit.sound-new-url:}")
    private String soundNewUrl;

    @Value("${mcp.order-audit.sound-merged-url:}")
    private String soundMergedUrl;

    @Value("${mcp.order-audit.sound-save-url:}")
    private String soundSaveUrl;

    @Value("${mcp.order-audit.shipment-sound-new-url:}")
    private String shipmentSoundNewUrl;

    @Value("${mcp.order-audit.shipment-sound-merged-url:}")
    private String shipmentSoundMergedUrl;

    @GetMapping("/mcp/order-audit")
    public String orderAuditPage(Model model) {
        if (StpUtil.isLogin()) {
            String loginId = StpUtil.getLoginIdAsString();
            model.addAttribute("loginUser", loginId);
            Object sid = StpUtil.getSession().get("mcpAuditStoreId");
            model.addAttribute("mcpAuditStoreId", sid != null ? String.valueOf(sid) : "");
            Object bypass = StpUtil.getSession().get("mcpAuditAdminBypass");
            boolean adminBypass = bypass instanceof Boolean ? (Boolean) bypass : "true".equalsIgnoreCase(String.valueOf(bypass));
            // 兼容旧会话：若未写入 bypass，但登录ID不是 store:xxx，按管理员放行。
            if (!adminBypass && isLikelyAdminLogin(loginId)) {
                adminBypass = true;
            }
            model.addAttribute("mcpAuditAdminBypass", adminBypass);
        } else {
            model.addAttribute("loginUser", "未登录用户");
            model.addAttribute("mcpAuditStoreId", "");
            model.addAttribute("mcpAuditAdminBypass", false);
        }
        model.addAttribute("mcpOrderAuditSoundNew", blankToEmpty(soundNewUrl));
        model.addAttribute("mcpOrderAuditSoundMerged", blankToEmpty(soundMergedUrl));
        model.addAttribute("mcpOrderAuditSoundSave", blankToEmpty(soundSaveUrl));
        model.addAttribute("mcpOrderAuditShipmentSoundNew", blankToEmpty(shipmentSoundNewUrl));
        model.addAttribute("mcpOrderAuditShipmentSoundMerged", blankToEmpty(shipmentSoundMergedUrl));
        return "mcp-order-audit/index";
    }

    private static String blankToEmpty(String s) {
        return StringUtils.hasText(s) ? s.trim() : "";
    }

    private static boolean isLikelyAdminLogin(String loginId) {
        if (!StringUtils.hasText(loginId)) {
            return false;
        }
        String v = loginId.trim();
        return !v.startsWith("store:");
    }
}
