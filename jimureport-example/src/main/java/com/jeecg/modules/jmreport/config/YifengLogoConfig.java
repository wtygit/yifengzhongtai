package com.jeecg.modules.jmreport.config;

/**
 * 一丰中台Logo配置
 * 用于统一管理"一丰中台"图片的base64编码
 */
public class YifengLogoConfig {
    
    /**
     * "一丰中台"图片的base64编码
     * 格式：data:image/png;base64,xxxxx 或 data:image/jpeg;base64,xxxxx
     * 
     * 使用方法：
     * 1. 将"一丰中台"图片转换为base64编码
     * 2. 将base64编码填入下面的字符串中
     * 3. 如果为空字符串，则使用文字"一丰中台"代替
     */
    public static final String YIFENG_LOGO_BASE64 = "";
    
    /**
     * 获取Logo base64编码
     * @return base64编码的图片，如果为空则返回null
     */
    public static String getLogoBase64() {
        if (YIFENG_LOGO_BASE64 == null || YIFENG_LOGO_BASE64.trim().isEmpty()) {
            return null;
        }
        return YIFENG_LOGO_BASE64;
    }
    
    /**
     * 检查是否提供了Logo图片
     * @return true表示提供了图片，false表示使用文字
     */
    public static boolean hasLogoImage() {
        return getLogoBase64() != null;
    }
}
