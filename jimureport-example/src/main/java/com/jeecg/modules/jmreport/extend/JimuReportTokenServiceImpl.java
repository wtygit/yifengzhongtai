package com.jeecg.modules.jmreport.extend;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.jeecg.modules.jmreport.satoken.config.SecurityConfig;
import com.jeecg.modules.jmreport.satoken.util.AjaxRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.jmreport.api.JmReportTokenServiceI;
import org.jeecg.modules.jmreport.common.constant.JmConst;
import org.jeecg.modules.jmreport.common.expetion.JimuReportException;
import org.jeecg.modules.jmreport.common.util.JimuSpringContextUtils;
import org.jeecg.modules.jmreport.common.util.OkConvertUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 自定义一丰报表鉴权(如果不进行自定义，则所有请求不做权限控制)
 * 1.自定义获取登录token
 * 2.自定义获取登录用户
 */
@Slf4j
@Component
public class JimuReportTokenServiceImpl implements JmReportTokenServiceI {
    @Autowired
    SecurityConfig securityConfig;
    
    /**
     * 通过请求获取Token
     * @param request
     * @return
     */
    @Override
    public String getToken(HttpServletRequest request) {
        String token = null;
        String requestPath = "";
        try {
            requestPath = SaHolder.getRequest().getRequestPath();
        } catch (Exception e) {
            // 忽略异常
        }
        
        // 优先从Sa-Token上下文获取token（这会自动从请求头X-Access-Token或Cookie中读取）
        try {
            token = StpUtil.getTokenValue();
            if (StringUtils.isNotEmpty(token)) {
                log.debug("------SA--TOKEN-----从Sa-Token上下文获取Token成功，RequestPath={}，Token = {}", requestPath, token);
                return token;
            }
        } catch (Exception e) {
            log.debug("从Sa-Token上下文获取Token失败: {}", e.getMessage());
        }
        
        // 如果Sa-Token上下文没有token，尝试从请求头获取
        if (request != null && StringUtils.isEmpty(token)) {
            token = request.getHeader("X-Access-Token");
            if (StringUtils.isNotEmpty(token)) {
                log.info("------SA--TOKEN-----从请求头X-Access-Token获取Token，RequestPath={}，Token = {}", requestPath, token);
                // 将token设置到Sa-Token存储中，让Sa-Token能够识别
                try {
                    SaHolder.getStorage().set(StpUtil.getTokenName(), token);
                } catch (Exception e) {
                    log.warn("设置Token到Sa-Token存储失败: {}", e.getMessage());
                }
                return token;
            }
        }
        
        // 最后尝试从URL参数获取token
        if (request != null && StringUtils.isEmpty(token)) {
            token = request.getParameter("token");
            if (StringUtils.isNotEmpty(token)) {
                log.info("------SA--TOKEN-----从URL参数获取Token，RequestPath={}，Token = {}", requestPath, token);
                // 将URL上的token设置到Sa-Token存储中，让Sa-Token能够识别
                try {
                    SaHolder.getStorage().set(StpUtil.getTokenName(), token);
                } catch (Exception e) {
                    log.warn("设置Token到Sa-Token存储失败: {}", e.getMessage());
                }
                return token;
            }
        }
        
        log.debug("------SA--TOKEN-----未获取到Token，RequestPath={}", requestPath);
        return token;
    }

    /**
     * 通过Token获取登录人用户名
     * @param token
     * @return
     */
    @Override
    public String getUsername(String token) {
        String username = StpUtil.getLoginIdAsString();
        log.debug("------SA--TOKEN-----RequestPath={} ，Token={} , LoginId={}", SaHolder.getRequest().getRequestPath(), token, username);
        return username;
    }

    /**
     * 自定义用户拥有的角色
     *
     * @param token
     * @return
     */
    @Override
    public String[] getRoles(String token) {
        //一丰内置三个角色 "admin","lowdeveloper","dbadeveloper"
        return new String[]{"admin","lowdeveloper","dbadeveloper"};
    }


