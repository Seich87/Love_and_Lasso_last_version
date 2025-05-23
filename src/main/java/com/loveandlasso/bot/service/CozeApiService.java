package com.loveandlasso.bot.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loveandlasso.bot.dto.CozeApiResponse;
import com.loveandlasso.bot.model.User;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${coze.api.url}")
    private String apiUrl;

    @Value("${coze.api.key}")
    private String apiKey;

    @Value("${coze.bot.id}")
    private String botId;

    @Value("${coze.api.timeout:30000}")
    private int timeout;

    @Autowired
    public CozeApiService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public CozeApiResponse sendRequest(String query, @NotNull User user) {
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
        message.put("content", query);
        message.put("content_type", "text");

        List<Map<String, Object>> additionalMessages = new ArrayList<>();
        additionalMessages.add(message);
        requestMap.put("additional_messages", additionalMessages);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestMap, headers);

        try {
            ResponseEntity<JsonNode> responseEntity = restTemplate.exchange(
                    apiUrl + "/v3/chat",
                    HttpMethod.POST,
                    entity,
                    JsonNode.class
            );

            JsonNode jsonResponse = responseEntity.getBody();
            System.out.println("JSON response: " + jsonResponse);

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
            return createErrorResponse("Ошибка HTTP: " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            return createErrorResponse("Ошибка соединения");
        } catch (Exception e) {
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

                ResponseEntity<Map> responseEntity = restTemplate.exchange(
                        statusUrl,
                        HttpMethod.GET,
                        entity,
                        Map.class
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
                    // Если статус все еще "in_progress", продолжаем ожидание
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error polling for completion: " + e.getMessage());
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

            ResponseEntity<Map> responseEntity = restTemplate.exchange(
                    messagesUrl,
                    HttpMethod.GET,
                    entity,
                    Map.class
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
    private String extractBotResponseFromMessages(Map<String, Object> messagesResponse) {
        try {
            if (messagesResponse != null && messagesResponse.containsKey("data")) {
                List<Map<String, Object>> messages = (List<Map<String, Object>>) messagesResponse.get("data");

                for (Map<String, Object> message : messages) {
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
        } catch (Exception e) {
            System.err.println("Error extracting bot response: " + e.getMessage());
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

        boolean isValid = response.getCode() != null && response.getCode() == 0;

        if (!isValid) {
            System.err.println("Invalid response - code: " + response.getCode() + ", msg: " + response.getMsg());
        }

        return isValid;
    }
}
