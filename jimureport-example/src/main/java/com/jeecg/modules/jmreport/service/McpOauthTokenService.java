package com.jeecg.modules.jmreport.service;

public interface McpOauthTokenService {
    String issueAccessToken(String clientId, String scope);

    boolean validateAccessToken(String token);
}

