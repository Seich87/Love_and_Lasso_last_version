package com.loveandlasso.bot.service;

import com.loveandlasso.bot.constant.BotConstants;
import com.loveandlasso.bot.constant.MessageTemplates;
import com.loveandlasso.bot.dto.CozeApiResponse;
import com.loveandlasso.bot.dto.PaymentRequest;
import com.loveandlasso.bot.dto.PaymentResponse;
import com.loveandlasso.bot.keyboard.InlineKeyboardFactory;
import com.loveandlasso.bot.keyboard.MainMenuKeyboard;
import com.loveandlasso.bot.model.SubscriptionType;
import com.loveandlasso.bot.model.User;
import com.loveandlasso.bot.repository.UserRepository;
import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

import static com.loveandlasso.bot.constant.BotConstants.*;
import static com.loveandlasso.bot.constant.MessageTemplates.*;

@Service
public class BotService {

    private final UserRepository userRepository;
    private final CozeApiService cozeApiService;
    private final SubscriptionService subscriptionService;
    private final PaymentService paymentService;

    @Value("${payment.yukassa.returnUrl}")
    private String returnUrl;

    private final Map<Long, MessageBuffer> userMessageBuffers = new ConcurrentHashMap<>();

    private static final long MESSAGE_PART_TIMEOUT = 2000; // 2 секунды

    private static class MessageBuffer {
        private final StringBuilder content = new StringBuilder();
        private long lastUpdateTime = System.currentTimeMillis();
        private boolean isProcessing = false;

        public void addPart(String part) {
            if (content.length() > 0) {
                content.append(" ");
            }
            content.append(part);
            lastUpdateTime = System.currentTimeMillis();
        }

        public String getContent() {
            return content.toString();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - lastUpdateTime > MESSAGE_PART_TIMEOUT;
        }

        public void clear() {
            content.setLength(0);
            lastUpdateTime = System.currentTimeMillis();
        }

        public boolean isProcessing() {
            return isProcessing;
        }

        public void setProcessing(boolean processing) {
            this.isProcessing = processing;
        }

    }

    @Autowired
    public BotService(UserRepository userRepository,
                      CozeApiService cozeApiService,
                      SubscriptionService subscriptionService,
                      PaymentService paymentService) {
        this.userRepository = userRepository;
        this.cozeApiService = cozeApiService;
        this.subscriptionService = subscriptionService;
        this.paymentService = paymentService;
    }

    public BotResponse processMessage(@NotNull Update update) {

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return new BotResponse(MessageTemplates.ERROR_MESSAGE);
        }

        String messageText = update.getMessage().getText();
        Long userId = update.getMessage().getFrom().getId();

        User user = registerUserIfNeeded(update);
        user.setLastActivity(LocalDateTime.now());
        userRepository.save(user);

        if (isMenuButton(messageText)) {
            clearUserBuffer(userId);
            return handleMenuButton(messageText, user);
        }

        MessageBuffer buffer = userMessageBuffers.computeIfAbsent(userId, k -> new MessageBuffer());

        if (buffer.isExpired()) {
            buffer.clear();
        }
        buffer.addPart(messageText);

        if (buffer.isProcessing()) {
            return new BotResponse("⏳ Обрабатываю ваше сообщение...");
        }

