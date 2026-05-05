package com.jeecg.modules.jmreport.service.impl;

import com.jeecg.modules.jmreport.config.McpOauthProperties;
import com.jeecg.modules.jmreport.service.McpOauthTokenService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class McpOauthTokenServiceImpl implements McpOauthTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class TokenInfo {
        private final long expireAtEpochSecond;

        private TokenInfo(long expireAtEpochSecond) {
            this.expireAtEpochSecond = expireAtEpochSecond;
        }
    }

    private final Map<String, TokenInfo> tokenStore = new ConcurrentHashMap<>();
    private final McpOauthProperties oauthProperties;

    public McpOauthTokenServiceImpl(McpOauthProperties oauthProperties) {
        this.oauthProperties = oauthProperties;
    }

    @Override
    public String issueAccessToken(String clientId, String scope) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long now = Instant.now().getEpochSecond();
        long ttl = oauthProperties.getAccessTokenTtlSeconds();
        // ttl <= 0 表示永不过期
        long expireAt = ttl > 0 ? now + ttl : Long.MAX_VALUE;
        tokenStore.put(token, new TokenInfo(expireAt));
        return token;
    }

    @Override
    public boolean validateAccessToken(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        String trimmed = token.trim();
        String fixed = oauthProperties.getStaticAccessToken();
        if (StringUtils.hasText(fixed) && fixed.trim().equals(trimmed)) {
            return true;
        }
        TokenInfo info = tokenStore.get(trimmed);
        if (info == null) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        if (info.expireAtEpochSecond <= now) {
            tokenStore.remove(trimmed);
            return false;
        }
        return true;
    }
}

