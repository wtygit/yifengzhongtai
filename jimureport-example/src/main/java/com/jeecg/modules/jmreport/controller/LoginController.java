package com.jeecg.modules.jmreport.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.jeecg.modules.jmreport.service.McpStoreLoginService;
import com.jeecg.modules.jmreport.satoken.config.SecurityConfig;
import com.jeecg.modules.jmreport.satoken.util.AjaxRequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;

/**
 * 一丰报表-设置默认首页跳转
 */
@Controller
public class LoginController {
    private Logger logger = LoggerFactory.getLogger(LoginController.class);
    public final static String LOGIN_PAGE = "/login/login.html";
    /** 登录失败时写入短效 Cookie，供静态登录页展示提示（避免网关/浏览器丢弃 URL 参数）。 */
    private static final String LOGIN_ERR_COOKIE = "mcp_login_err";

    @Autowired
    SecurityConfig securityConfig;

    @Autowired(required = false)
    private McpStoreLoginService mcpStoreLoginService;

    /** 无 redirect 参数时跳转；默认积木报表列表（依赖本机 jimureport 库）。主库不可用时可在 yml 改为 /mcp/order-audit */
    @Value("${jmreport.login.success-redirect:/jmreport/list}")
    private String loginSuccessRedirect;

    /**
     * 登录请求
     *
     * @param username
     * @param password
     * @param req
     * @return
     */
    @GetMapping("/doLogin")
    public String login(@RequestParam String username, @RequestParam String password,
                        @RequestParam(required = false) String redirect,
                        jakarta.servlet.http.HttpServletRequest req,
                        HttpServletResponse response) {
        logger.info("登录请求，用户名：{}", username);
        // 此处通过yml配置文件获取登录账号和密码，实际项目中请从数据库读取进行验证
        if (securityConfig == null || securityConfig.getUser() == null) {
            logger.error("登录配置缺失：jmreport.login.user 未配置，请在 application.yml / application-prod.yml 中配置 jmreport.login.user.name 与 jmreport.login.user.password");
            setLoginErrorFlashCookie(response, "config");
            return "redirect:" + LOGIN_PAGE + "?error=config";
        }
        boolean adminOk = mcpStoreLoginService != null
                ? mcpStoreLoginService.validateAdminLogin(username, password)
                : (securityConfig.getUser().getName().equals(username) && securityConfig.getUser().getPassword().equals(password));
        if (adminOk) {
            clearLoginErrorFlashCookie(response);
            StpUtil.login(username.trim());
            logger.info("登录成功（管理员），当前会话tokeName={}, tokenValue={}", StpUtil.getTokenName(), StpUtil.getTokenValue());
            StpUtil.getSession().delete("mcpAuditStoreId");
            StpUtil.getSession().set("mcpAuditAdminBypass", true);

            // 设置登录来源，方便退出登录时区分
            AjaxRequestUtils.setLoginSessionInfo();
            
            // 如果有redirect参数，则跳转到该URL，否则跳转到默认页面
            if (redirect != null && !redirect.isEmpty()) {
                logger.info("登录成功，跳转到原始请求页面：" + redirect);
                return "redirect:" + redirect;
            } else {
                logger.info("登录成功，跳转到默认页面：{}", loginSuccessRedirect);
                return "redirect:" + loginSuccessRedirect;
            }
        }
        if (mcpStoreLoginService != null && mcpStoreLoginService.validateStoreLogin(username, password)) {
            clearLoginErrorFlashCookie(response);
            String sid = username.trim();
            StpUtil.login("store:" + sid);
            StpUtil.getSession().set("mcpAuditStoreId", sid);
            StpUtil.getSession().set("mcpAuditAdminBypass", false);
            logger.info("登录成功（门店账号 storeId={}），tokenValue={}", sid, StpUtil.getTokenValue());
            AjaxRequestUtils.setLoginSessionInfo();
            if (redirect != null && !redirect.isEmpty()) {
                return "redirect:" + redirect;
            }
            return "redirect:" + loginSuccessRedirect;
        } else {
            logger.error("登录失败，用户名或密码错误");
            setLoginErrorFlashCookie(response, "1");
            // 返回密码错误提示和原始redirect参数，需进行URL编码
            String errorUrl = LOGIN_PAGE + "?error=1";
            if (redirect != null && !redirect.isEmpty()) {
                try {
                    errorUrl += "&redirect=" + URLEncoder.encode(redirect, "UTF-8");
                } catch (Exception ex) {
                    logger.error("URL编码失败：" + ex.getMessage());
                }
            }
            return "redirect:" + errorUrl;
        }
    }

    private static void setLoginErrorFlashCookie(HttpServletResponse response, String value) {
        Cookie c = new Cookie(LOGIN_ERR_COOKIE, value);
        c.setPath("/");
        c.setMaxAge(120);
        c.setHttpOnly(false);
        response.addCookie(c);
    }

    private static void clearLoginErrorFlashCookie(HttpServletResponse response) {
        Cookie c = new Cookie(LOGIN_ERR_COOKIE, "");
        c.setPath("/");
        c.setMaxAge(0);
        c.setHttpOnly(false);
        response.addCookie(c);
    }


    /**
     * 首页跳转
     *
     * @param model
     * @return
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("name", "jimureport");
        return "jmreport/list"; // 视图重定向 - 跳转
    }

    /**
     * 查询登录状态
     *
     * @return
     */
    @RequestMapping("/isLogin")
    public String isLogin() {
        logger.info("查询登录状态:{}", StpUtil.getTokenInfo());
        return "当前会话是否登录：" + StpUtil.isLogin();
    }

    /**
     * 退出登录
     *
     * @return
     */
    @RequestMapping("/logout")
    public String logout() {
        StpUtil.logout();
        return "redirect:" + LOGIN_PAGE;
    }
}