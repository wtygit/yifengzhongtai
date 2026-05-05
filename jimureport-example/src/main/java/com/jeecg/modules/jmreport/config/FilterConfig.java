package com.jeecg.modules.jmreport.config;

import com.jeecg.modules.jmreport.filter.JimuDragPageFilter;
import com.jeecg.modules.jmreport.filter.JimuReportPageFilter;
import com.jeecg.modules.jmreport.filter.TokenParameterFilter;
import com.jeecg.modules.jmreport.filter.ApiGeneratorPageFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 过滤器配置类
 */
@Configuration
public class FilterConfig {

    /**
     * 注册Token参数过滤器
     * 将URL参数中的token添加到请求头中，优先级最高，确保在Sa-Token处理之前执行
     */
    @Bean
    public FilterRegistrationBean<TokenParameterFilter> tokenParameterFilter() {
        FilterRegistrationBean<TokenParameterFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new TokenParameterFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(0); // 设置最高优先级，确保在其他过滤器之前执行
        return registrationBean;
    }

    /**
     * 注册报表工作台页面过滤器
     */
    @Bean
    public FilterRegistrationBean<JimuReportPageFilter> jimuReportPageFilter() {
        FilterRegistrationBean<JimuReportPageFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new JimuReportPageFilter());
        // 过滤报表工作台页面和登录页面的请求
        registrationBean.addUrlPatterns("/jmreport/list", "/login/login.html");
        registrationBean.setOrder(1);
        return registrationBean;
    }

    /**
     * 注册BI工作台页面过滤器，用于在左上角显示"一丰中台"
     */
    @Bean
    public FilterRegistrationBean<JimuDragPageFilter> jimuDragPageFilter() {
        FilterRegistrationBean<JimuDragPageFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new JimuDragPageFilter());
        // 过滤BI工作台相关页面（列表、设计器等）
        registrationBean.addUrlPatterns("/drag/*");
        registrationBean.setOrder(2);
        return registrationBean;
    }

    /**
     * 注册API工作台页面过滤器，用于在左上角显示"一丰中台"
     */
    @Bean
    public FilterRegistrationBean<ApiGeneratorPageFilter> apiGeneratorPageFilter() {
        FilterRegistrationBean<ApiGeneratorPageFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new ApiGeneratorPageFilter());
        // 过滤API工作台页面
        registrationBean.addUrlPatterns("/api-generator/list");
        registrationBean.setOrder(3);
        return registrationBean;
    }
}