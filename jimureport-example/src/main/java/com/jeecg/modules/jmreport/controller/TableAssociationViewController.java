package com.jeecg.modules.jmreport.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 多表关联与字段组合页面
 */
@Controller
public class TableAssociationViewController {

    @GetMapping("/table-association")
    public String index(Model model) {
        if (StpUtil.isLogin()) {
            model.addAttribute("loginUser", StpUtil.getLoginIdAsString());
        } else {
            model.addAttribute("loginUser", "未登录用户");
        }
        return "table-association/index";
    }
}

