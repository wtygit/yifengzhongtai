//package com.jeecg.modules.jmreport.satoken.exception;
//
//import cn.dev33.satoken.exception.NotLoginException;
//import cn.dev33.satoken.util.SaResult;
//import com.jeecg.modules.jmreport.satoken.util.AjaxRequestUtils;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import org.springframework.web.bind.annotation.ExceptionHandler;
//import org.springframework.web.bind.annotation.RestControllerAdvice;
//
//import java.io.IOException;
//import java.net.URLEncoder;
//
//@RestControllerAdvice
//public class GlobalExceptionHandler {
//
//    // 捕获未登录异常
//    @ExceptionHandler(NotLoginException.class)
//    public SaResult handlerNotLoginException(NotLoginException e,
//                                             HttpServletRequest request,
//                                             HttpServletResponse response) {
//
//        // AJAX 请求返回 JSON
//        if (AjaxRequestUtils.isAjaxRequest(request)) {
//            return SaResult.error("未登录，请先登录").setCode(401);
//        }
//
//        // 普通请求重定向到登录页
//        try {
//            response.sendRedirect("/login/login.html?redirect=" + URLEncoder.encode(request.getRequestURI(), "UTF-8"));
//        } catch (IOException ex) {
//            ex.printStackTrace();
//        }
//
//        return SaResult.error("未登录");
//    }
//
//}