package com.jeecg.modules.jmreport.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mcp.oauth")
public class McpOauthProperties {
    /**
     * 是否启用 MCP OAuth2 鉴权。
     */
    private boolean enabled = true;

    /**
     * client_credentials 的 client_id。
     */
    private String clientId = "mcp-client";

    /**
     * client_credentials 的 client_secret。
     */
    private String clientSecret = "change-me-please";

    /**
     * scope（可选）。
     */
    private String scope = "mcp.read mcp.write";

    /**
     * access_token 过期时间（秒）。
     */
    private long accessTokenTtlSeconds = -1;

    /**
     * 固定 Bearer Token（非空则始终校验通过，调用方无需再调 /mcp/core_oauth_token）。
     * 生产环境务必通过环境变量 MCP_OAUTH_STATIC_TOKEN 覆盖为强随机串。
     */
    private String staticAccessToken = "";
}

