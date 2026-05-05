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
                .addExclude("/favicon.ico")
                .addExclude("/login/**")
                .addExclude("/doLogin")
                .addExclude("/ws/**")
                

                // 认证函数: 每次请求执行
                .setAuth(obj -> {
                    // 设置登录来源，方便退出登录时区分
                    AjaxRequestUtils.setLoginSessionInfo();
                    
                    // 检查是否需要进行登录检查
                    String path = SaHolder.getRequest().getRequestPath();
                    if (path.startsWith("/api-generator/design") || path.startsWith("/api-generator/list") || path.startsWith("/jmreport/list")) {
                        // 检查用户是否已登录，未登录会自动抛出NotLoginException异常
                        StpUtil.checkLogin();
                    }
                })

                // 异常处理函数：每次认证函数发生异常时执行此函数 
                .setError(e -> {
                    log.warn("---------- sa全局异常，path = " + SaHolder.getRequest().getRequestPath());
                    log.warn("---------- sa全局认证，token = " + StpUtil.getTokenValue());
                    
                    // 处理未登录异常
                    if (e instanceof cn.dev33.satoken.exception.NotLoginException) {
                        // 获取当前请求路径和参数
                        String path = SaHolder.getRequest().getRequestPath();
                        String queryString = "";
                        HttpServletRequest request = JimuSpringContextUtils.getHttpServletRequest();
                        if (request != null) {
                            queryString = request.getQueryString();
                        }
                        String fullUrl = path;
                        if (queryString != null && !queryString.isEmpty()) {
                            fullUrl += "?" + queryString;
                        }
                        
                        // 如果是HTML页面请求（不是API请求），重定向到登录页面
                        if (path.startsWith("/api-generator/design") || path.startsWith("/api-generator/list") || path.startsWith("/jmreport/list")) {
                            try {
                                // 重定向到登录页面，并带上原始请求的URL作为参数
                                SaHolder.getResponse().redirect("/login/login.html?redirect=" + java.net.URLEncoder.encode(fullUrl, "UTF-8"));
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                            return null;
                        }
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

}