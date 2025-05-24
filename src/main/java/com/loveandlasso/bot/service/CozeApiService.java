package com.loveandlasso.bot.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loveandlasso.bot.constant.BotConstants;
import com.loveandlasso.bot.controller.TelegramBotController;
import com.loveandlasso.bot.dto.CozeApiResponse;
import com.loveandlasso.bot.model.User;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class CozeApiService {

    private final RestTemplate restTemplate;
    private static final Logger log = LoggerFactory.getLogger(TelegramBotController.class);

    @Value("${coze.api.url}")
    private String apiUrl;

    @Value("${coze.api.key}")
    private String apiKey;

    @Value("${coze.bot.id}")
    private String botId;

    @Autowired
    public CozeApiService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public CozeApiResponse sendRequest(String query, @NotNull User user) {

        String finalQuery = query != null ? query.trim() : "Привет!";

        log.info("🚀 Sending to Coze API - Length: {}", finalQuery.length());
        log.debug("🚀 Content: [{}]", finalQuery.substring(0, Math.min(finalQuery.length(), 500)) + "...");


        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("bot_id", botId);
        requestMap.put("user_id", user.getTelegramId().toString());
        requestMap.put("stream", false);
        requestMap.put("auto_save_history", true);

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", finalQuery);
        message.put("content_type", "text");

        List<Map<String, Object>> additionalMessages = new ArrayList<>();
        additionalMessages.add(message);
        requestMap.put("additional_messages", additionalMessages);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestMap, headers);

        try {
            log.info("📡 Making HTTP request to Coze API...");

            ResponseEntity<JsonNode> responseEntity = restTemplate.exchange(
                    apiUrl + "/v3/chat",
                    HttpMethod.POST,
                    entity,
                    JsonNode.class
            );

            log.info("✅ Received HTTP response: status={}", responseEntity.getStatusCode());

            JsonNode jsonResponse = responseEntity.getBody();
            log.info("📝 Response body: {}", jsonResponse);

            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            CozeApiResponse response = objectMapper.treeToValue(jsonResponse, CozeApiResponse.class);

            if (response != null && response.getData() != null &&
                    "in_progress".equals(response.getData().getStatus())) {

                response = pollForCompletion(response.getData().getId(),
                        response.getData().getConversationId());
            }

            return response;

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("❌ HTTP Error: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return createErrorResponse("Ошибка HTTP: " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            log.error("❌ Connection Error: {}", e.getMessage());
            return createErrorResponse("Ошибка соединения");
        } catch (Exception e) {
            log.error("❌ Unexpected Error: {}", e.getMessage(), e);
            return createErrorResponse("Неожиданная ошибка: " + e.getMessage());
        }
    }

    private CozeApiResponse pollForCompletion(String chatId, String conversationId) {
        int maxAttempts = 30;
        int attemptDelay = 1000;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                Thread.sleep(attemptDelay);

                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Bearer " + apiKey);

                HttpEntity<String> entity = new HttpEntity<>(headers);

                String statusUrl = apiUrl + "/v3/chat/retrieve?conversation_id=" + conversationId + "&chat_id=" + chatId;

                ParameterizedTypeReference<Map<String, Object>> typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

                ResponseEntity<Map<String, Object>> responseEntity = restTemplate.exchange(
                        statusUrl,
                        HttpMethod.GET,
                        entity,
                        typeRef
                );

                Map<String, Object> rawResponse = responseEntity.getBody();

                ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

                CozeApiResponse statusResponse = objectMapper.convertValue(rawResponse, CozeApiResponse.class);

                if (statusResponse != null && statusResponse.getData() != null) {
                    String status = statusResponse.getData().getStatus();

                    if ("completed".equals(status)) {
                        return getMessages(conversationId, chatId);
                    } else if ("failed".equals(status)) {
                        return createErrorResponse("Не удалось обработать запрос");
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Игнорируем ошибки
            }
        }

        return createErrorResponse("Превышено время ожидания ответа");
    }

    @NotNull
    private CozeApiResponse getMessages(String conversationId, String chatId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(headers);

            String messagesUrl = apiUrl + "/v3/chat/message/list?conversation_id=" + conversationId + "&chat_id=" + chatId;

            ParameterizedTypeReference<Map<String, Object>> typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

            ResponseEntity<Map<String, Object>> responseEntity = restTemplate.exchange(
                    messagesUrl,
                    HttpMethod.GET,
                    entity,
                    typeRef
            );

            String botResponseText = extractBotResponseFromMessages(responseEntity.getBody());

            CozeApiResponse response = new CozeApiResponse();
            response.setCode(0);
            response.setMsg("success");
            response.setResponseText(botResponseText);

            return response;

        } catch (Exception e) {
            return createErrorResponse("Ошибка получения сообщений");
        }
    }

    @NotNull
    @SuppressWarnings("unchecked")
    private String extractBotResponseFromMessages(Map<String, Object> messagesResponse) {
        try {
            if (messagesResponse != null && messagesResponse.containsKey("data")) {
                Object dataObject = messagesResponse.get("data");

                if (dataObject instanceof List<?> dataList) {
                    for (Object messageObj : dataList) {
                        if (messageObj instanceof Map<?, ?> messageMap) {
                            Map<String, Object> message = (Map<String, Object>) messageMap;
                            String role = (String) message.get("role");
                            String type = (String) message.get("type");

                            if ("assistant".equals(role) && "answer".equals(type)) {
                                String content = (String) message.get("content");
                                if (content != null && !content.trim().isEmpty()) {
                                    return content;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Игнорируем ошибки
        }

        return "Ответ получен, но содержимое не удалось извлечь.";
    }

    public String extractResponseText(CozeApiResponse response) {
        if (!isValidResponse(response)) {
            if (response != null && response.getMsg() != null) {
                return response.getMsg();
            }
            return "Извините, не удалось получить ответ от сервиса.";
        }

        if (response.getResponseText() != null && !response.getResponseText().isEmpty()) {
            return response.getResponseText();
        }

        if (response.getData() != null && "completed".equals(response.getData().getStatus())) {
            return "Ответ получен, но содержимое недоступно.";
        }

        if (response.getData() != null && "in_progress".equals(response.getData().getStatus())) {
            return "Ваш запрос обрабатывается. Попробуйте повторить запрос через несколько секунд.";
        }

        return "Получен неожиданный формат ответа.";
    }

    @NotNull
    private CozeApiResponse createErrorResponse(String errorMessage) {
        CozeApiResponse response = new CozeApiResponse();
        response.setCode(-1);
        response.setMsg(errorMessage);
        return response;
    }

    public boolean isValidResponse(CozeApiResponse response) {
        if (response == null) {
            return false;
        }

        return response.getCode() != null && response.getCode() == 0;
    }
}