        try {
            Thread.sleep(MESSAGE_PART_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String completeMessage = buffer.getContent();
        buffer.setProcessing(true);

        try {
            if (BotConstants.DIALOG_SUBSCRIPTION.equals(user.getDialogState()) && !isMenuButton(completeMessage)) {
                try {
                    String apiResponse = processRegularMessage(completeMessage, user);

                    user.setDialogState(BotConstants.DIALOG_MAIN);
                    user.setSelectedPlan(null);
                    user.setLastActivity(LocalDateTime.now());
                    userRepository.save(user);

                    return new BotResponse(apiResponse);

                } catch (Exception e) {

                    user.setDialogState(BotConstants.DIALOG_MAIN);
                    user.setSelectedPlan(null);
                    user.setLastActivity(LocalDateTime.now());
                    userRepository.save(user);
                    return new BotResponse("⚠️ Извините, произошла ошибка при обработке вашего сообщения. Попробуйте позже.");
                }
            }

            String apiResponse = null;
            String additionalHint = "";

            String currentState = user.getDialogState();
            if (currentState != null) {
                switch (currentState) {
                    case BotConstants.DIALOG_SUBSCRIPTION -> {
                        additionalHint = "\n\n📝 <b>Подсказка:</b> Выберите подписку из меню выше или продолжайте общение с ботом.";

                        // Определяем, какую клавиатуру показать в зависимости от выбранного плана
                        InlineKeyboardMarkup selectedKeyboard;
                        if ("test".equals(user.getSelectedPlan())) {
                            selectedKeyboard = InlineKeyboardFactory.createNavigationWithPaymentForFreeKeyboard();
                        } else {
                            selectedKeyboard = InlineKeyboardFactory.createNavigationWithPaymentKeyboard();
                        }

                        return new BotResponse(
                                "\uD83D\uDC49 <b>Выберите тариф, для этого используйте свайпы! ⬅️ ➡️</b>\n\n" +
                                        getPlanDetailsMessage(user.getSelectedPlan() != null ? user.getSelectedPlan() : "test"),
                                selectedKeyboard
                        );
                    }
                    case BotConstants.DIALOG_PLAN_DETAILS -> {
                        if ("test".equals(user.getSelectedPlan())) {
                            additionalHint = "\n\n📝 <b>Подсказка:</b> Выберите 'Активировать' для подключения тестового тарифа, если Вы им еще не воспользовались";
                        } else {
                            additionalHint = "\n\n📝 <b>Подсказка:</b> Для оформления подписки нажмите '💰 Оплатить'.";
                        }
                    }
                    case BotConstants.DIALOG_PAYMENT ->
                            additionalHint = "\n\n💳 **Информация:** Если у вас есть вопросы по оплате, обращайтесь в поддержку.";
                    case BotConstants.DIALOG_AWAITING_MESSAGE -> {
                        user.setDialogState(BotConstants.DIALOG_MAIN);
                        userRepository.save(user);
                    }
                    default -> {
                    }
                }
            }

            try {
                apiResponse = processRegularMessage(completeMessage, user);
            } catch (Exception e) {
                return new BotResponse("⚠️ Извините, произошла ошибка при обработке вашего сообщения. Попробуйте позже.");
            }

            String finalResponse = "";
            if (apiResponse != null && !apiResponse.trim().isEmpty()) {
                finalResponse = apiResponse;
            } else {
                finalResponse = "🤖 Получен ваш запрос, но ответ пока не готов.";
            }

            if (!additionalHint.isEmpty()) {
                finalResponse += additionalHint;
            }

            return new BotResponse(finalResponse);

        } finally {
            clearUserBuffer(userId);
        }
    }

    public BotResponse processCallbackQuery(@NotNull CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        User user = getUserFromCallback(callbackQuery);

        clearUserBuffer(callbackQuery.getFrom().getId());
        user.setLastActivity(LocalDateTime.now());
        userRepository.save(user);

        if (InlineKeyboardFactory.CALLBACK_TEST_MODE.equals(callbackData)) {
            return new BotResponse(activateTestPlan(user));
        }
        if (InlineKeyboardFactory.CALLBACK_HELP_MODE.equals(callbackData)) {
            return new BotResponse(activateTestPlan(user));
        }
        if (InlineKeyboardFactory.CALLBACK_CONTACT.equals(callbackData)) {
            return new BotResponse("Для связи обратитесь к @Estreman");
        }
        if (InlineKeyboardFactory.CALLBACK_START_DIALOG.equals(callbackData)) {
            user.setAwaitingResponse(true);
            userRepository.save(user);

            try {
                String presetMessage = "Привет! Ты кто и что делаешь?";
                String apiResponse = processRegularMessage(presetMessage, user);

                user.setAwaitingResponse(false);
                user.setDialogState(BotConstants.DIALOG_MAIN);
                userRepository.save(user);

                return new BotResponse(apiResponse);

            } catch (Exception e) {
                user.setAwaitingResponse(false);
                userRepository.save(user);
                return new BotResponse("⚠️ Извините, произошла ошибка при обработке вашего запроса. Попробуйте позже.");
            }
        }

        if (InlineKeyboardFactory.CALLBACK_PREV.equals(callbackData)) {
            String currentPlan = user.getSelectedPlan();
            String prevPlan = getPreviousPlan(currentPlan);
            user.setSelectedPlan(prevPlan);
            userRepository.save(user);

            InlineKeyboardMarkup selectedKeyboard;
            if ("test".equals(user.getSelectedPlan())) {
                selectedKeyboard = InlineKeyboardFactory.createNavigationWithPaymentForFreeKeyboard();
            } else {
                selectedKeyboard = InlineKeyboardFactory.createNavigationWithPaymentKeyboard();
            }

            return new BotResponse(
                    TARIFF_INFO +
                            getPlanDetailsMessage(prevPlan),
                    selectedKeyboard
            );
        }

        // Обработка навигации "Вперед"
        if (InlineKeyboardFactory.CALLBACK_NEXT.equals(callbackData)) {
            String currentPlan = user.getSelectedPlan();
            String nextPlan = getNextPlan(currentPlan);
            user.setSelectedPlan(nextPlan);
            userRepository.save(user);

            InlineKeyboardMarkup selectedKeyboard;
            if ("test".equals(user.getSelectedPlan())) {
                selectedKeyboard = InlineKeyboardFactory.createNavigationWithPaymentForFreeKeyboard();
            } else {
                selectedKeyboard = InlineKeyboardFactory.createNavigationWithPaymentKeyboard();
            }

            return new BotResponse(
                    TARIFF_INFO +
                            getPlanDetailsMessage(nextPlan),
                    selectedKeyboard
            );
        }

        if (InlineKeyboardFactory.CALLBACK_PAY.equals(callbackData)) {
            String selectedPlan = user.getSelectedPlan();
            if (selectedPlan != null) {
                user.setDialogState(BotConstants.DIALOG_PAYMENT);
                userRepository.save(user);
                return new BotResponse(getPaymentMessage(selectedPlan, user));
            } else {
                return new BotResponse("Сначала выберите тариф, используя кнопки навигации.");
            }
        }

        return new BotResponse("Неизвестное действие");
    }

    public String processRegularMessage(String message, User user) {
        if (subscriptionService.isDailyLimitExceeded(user)) {
            return MessageTemplates.LIMIT_REACHED;
        }

        user.setAwaitingResponse(true);
        userRepository.save(user);

        CozeApiResponse cozeResponse = cozeApiService.sendRequest(message, user);

        boolean successful = cozeApiService.isValidResponse(cozeResponse);
        Integer tokenCount = successful && cozeResponse.getUsage() != null
                ? cozeResponse.getUsage().getTotalTokens()
                : null;

        String messageForLogging = message.length() > 1000 ?
                message.substring(0, 997) + "..." : message;

        subscriptionService.logUsage(user, messageForLogging, null, successful, tokenCount);

        user.setAwaitingResponse(false);
        userRepository.save(user);

        if (!successful) {
            return MessageTemplates.API_ERROR;
        }

        return cozeApiService.extractResponseText(cozeResponse);
    }

    private void clearUserBuffer(Long userId) {
        userMessageBuffers.remove(userId);
    }

    @Scheduled(fixedRate = 30000) // Каждые 30 секунд
    public void cleanupExpiredBuffers() {
        userMessageBuffers.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private boolean isMenuButton(@NotNull String messageText) {
        return  messageText.equals("ℹ️ Инструкция") ||
                messageText.equals("💼 Подписные тарифы") ||
                messageText.contains("\uD83D\uDD27 Тех.поддержка") ||
                messageText.equals("👤 Мой профиль") ||
                messageText.equals("/start");
    }

    private BotResponse handleMenuButton(String messageText, User user) {
        switch (messageText) {
            case "/start" -> {
                user.setDialogState(BotConstants.DIALOG_MAIN);
                user.setSelectedPlan(null);
                userRepository.save(user);
                return new BotResponse(
                        String.format(MessageTemplates.WELCOME_MESSAGE, user.getFirstName()),
                        MainMenuKeyboard.create(),
                        InlineKeyboardFactory.createTestModeKeyboard()
                );
            }

            case "ℹ️ Инструкция" -> {
                return new BotResponse(MessageTemplates.INSTRUCTION_INFO);
            }
            case "💼 Подписные тарифы" -> {
                user.setDialogState(BotConstants.DIALOG_SUBSCRIPTION);
                user.setSelectedPlan("test");
                userRepository.save(user);

                return new BotResponse(
                        TARIFF_INFO + MessageTemplates.FREE_INFO,
                        InlineKeyboardFactory.createNavigationWithPaymentForFreeKeyboard(),
                        true
                );
            }
            case "\uD83D\uDD27 Тех.поддержка", "?" -> {
                return new BotResponse(
                        String.format(HELP_MESSAGE, user.getFirstName()),
                        InlineKeyboardFactory.createHelpKeyboard());
            }
            case "📞 Связаться" -> {
                return new BotResponse("Для связи обратитесь к @Estreman");
            }
            case "👤 Мой профиль" -> {
                return new BotResponse(getProfileMessage(user));
            }
            case "Активировать" -> {
                return new BotResponse(activateTestPlan(user));
            }

            default -> throw new IllegalStateException("Unexpected value: " + user.getDialogState());
        }
    }

    private String getPlanDetailsMessage(@NotNull String planType) {
        return switch (planType) {
            case "romantic" -> MessageTemplates.ROMANTIC_INFO;
            case "alpha" -> MessageTemplates.ALFA_INFO;
            case "lovelace" -> MessageTemplates.LOVELACE_INFO;
            case "test" -> MessageTemplates.FREE_INFO;
            default -> MessageTemplates.ERROR_MESSAGE;
        };
    }

    @NotNull
    @Contract(pure = true)
    private String getNextPlan(@NotNull String currentPlan) {
        return switch (currentPlan) {
            case "test" -> "romantic";
            case "romantic" -> "alpha";
            case "alpha" -> "lovelace";
            default -> "test";
        };
    }

    @NotNull
    @Contract(pure = true)
    private String getPreviousPlan(@NotNull String currentPlan) {
        return switch (currentPlan) {
            case "test" -> "lovelace";
            case "lovelace" -> "alpha";
            case "alpha" -> "romantic";
            default -> "test";
        };
    }

    private String activateTestPlan(User user) {
        try {
            if (user == null) {
                return "❌ Ошибка: пользователь не найден.";
            }

            if (subscriptionService == null) {
                throw new RuntimeException("SubscriptionService is null");
            }

            if (subscriptionService.hasUserUsedTestPlan(user)) {
                return TEST_ACTIVATE_EARLIER;
            }

            subscriptionService.createTestSubscription(user);

            user.setDialogState(BotConstants.DIALOG_MAIN);
            user.setSelectedPlan(null);

            if (userRepository == null) {
                throw new RuntimeException("UserRepository is null");
            }

            userRepository.save(user);

            return MessageTemplates.TEST_ACTIVATE;

        } catch (Exception e) {
            return TEST_ACTIVATE_ERROR;
        }
    }

    private String getPaymentMessage(String planType, User user) {
        if (planType == null) {
            return "Ошибка: тариф не выбран. Вернитесь к выбору тарифа.";
        }

        if ("test".equals(planType)) {
            return activateTestPlan(user);
        }

        SubscriptionType subscriptionType;
        double amount;
        String planDisplayName;
        String requests;

        switch (planType) {
            case "romantic" -> {
                subscriptionType = SubscriptionType.ROMANTIC;
                amount = ROMANTIC_PRICE_MONTHLY;
                planDisplayName = "Романтик 💎";
                requests = String.valueOf(ROMANTIC_REQUESTS_PER_DAY);
            }
            case "alpha" -> {
                subscriptionType = SubscriptionType.ALPHA;
                amount = ALPHA_PRICE_MONTHLY;
                planDisplayName = "Альфач 🔷";
                requests = String.valueOf(ALPHA_REQUESTS_PER_DAY);
            }
            case "lovelace" -> {
                subscriptionType = SubscriptionType.LOVELACE;
                amount = LOVELACE_PRICE_MONTHLY;
                planDisplayName = "Ловелас 👑";
                requests = "Безлимит";
            }
            default -> {
                return MessageTemplates.ERROR_MESSAGE;
            }
        }

        PaymentRequest paymentRequest = PaymentRequest.builder()
                .userId(user.getTelegramId())
                .amount(amount)
                .currency("RUB")
                .description("Подписка " + planDisplayName + " - 1 месяц")
                .subscriptionType(subscriptionType)
                .returnUrl(returnUrl)
                .build();

        PaymentResponse paymentResponse = paymentService.createPayment(paymentRequest);

        if (paymentResponse == null) {
            return PAY_FAILED;
        }

        user.setCurrentPaymentId(paymentResponse.getId());
        userRepository.save(user);

        user.setDialogState(BotConstants.DIALOG_MAIN);
        user.setSelectedPlan(null);
        userRepository.save(user);

        return "💳 <b>Оформление подписки " + planDisplayName.toUpperCase() + "</b>\n\n" +
                "📦 <b>Тариф:</b> " + planDisplayName + "\n" +
                "⏰ <b>Период:</b> 1 месяц\n" +
                "💰 <b>Сумма к оплате:</b> " + String.format("%.0f", amount) + "₽\n" +
                "⚡ <b>Запросов в день:</b> " + requests + "\n\n" +
                "🔗 <b>Для оплаты перейдите по ссылке:</b>\n" +
                paymentResponse.getPaymentUrl() + "\n\n" +
                "После успешной оплаты ваша подписка будет активирована автоматически.\n\n" +
                "💡 Ссылка действительна в течение 15 минут.";
    }

    @Transactional
    public User registerUserIfNeeded(@NotNull Update update) {
        org.telegram.telegrambots.meta.api.objects.User telegramUser = update.getMessage().getFrom();
        Long telegramId = telegramUser.getId();

        Optional<User> userOptional = userRepository.findByTelegramId(telegramId);

        if (userOptional.isPresent()) {
            return userOptional.get();
        }

        User newUser = User.builder()
                .telegramId(telegramId)
                .firstName(telegramUser.getFirstName())
                .lastName(telegramUser.getLastName())
                .username(telegramUser.getUserName())
                .registeredAt(LocalDateTime.now())
                .lastActivity(LocalDateTime.now())
                .active(true)
                .awaitingResponse(false)
                .dialogState(BotConstants.DIALOG_MAIN)
                .selectedPlan(null)
                .build();

        return userRepository.save(newUser);
    }

    private User getUserFromCallback(@NotNull CallbackQuery callbackQuery) {
        Long userId = callbackQuery.getFrom().getId();
        return userRepository.findByTelegramId(userId)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));
    }

