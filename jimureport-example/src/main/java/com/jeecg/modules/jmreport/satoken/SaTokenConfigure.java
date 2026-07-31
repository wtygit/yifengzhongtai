package com.jeecg.modules.jmreport.satoken;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.util.SaResult;
import com.jeecg.modules.jmreport.satoken.util.AjaxRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.jeecg.modules.jmreport.common.util.JimuSpringContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


/**
 * [Sa-Token 权限认证] 配置类
 *
 * @author click33
 */
@Slf4j
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    
    /**
     * 注册 Sa-Token 拦截器打开注解鉴权功能
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 拦截器打开注解鉴权功能 
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**")
                .excludePathPatterns("/ws/**");
    }

    /**
     * 注册 [Sa-Token 全局过滤器]
     */
    @Bean
    public SaServletFilter getSaServletFilter() {
        return new SaServletFilter()

                // 指定 [拦截路由] 与 [放行路由]
                .addInclude("/**")
                
                // 放行登录相关路径
                .addExclude("/login/login.html")
                .addExclude("/doLogin")
                
                // 放行MCP API接口（由 McpOauthBearerFilter 处理 OAuth Bearer Token 认证）
                .addExclude("/mcp/rpc")
                .addExclude("/mcp/core_oauth_token")
                .addExclude("/mcp/core_order_query")
                .addExclude("/mcp/core_insurance_query")
                .addExclude("/mcp/core_drug_query")
                .addExclude("/mcp/core_visit_strategy_query")
                .addExclude("/mcp/core_order_create")
                .addExclude("/mcp/core_profile_create")
                .addExclude("/mcp/core_shipment_create")
                .addExclude("/mcp/core_order_status_update")
                .addExclude("/mcp/core_order_approve")
                .addExclude("/mcp/core_order_reject")
                .addExclude("/mcp/core_order_void")
                .addExclude("/mcp/order-audit-list")
                .addExclude("/mcp/order-query-by-group-token")
                .addExclude("/mcp/chat-group-config/**")
                .addExclude("/mcp/order-audit-list")
                
                // 放行WebSocket连接
                .addExclude("/ws/**")
                
                // 放行静态资源
                .addExclude("/favicon.ico")
                .addExclude("/css/**")
                .addExclude("/js/**")
                .addExclude("/images/**")
                .addExclude("/fonts/**")
                .addExclude("/lib/**")
                .addExclude("/static/**")
                .addExclude("/error")
                
                // 认证函数: 每次请求执行
                .setAuth(obj -> {
                    // 设置登录来源，方便退出登录时区分
                    AjaxRequestUtils.setLoginSessionInfo();
                    
                    // 获取当前请求路径
                    String path = SaHolder.getRequest().getRequestPath();
                    HttpServletRequest request = JimuSpringContextUtils.getHttpServletRequest();
                    
                    // 判断是否为页面请求（需要Sa-Token登录态）
                    // 规则：路径以页面路由开头，且不是API接口
                    boolean isPageRequest = isNeedAuthPage(path, request);
                    
                    if (isPageRequest) {
                        // 检查用户是否已登录，未登录会自动抛出NotLoginException异常
                        StpUtil.checkLogin();
                    }
                    // API请求直接放行，由对应过滤器处理（如MCP的Bearer Token）
                })

                // 异常处理函数：每次认证函数发生异常时执行此函数 
                .setError(e -> {
                    log.warn("---------- sa全局异常，path = " + SaHolder.getRequest().getRequestPath());
                    log.warn("---------- sa全局认证，token = " + StpUtil.getTokenValue());
                    
                    // 处理未登录异常
                    if (e instanceof cn.dev33.satoken.exception.NotLoginException) {
                        String path = SaHolder.getRequest().getRequestPath();
                        HttpServletRequest request = JimuSpringContextUtils.getHttpServletRequest();
                        
                        // 构建重定向URL参数
                        String queryString = "";
                        if (request != null) {
                            queryString = request.getQueryString();
                        }
                        String fullUrl = path;
                        if (queryString != null && !queryString.isEmpty()) {
                            fullUrl += "?" + queryString;
                        }
                        
                        // 重定向到登录页面，并带上原始请求的URL作为参数
                        try {
                            SaHolder.getResponse().redirect("/login/login.html?redirect=" + java.net.URLEncoder.encode(fullUrl, "UTF-8"));
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                        return null;
                    }
                    
                    e.printStackTrace();
                    return SaResult.error(e.getMessage());
                })

                // 前置函数：在每次认证函数之前执行（BeforeAuth 不受 includeList 与 excludeList 的限制，所有请求都会进入）
                .setBeforeAuth(r -> {
                    // ---------- 设置一些安全响应头 ----------
                    SaHolder.getResponse()
                            // 服务器名称 
                            .setServer("sa-server")
                            /**
                             * frame-ancestors 'none' - 等同于 DENY 不可以
                             * frame-ancestors 'self' - 等同于 SAMEORIGIN 同域下可以
                             * frame-ancestors * - 允许所有域名嵌入
                             * frame-ancestors example.com example.net - 允许指定域名嵌入
                             */
                             // 使用 Content-Security-Policy 替代 X-Frame-Options，允许所有域名嵌入
                             .setHeader("Content-Security-Policy", "frame-ancestors *")
                            // 是否启用浏览器默认XSS防护： 0=禁用 | 1=启用 | 1; mode=block 启用, 并在检查到XSS攻击时，停止渲染页面
                            .setHeader("X-XSS-Protection", "1; mode=block")
                            // 禁用浏览器内容嗅探 
                            .setHeader("X-Content-Type-Options", "nosniff")
                    ;
                });
    }

    /**
     * 判断请求是否为需要Sa-Token登录认证的页面请求
     * 
     * @param path 请求路径
     * @param request HTTP请求对象
     * @return true=需要认证，false=不需要认证
     */
    private boolean isNeedAuthPage(String path, HttpServletRequest request) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        
        // 0. 先判断是否为静态资源（不需要认证）
        if (isStaticResource(path)) {
            return false;
        }
        
        // 1. 优先判断：如果路径明确是页面路由，则需要认证
        String[] pagePrefixes = {
            "/jmreport",           // 积木报表工作台
            "/drag",                // 积木BI大屏工作台
            "/api-generator",       // API生成器
            "/mcp/order-audit",     // MCP订单审核页面
            "/table-association",   // 表关联配置页面
            "/mcp/store-login-admin", // 门店登录管理
        };
        
        for (String prefix : pagePrefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        
        // 2. 特殊路径：根路径或直接访问html文件
        if (path.equals("/") || path.endsWith(".html")) {
            return true;
        }
        
        // 3. 通过请求头判断：如果Accept包含text/html，说明是浏览器页面请求
        if (request != null) {
            String acceptHeader = request.getHeader("Accept");
            if (acceptHeader != null && acceptHeader.contains("text/html")) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 判断是否为静态资源路径（不需要认证）
     */
    private boolean isStaticResource(String path) {
        if (path == null) {
            return false;
        }
        // 常见静态资源扩展名
        String[] staticExtensions = {
            ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", 
            ".ico", ".woff", ".woff2", ".ttf", ".eot", ".mp3", ".map",
            ".bmp", ".webp", ".pdf", ".doc", ".docx", ".xls", ".xlsx",
            ".zip", ".rar", ".7z", ".exe", ".msi"
        };
        
        for (String ext : staticExtensions) {
            if (path.endsWith(ext)) {
                return true;
            }
        }
        
        // 静态资源目录前缀（已在addExclude中配置的，但这里兜底判断）
        String[] staticPrefixes = {
            "/css/", "/js/", "/images/", "/fonts/", "/lib/", "/static/",
            "/favicon.ico", "/error"
        };
        
        for (String prefix : staticPrefixes) {
            if (path.startsWith(prefix) || path.equals(prefix)) {
                return true;
            }
        }
        
        return false;
    }

}