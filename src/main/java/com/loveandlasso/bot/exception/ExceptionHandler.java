package com.loveandlasso.bot.exception;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class ExceptionHandler {

    @org.springframework.web.bind.annotation.ExceptionHandler(TelegramApiException.class)
    public ResponseEntity<Object> handleTelegramApiException(@NotNull TelegramApiException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Ошибка Telegram API: " + ex.getMessage());
        body.put("timestamp", System.currentTimeMillis());

        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<Object> handleHttpClientErrorException(@NotNull HttpClientErrorException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Ошибка HTTP-клиента: " + ex.getMessage());
        body.put("statusCode", ex.getStatusCode().value());
        body.put("timestamp", System.currentTimeMillis());

        return new ResponseEntity<>(body, ex.getStatusCode());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RestClientException.class)
    public ResponseEntity<Object> handleRestClientException(@NotNull RestClientException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Ошибка REST-клиента: " + ex.getMessage());
        body.put("timestamp", System.currentTimeMillis());

        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Object> handleResourceAccessException(@NotNull ResourceAccessException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Ошибка доступа к ресурсу: " + ex.getMessage());
        body.put("timestamp", System.currentTimeMillis());

        return new ResponseEntity<>(body, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGlobalException(@NotNull Exception ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Произошла ошибка: " + ex.getMessage());
        body.put("timestamp", System.currentTimeMillis());

        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

}
