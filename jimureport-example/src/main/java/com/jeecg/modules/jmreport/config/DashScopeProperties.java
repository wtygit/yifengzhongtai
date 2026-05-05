package com.jeecg.modules.jmreport.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 云雾API 配置属性（兼容 OpenAI 协议）
 */
@Data
@Component
@ConfigurationProperties(prefix = "yunwu.api")
public class DashScopeProperties {

    /**
     * API Key（支持环境变量 YUNWU_API_KEY）
     */
    private String apiKey;
    /**
     * 模型名称，如 gpt-4o-mini、gpt-3.5-turbo 等
     */
    private String model = "gpt-4o-mini";
    /**
     * Chat Completions 接口地址
     */
    private String url = "https://yunwu.ai/v1/chat/completions";
}
