package com.loveandlasso.bot.controller;

import com.loveandlasso.bot.keyboard.InlineKeyboardFactory;
import com.loveandlasso.bot.model.User;
import com.loveandlasso.bot.repository.UserRepository;
import com.loveandlasso.bot.service.PaymentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Map;
import java.util.Optional;

import static com.loveandlasso.bot.constant.MessageTemplates.PAYMENT_SUCCESS;

@RestController
@RequestMapping("/payment")
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;
    private final TelegramBotController telegramBotController;
    private final UserRepository userRepository;

    @Autowired
    public PaymentController(PaymentService paymentService,
                             TelegramBotController telegramBotController,
                             UserRepository userRepository) {
        this.paymentService = paymentService;
        this.telegramBotController = telegramBotController;
        this.userRepository = userRepository;
    }

    @PostMapping("/webhook")

    public ResponseEntity<String> handlePaymentWebhook(@RequestBody Map<String, Object> payload) {
        try {

            String event = (String) payload.get("event");

            if ("payment.succeeded".equals(event)) {

                Optional.ofNullable(payload.get("object"))

                        .filter(obj -> obj instanceof Map)
                        .map(obj -> (Map<?, ?>) obj)
                        .map(map -> map.get("id"))
                        .filter(id -> id instanceof String)
                        .map(String.class::cast)
                        .ifPresent(paymentId -> {
                            boolean processed = paymentService.processSuccessfulPayment(paymentId);

                            if (processed) {
                                sendPaymentSuccessNotification(paymentId);
                            }
                        });
            }

            return ResponseEntity.ok("OK");
        } catch (Exception e) {

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("ERROR: " + e.getMessage());
        }
    }

    private void sendPaymentSuccessNotification(String paymentId) {

        Optional<User> userOptional = userRepository.findByCurrentPaymentId(paymentId);

        if (userOptional.isPresent()) {
            User user = userOptional.get();

            SendMessage message = new SendMessage();
            message.setChatId(user.getTelegramId().toString());
            message.setText(PAYMENT_SUCCESS);
            message.setParseMode("HTML");
            message.setReplyMarkup(InlineKeyboardFactory.createStartDialogKeyboard());

            try {
                telegramBotController.execute(message);
            } catch (TelegramApiException e) {
                log.error("Ошибка при отправке уведомления об успешной оплате: {}", e.getMessage(), e);
            }

            user.setCurrentPaymentId(null);
            userRepository.save(user);
        } else {
            log.warn("Не найден пользователь для платежа {}", paymentId);
        }
    }
}
