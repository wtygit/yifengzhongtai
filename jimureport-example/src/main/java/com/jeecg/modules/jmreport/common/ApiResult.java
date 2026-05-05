package com.jeecg.modules.jmreport.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一接口返回结构：{code, msg, data}
 */
public class ApiResult {

    public static Map<String, Object> ok(Object data) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", 200);
        res.put("msg", "success");
        res.put("data", data);
        return res;
    }

    public static Map<String, Object> okMsg(String msg, Object data) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", 200);
        res.put("msg", msg);
        res.put("data", data);
        return res;
    }

    public static Map<String, Object> error(int code, String msg) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", code);
        res.put("msg", msg);
        res.put("data", null);
        return res;
    }

    public static Map<String, Object> error(String msg) {
        return error(500, msg);
    }
}

