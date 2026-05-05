package com.jeecg.modules.jmreport.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jeecg.modules.jmreport.config.DashScopeProperties;
import com.jeecg.modules.jmreport.dto.ChatExtractRequest;
import com.jeecg.modules.jmreport.dto.ExtractedEntity;
import com.jeecg.modules.jmreport.service.ChatExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聊天记录信息提取服务实现（调用云雾API）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatExtractionServiceImpl implements ChatExtractionService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String SYSTEM_PROMPT = """
        你是一个专业的信息提取专家。你的任务是从给定的微信聊天记录中，提取出所有的人名、电话号码和地址信息。
        限制条件：
        1. 不要编造：如果信息不存在，请填 null。
        2. 完整提取：地址要尽可能完整，电话只要数字，姓名要提取全名。
        3. 去重：相同的信息只提取一次。
        输出格式：请严格按照以下 JSON 格式输出，不要包含任何 Markdown 标记：
        {"entities": [{"name": "姓名或null", "phone": "电话或null", "address": "地址或null"}]}
        """;

    private final DashScopeProperties dashScopeProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<ExtractedEntity> extract(ChatExtractRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            log.warn("聊天记录为空，返回空列表");
            return new ArrayList<>();
        }
        String userContent = String.join("\n", request.getMessages());
        String apiKey = dashScopeProperties.getApiKey();
        String model = dashScopeProperties.getModel();
        String url = dashScopeProperties.getUrl();
        if (apiKey == null || apiKey.isBlank()) {
            log.error("云雾API api-key 未配置");
            throw new IllegalStateException("云雾API api-key 未配置");
        }
        OkHttpClient client = new OkHttpClient();
        try {
            String bodyJson = buildRequestBody(model, SYSTEM_PROMPT, userContent);
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(bodyJson, JSON))
                    .build();
            log.info("调用云雾API 提取信息，消息条数: {}", request.getMessages().size());
            try (Response response = client.newCall(httpRequest).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String msg = response.body() != null ? response.body().string() : "response body is null";
                    log.error("云雾API 请求失败，code: {}, body: {}", response.code(), msg);
                    throw new RuntimeException("云雾API 请求失败: " + response.code() + " " + msg);
                }
                String responseBody = response.body().string();
                String content = parseContentFromResponse(responseBody);
                return parseEntitiesFromContent(content);
            }
        } catch (IOException e) {
            log.error("调用云雾API IO 异常", e);
            throw new RuntimeException("调用云雾API失败: " + e.getMessage(), e);
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            log.error("提取聊天信息异常", e);
            throw new RuntimeException("提取失败: " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(String model, String systemPrompt, String userContent) throws IOException {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
        );
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "temperature", 0.1,
                "response_format", Map.of("type", "json_object")
        );
        return objectMapper.writeValueAsString(body);
    }

    private String parseContentFromResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isEmpty()) {
            log.error("云雾API 返回无 choices，body: {}", responseBody);
            throw new RuntimeException("云雾API 返回格式异常：无 choices");
        }
        JsonNode message = choices.get(0).path("message");
        JsonNode content = message.path("content");
        if (content.isMissingNode() || !content.isTextual()) {
            log.error("云雾API 返回无 message.content，body: {}", responseBody);
            throw new RuntimeException("云雾API 返回格式异常：无 content");
        }
        return content.asText();
    }

    private List<ExtractedEntity> parseEntitiesFromContent(String content) throws IOException {
        JsonNode root = objectMapper.readTree(content);
        JsonNode entitiesNode = root.path("entities");
        if (!entitiesNode.isArray()) {
            log.warn("返回 JSON 无 entities 数组，content: {}", content);
            return new ArrayList<>();
        }
        List<ExtractedEntity> result = new ArrayList<>();
        for (JsonNode node : entitiesNode) {
            String name = nullIfNullOrBlank(node.path("name").asText(null));
            String phone = nullIfNullOrBlank(node.path("phone").asText(null));
            String address = nullIfNullOrBlank(node.path("address").asText(null));
            result.add(new ExtractedEntity(name, phone, address));
        }
        log.info("提取到实体数量: {}", result.size());
        return result;
    }

    private static String nullIfNullOrBlank(String s) {
        if (s == null || s.isBlank() || "null".equalsIgnoreCase(s.trim())) {
            return null;
        }
        return s.trim();
    }
}
