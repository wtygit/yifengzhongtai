package com.jeecg.modules.jmreport.controller;

import com.jeecg.modules.jmreport.config.McpOauthProperties;
import com.jeecg.modules.jmreport.service.McpOauthTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/mcp/oauth")
@RequiredArgsConstructor
public class McpOauthController {
    private final McpOauthProperties oauthProperties;
    private final McpOauthTokenService tokenService;

    @PostMapping(value = "/token", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Map<String, Object> token(
            @RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "client_secret", required = false) String clientSecret,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        if (!oauthProperties.isEnabled()) {
            return Map.of("error", "temporarily_disabled", "error_description", "OAuth is disabled");
        }
        if (!"client_credentials".equals(grantType)) {
            return Map.of("error", "unsupported_grant_type", "error_description", "grant_type must be client_credentials");
        }

        String[] creds = parseBasicAuth(authorization);
        if (!StringUtils.hasText(clientId) && creds != null) {
            clientId = creds[0];
        }
        if (!StringUtils.hasText(clientSecret) && creds != null) {
            clientSecret = creds[1];
        }

        if (!oauthProperties.getClientId().equals(clientId) || !oauthProperties.getClientSecret().equals(clientSecret)) {
            return Map.of("error", "invalid_client", "error_description", "client authentication failed");
        }

        String actualScope = StringUtils.hasText(scope) ? scope : oauthProperties.getScope();
        String accessToken = tokenService.issueAccessToken(clientId, actualScope);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("access_token", accessToken);
        data.put("token_type", "Bearer");
        data.put("expires_in", oauthProperties.getAccessTokenTtlSeconds());
        data.put("scope", actualScope);
        return data;
    }

    private static String[] parseBasicAuth(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Basic ")) {
            return null;
        }
        try {
            String b64 = authorization.substring(6).trim();
            String plain = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            int idx = plain.indexOf(':');
            if (idx <= 0) {
                return null;
            }
            return new String[]{plain.substring(0, idx), plain.substring(idx + 1)};
        } catch (Exception e) {
            return null;
        }
    }
}

