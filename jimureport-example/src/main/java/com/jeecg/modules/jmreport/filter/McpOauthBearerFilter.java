package com.jeecg.modules.jmreport.filter;

import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jeecg.modules.jmreport.config.McpOauthProperties;
import com.jeecg.modules.jmreport.service.McpOauthTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class McpOauthBearerFilter extends OncePerRequestFilter {
    private final McpOauthProperties oauthProperties;
    private final McpOauthTokenService tokenService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!oauthProperties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        if (!StringUtils.hasText(path)) {
            return true;
        }
        // 仅保护 MCP API，页面路由（如 /mcp/order-audit）及系统其它接口不做 Bearer 校验
        if (path.startsWith("/mcp/oauth/token")) {
            return true;
        }
        if ("/mcp/core_oauth_token".equals(path)) {
            return true;
        }
        return !isProtectedMcpApiPath(path);
    }

    private boolean isProtectedMcpApiPath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        // JSON-RPC MCP 主入口
        if ("/mcp/rpc".equals(path)) {
            return true;
        }
        // MCP 直连核心工具接口
        if (path.startsWith("/mcp/core_")) {
            return true;
        }
        // MCP 相关查询与配置接口（群分词下拉与 /mcp/order-audit-list 同源由页面直调，不带 Bearer，故不纳入 OAuth）
        return path.startsWith("/mcp/order-query-by-group-token")
                || path.startsWith("/mcp/chat-group-config/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        // 后台审核页面通过网页登录（Sa-Token session）调用的核心接口，允许会话态放行；
        // 其它 MCP 对接接口仍按 Bearer Token 校验。
        if (isSessionAuditPath(path) && isSessionLoginSafe(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String auth = request.getHeader("Authorization");
        String token = null;
        if (StringUtils.hasText(auth) && auth.startsWith("Bearer ")) {
            token = auth.substring(7).trim();
        }
        if (!tokenService.validateAccessToken(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "error", "invalid_token",
                    "error_description", "A valid Bearer access token is required"
            ));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isSessionAuditPath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        return "/mcp/core_order_approve".equals(path)
                || "/mcp/core_order_reject".equals(path)
                || "/mcp/core_order_void".equals(path)
                // 审核页面“手动新增”会话态直调：无需 Bearer
                || "/mcp/core_order_create".equals(path)
                || "/mcp/core_shipment_create".equals(path);
    }

    private boolean isSessionLoginSafe(HttpServletRequest request) {
        try {
            return StpUtil.isLogin();
        } catch (SaTokenContextException e) {
            // 当前过滤器阶段 SaTokenContext 可能尚未初始化，回退到 HttpSession 粗判
            HttpSession session = request.getSession(false);
            if (session == null) {
                return false;
            }
            return session.getAttribute("loginFrom") != null;
        } catch (Exception e) {
            return false;
        }
    }
}

