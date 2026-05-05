package com.jeecg.modules.jmreport.satoken.config;

import com.jeecg.modules.jmreport.satoken.config.vo.User;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Role;
import org.springframework.stereotype.Component;


/**
 * 加载项目登录配置（使用 jmreport.login 前缀，避免与 spring.security 冲突）
 *
 * @author: jeecg-boot
 */
@Component("securityConfig")
@ConfigurationProperties(prefix = "jmreport.login")
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class SecurityConfig {
    private Boolean enable = true;
    /**
     * 登录账号和密码
     */
    private User user;

    /**
     * 门店号登录时统一密码（与海典 corecmsstore 同步到本地表后，用户名=门店编号，密码为此项）
     */
    private String storeDefaultPassword = "123456";

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Boolean getEnable() {
        return enable;
    }

    public void setEnable(Boolean enable) {
        this.enable = enable;
    }

    public String getStoreDefaultPassword() {
        return storeDefaultPassword;
    }

    public void setStoreDefaultPassword(String storeDefaultPassword) {
        this.storeDefaultPassword = storeDefaultPassword;
    }
}