    @NotNull
    private String getProfileMessage(User user) {
        var activeSubscription = subscriptionService.getActiveSubscription(user);
        if (activeSubscription.isPresent()) {
            var subscription = activeSubscription.get();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

            if (subscription.getType() == SubscriptionType.LOVELACE) {
                return String.format(
                        MessageTemplates.PROFILE_MESSAGE_UNLIMITED,
                        user.getFirstName(),
                        user.getRegisteredAt().format(formatter),
                        subscription.getType().getDisplayName(),
                        subscription.getEndDate().format(formatter)
                );
            } else {
                return String.format(
                        MessageTemplates.PROFILE_MESSAGE,
                        user.getFirstName(),
                        user.getRegisteredAt().format(formatter),
                        subscription.getType().getDisplayName(),
                        subscriptionService.getRemainingRequests(user),
                        subscription.getRequestsPerDay(),
                        subscription.getEndDate().format(formatter)
                );
            }

        } else {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
            return String.format(
                    MessageTemplates.PROFILE_NO_SUBSCRIPTION,
                    user.getFirstName(),
                    user.getRegisteredAt().format(formatter),
                    subscriptionService.getRemainingRequests(user),
                    BotConstants.FREE_REQUESTS_PER_DAY
            );
        }
    }

    public static class BotResponse {
        @Getter
        private final String text;
        @Getter
        private final InlineKeyboardMarkup inlineKeyboard;
        @Getter
        private final ReplyKeyboardMarkup replyKeyboard;
        private final boolean removeReplyKeyboard;

        public BotResponse(String text) {
            this(text, null, null, false);
        }

        public BotResponse(String text, InlineKeyboardMarkup inlineKeyboard) {
            this(text, inlineKeyboard, null, false);
        }

        public BotResponse(String text, InlineKeyboardMarkup inlineKeyboard, boolean removeReplyKeyboard) {
            this(text, inlineKeyboard, null, removeReplyKeyboard);
        }

        public BotResponse(String text, ReplyKeyboardMarkup replyKeyboard, InlineKeyboardMarkup inlineKeyboard) {
            this(text, inlineKeyboard, replyKeyboard, false);
        }

        public BotResponse(String text, InlineKeyboardMarkup inlineKeyboard, ReplyKeyboardMarkup replyKeyboard, boolean removeReplyKeyboard) {
            this.text = text;
            this.inlineKeyboard = inlineKeyboard;
            this.replyKeyboard = replyKeyboard;
            this.removeReplyKeyboard = removeReplyKeyboard;
        }

        public boolean shouldRemoveReplyKeyboard() {
            return removeReplyKeyboard;
        }

        public boolean hasInlineKeyboard() {
            return inlineKeyboard != null;
        }

        public boolean hasReplyKeyboard() {
            return replyKeyboard != null;
        }
    }
}