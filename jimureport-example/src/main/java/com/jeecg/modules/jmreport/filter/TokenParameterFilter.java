package com.jeecg.modules.jmreport.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Token参数过滤器
 * 将URL参数中的token添加到请求头中，以便Sa-Token能够识别
 */
@Slf4j
public class TokenParameterFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // 初始化方法，空实现
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        
        // 检查请求头中是否已经有X-Access-Token
        String tokenInHeader = httpRequest.getHeader("X-Access-Token");
        
        // 如果请求头中没有token，尝试从URL参数中获取
        if (StringUtils.isEmpty(tokenInHeader)) {
            String tokenInParam = httpRequest.getParameter("token");
            if (StringUtils.isNotEmpty(tokenInParam)) {
                log.debug("从URL参数获取token，添加到请求头: {}", tokenInParam);
                // 创建一个包装的请求，将token添加到请求头中
                HttpServletRequestWrapper wrappedRequest = new HttpServletRequestWrapper(httpRequest) {
                    @Override
                    public String getHeader(String name) {
                        if ("X-Access-Token".equalsIgnoreCase(name)) {
                            return tokenInParam;
                        }
                        return super.getHeader(name);
                    }
                    
                    @Override
                    public Enumeration<String> getHeaderNames() {
                        Map<String, String> headers = new HashMap<>();
                        Enumeration<String> originalHeaders = super.getHeaderNames();
                        while (originalHeaders.hasMoreElements()) {
                            String headerName = originalHeaders.nextElement();
                            headers.put(headerName, super.getHeader(headerName));
                        }
                        // 添加X-Access-Token到请求头
                        headers.put("X-Access-Token", tokenInParam);
                        return Collections.enumeration(headers.keySet());
                    }
                    
                    @Override
                    public Enumeration<String> getHeaders(String name) {
                        if ("X-Access-Token".equalsIgnoreCase(name)) {
                            return Collections.enumeration(Collections.singletonList(tokenInParam));
                        }
                        return super.getHeaders(name);
                    }
                };
                chain.doFilter(wrappedRequest, response);
                return;
            }
        }
        
        // 如果请求头中已经有token，或者URL参数中没有token，直接放行
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        // 销毁方法，空实现
    }
}
