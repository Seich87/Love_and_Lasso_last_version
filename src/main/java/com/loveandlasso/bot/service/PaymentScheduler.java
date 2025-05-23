package com.loveandlasso.bot.service;

import com.loveandlasso.bot.constant.BotConstants;
import com.loveandlasso.bot.controller.TelegramBotController;
import com.loveandlasso.bot.keyboard.InlineKeyboardFactory;
import com.loveandlasso.bot.model.User;
import com.loveandlasso.bot.repository.UserRepository;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static com.loveandlasso.bot.constant.MessageTemplates.*;

@Component
@EnableScheduling
public class PaymentScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentScheduler.class);

    private final PaymentService paymentService;
    private final UserRepository userRepository;
    private final TelegramBotController telegramBotController;

    @Autowired
    public PaymentScheduler(
            PaymentService paymentService,
            UserRepository userRepository,
            TelegramBotController telegramBotController) {
        this.paymentService = paymentService;
        this.userRepository = userRepository;
        this.telegramBotController = telegramBotController;
    }

    @Scheduled(fixedDelay = 300000)
    public void checkPendingPayments() {
        log.info("Запуск периодической проверки платежей");
        try {
            List<User> allUsers = userRepository.findAll();
            List<User> usersWithPendingPayments = allUsers.stream()
                    .filter(user -> user.getCurrentPaymentId() != null && !user.getCurrentPaymentId().isEmpty())
                    .toList();
            for (User user : usersWithPendingPayments) {
                String paymentId = user.getCurrentPaymentId();

                try {
                    String status = paymentService.checkPaymentStatus(paymentId);

                    if (BotConstants.PAYMENT_SUCCEEDED.equals(status)) {
                        boolean processed = paymentService.processSuccessfulPayment(paymentId);

                        if (processed) {

                            InlineKeyboardMarkup startDialogKeyboard = InlineKeyboardFactory.createStartDialogKeyboard();
                            telegramBotController.sendNotificationWithKeyboard(user.getTelegramId(), PAYMENT_SUCCESS, startDialogKeyboard);

                            user.setCurrentPaymentId(null);
                            userRepository.save(user);
                        }
                    } else if (BotConstants.PAYMENT_CANCELED.equals(status) || "expired".equals(status)) {

                        user.setCurrentPaymentId(null);
                        userRepository.save(user);
                    }
                } catch (Exception e) {
                    log.error("Ошибка при проверке платежа {}: {}", paymentId, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Ошибка при запуске периодической проверки платежей: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 0 12 * * ?")
    public void checkSubscriptionsExpiration() {

        try {
            List<User> usersWithActiveSubscription = userRepository.findAll().stream()
                    .filter(user -> user.getSubscriptionEndDate() != null)
                    .filter(user -> user.getSubscriptionEndDate().isAfter(LocalDateTime.now()))
                    .toList();

            LocalDateTime now = LocalDateTime.now();

            for (User user : usersWithActiveSubscription) {
                try {
                    LocalDateTime endDate = user.getSubscriptionEndDate();
                    long daysUntilExpiration = ChronoUnit.DAYS.between(now.toLocalDate(), endDate.toLocalDate());

                    String message = null;

                    if (daysUntilExpiration == 7) {
                        message = createExpirationMessage(user, 7);
                    } else if (daysUntilExpiration == 3) {
                        message = createExpirationMessage(user, 3);
                    } else if (daysUntilExpiration == 1) {
                        message = createExpirationMessage(user, 1);
                    }

                    if (message != null) {
                        InlineKeyboardMarkup renewKeyboard = createRenewSubscriptionKeyboard();
                        telegramBotController.sendNotificationWithKeyboard(user.getTelegramId(), message, renewKeyboard);

                    }
                } catch (Exception e) {
                    log.error("Ошибка при обработке подписки пользователя {}: {}",
                            user.getTelegramId(), e.getMessage(), e);
                }
            }

            List<User> usersWithExpiredSubscription = userRepository.findAll().stream()
                    .filter(user -> user.getSubscriptionEndDate() != null)
                    .filter(user -> user.getSubscriptionEndDate().isBefore(now) &&
                            user.getSubscriptionEndDate().isAfter(now.minusDays(1)))
                    .toList();

            for (User user : usersWithExpiredSubscription) {

                InlineKeyboardMarkup renewKeyboard = createRenewSubscriptionKeyboard();
                telegramBotController.sendNotificationWithKeyboard(user.getTelegramId(), PAYMENT_END, renewKeyboard);

            }

        } catch (Exception e) {
            log.error("Ошибка при проверке сроков подписок: {}", e.getMessage(), e);
        }
    }

    @NotNull
    private String createExpirationMessage(@NotNull User user, int daysLeft) {
        String planName = getPlanDisplayName(user.getSelectedPlan());
        String daysWord = getDaysWordForm(daysLeft);

        return String.format(PAYMENT_REMINDER, daysLeft, daysWord, planName);
    }

    @NotNull
    private InlineKeyboardMarkup createRenewSubscriptionKeyboard() {
        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        List<InlineKeyboardButton> row = new ArrayList<>();
        InlineKeyboardButton renewButton = new InlineKeyboardButton();
        renewButton.setText("💼 Продлить подписку");
        renewButton.setCallbackData("renew_subscription");
        row.add(renewButton);

        rowsInline.add(row);
        markupInline.setKeyboard(rowsInline);

        return markupInline;
    }

    @Contract(pure = true)
    private String getPlanDisplayName(@NotNull String planCode) {
        return switch (planCode) {
            case "romantic" -> "Романтик";
            case "alfa" -> "Альфач";
            case "lovelass" -> "Ловелас";
            case "test" -> "Тестовый";
            default -> planCode;
        };
    }

    @NotNull
    @Contract(pure = true)
    private String getDaysWordForm(int days) {
        if (days % 10 == 1 && days % 100 != 11) {
            return "день";
        } else if ((days % 10 == 2 || days % 10 == 3 || days % 10 == 4) &&
                (days % 100 < 10 || days % 100 >= 20)) {
            return "дня";
        } else {
            return "дней";
        }
    }
}