    /**
     * 自定义用户拥有的权限指令
     * 
     * @param token
     * @return
     */
    @Override
    public String[] getPermissions(String token) {
        //drag:datasource:testConnection   仪表盘数据库连接测试
        //onl:drag:clear:recovery          清空回收站
        //drag:analysis:sql                SQL解析
        //drag:design:getTotalData         仪表盘对Online表单展示数据
        //drag:dataset:save                数据集保存
        //drag:dataset:delete              数据集删除
        //drag:datasource:saveOrUpate      数据源保存
        //drag:datasource:delete           数据源删除
        return new String[]{"drag:datasource:testConnection","onl:drag:clear:recovery","drag:analysis:sql","drag:design:getTotalData","onl:drag:page:delete",
                "drag:dataset:save","drag:dataset:delete","drag:datasource:saveOrUpate","`drag:datasource:delete"};
    }

    /**
     * Token校验
     * @param token
     * @return
     */
    @Override
    public Boolean verifyToken(String token) {
        try {
            if(securityConfig.getEnable()!=null && !securityConfig.getEnable()){
                // 如果security.enable=false,则不进行登录校验
                return true;
            }
            
            // 检查是否是API生成器的内部请求
            String requestPath = "";
            HttpServletRequest request = null;
            try {
                requestPath = SaHolder.getRequest().getRequestPath();
                request = JimuSpringContextUtils.getHttpServletRequest();
                
                // 如果请求路径包含数据源相关接口，且是本地请求，跳过Token校验
                if ((requestPath.contains("getDataSourceByPage") || 
                     requestPath.contains("getDataSourceById")) &&
                    request != null && request.getRemoteAddr().equals("127.0.0.1")) {
                    log.debug("内部请求跳过Token校验: {}", requestPath);
                    return true;
                }
            } catch (Exception e) {
                log.debug("获取请求信息失败: {}", e.getMessage());
            }
            
            // 如果token为空，尝试从请求中获取
            if (StringUtils.isEmpty(token) && request != null) {
                // 优先从请求头获取
                token = request.getHeader("X-Access-Token");
                // 如果请求头没有，从URL参数获取
                if (StringUtils.isEmpty(token)) {
                    token = request.getParameter("token");
                }
            }
            
            // 使用Sa-Token的checkLogin方法验证token
            // 由于TokenParameterFilter已经将URL参数中的token添加到请求头，Sa-Token应该能够自动识别
            StpUtil.checkLogin();
            log.debug("--SaToken verifyToken-成功！RequestPath={}，Token = {}", requestPath, token);
        } catch (Exception e) {
            log.warn("Token校验失败: token = {}，error:{}", token, e.getMessage());
            
            if(e instanceof NotLoginException){
                // 跳转登录页面
                try {
                    HttpServletRequest request = JimuSpringContextUtils.getHttpServletRequest();
                    if(!AjaxRequestUtils.isAjaxRequest(request)){
                        JimuSpringContextUtils.getHttpServletResponse().sendRedirect("/login/login.html");
                    }
                } catch (Exception ex) {
                    log.warn("重定向到登录页面失败: {}", ex.getMessage());
                }
                return false;
            }else{
                throw new JimuReportException(e);
            }
        }
        return true;
    }

//    /**
//     *  自定义请求头
//     * @return
//     */
//    @Override
//    public HttpHeaders customApiHeader() {
//        HttpHeaders header = new HttpHeaders();
//        header.add("custom-header1", "Please set a custom value 1");
//        header.add("token", "token value 2");
//        return header;
//    }

    /**
     * 自定义租户
     *
     * @return
     */
    @Override
    public String getTenantId() {
        String headerTenantId = null;
        HttpServletRequest request = JimuSpringContextUtils.getHttpServletRequest();
        if (request != null) {
            headerTenantId = request.getHeader(JmConst.HEADER_TENANT_KEY);
            if(OkConvertUtils.isEmpty(headerTenantId)){
                headerTenantId = request.getHeader(JmConst.HEADER_TENANT_ID);
            }
            if(OkConvertUtils.isEmpty(headerTenantId)){
                headerTenantId = request.getParameter(JmConst.TENANT_ID);
            }
        }
        return headerTenantId;
    }
}