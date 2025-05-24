package com.loveandlasso.bot.service;

import com.loveandlasso.bot.constant.BotConstants;
import com.loveandlasso.bot.constant.MessageTemplates;
import com.loveandlasso.bot.controller.ApplicationContextProvider;
import com.loveandlasso.bot.controller.TelegramBotController;
import com.loveandlasso.bot.dto.CozeApiResponse;
import com.loveandlasso.bot.dto.PaymentRequest;
import com.loveandlasso.bot.dto.PaymentResponse;
import com.loveandlasso.bot.keyboard.InlineKeyboardFactory;
import com.loveandlasso.bot.keyboard.MainMenuKeyboard;
import com.loveandlasso.bot.model.SubscriptionType;
import com.loveandlasso.bot.model.User;
import com.loveandlasso.bot.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;

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

    // Система сборки разбитых сообщений
    private final Map<Long, MessageAssembler> messageAssemblers = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(TelegramBotController.class);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

    private static final long PART_WAIT_TIMEOUT = 2000; // 2 секунды между частями
    private static final long MAX_ASSEMBLY_TIME = 60000; // 30 секунд максимум на сборку

    private static class MessageAssembler {
        private final StringBuilder fullMessage = new StringBuilder();
        private final Long userId;
        private final Consumer<MessageAssembler> completeHandler;
        private ScheduledFuture<?> processingTask;
        private final long assemblyStartTime;
        private long lastPartTime;
        private int partsCount = 0;
        private boolean isAssembling = true;

        MessageAssembler(Long userId, Consumer<MessageAssembler> completeHandler) {
            this.userId = userId;
            this.completeHandler = completeHandler;
            this.assemblyStartTime = System.currentTimeMillis();
            this.lastPartTime = assemblyStartTime;
        }

        synchronized void addPart(String part) {
            if (!isAssembling) {
                log.warn("Ignoring part - assembly already completed for user {}", userId);
                return;
            }

            long now = System.currentTimeMillis();
            partsCount++;

            // Добавляем часть к полному сообщению
            if (fullMessage.length() > 0) {
                fullMessage.append(" "); // Пробел между частями
            }
            fullMessage.append(part.trim());
            lastPartTime = now;

            log.info("📥 Part #{} received for user {}: length={}, total_length={}",
                    partsCount, userId, part.length(), fullMessage.length());
            log.debug("Part content: [{}]", part);

            // Отменяем предыдущую задачу обработки
            if (processingTask != null) {
                processingTask.cancel(false);
            }

            // Планируем обработку полного сообщения
            long delay = calculateDelay(now);
            processingTask = scheduler.schedule(this::tryComplete, delay, TimeUnit.MILLISECONDS);

            log.info("⏰ Scheduled completion check for user {} in {}ms", userId, delay);
        }
        private long calculateDelay(long now) {
            long assemblyDuration = now - assemblyStartTime;

            // Если сборка длится слишком долго - завершаем принудительно
            if (assemblyDuration > MAX_ASSEMBLY_TIME) {
                log.warn("⚠️ Force completing due to max assembly time for user {}", userId);
                return 100;
            }

            // Если получили много частей - короткая задержка
            if (partsCount > 3) {
                return 1500;
            }

            // Обычная задержка
            return PART_WAIT_TIMEOUT;
        }

        private void tryComplete() {
            long now = System.currentTimeMillis();
            long timeSinceLastPart = now - lastPartTime;
            long sessionDuration = now - assemblyStartTime; // Вычисляем на лету

            log.info("🔍 tryComplete: user={}, sessionDuration={}ms, parts={}, length={}",
                    userId, sessionDuration, partsCount, fullMessage.length());

            // ДОБАВЛЯЕМ принудительное завершение
            boolean forceByTime = sessionDuration > 10000; // 10 секунд
            boolean forceByParts = partsCount >= 4; // 4 или больше частей
            boolean normalComplete = timeSinceLastPart >= PART_WAIT_TIMEOUT - 100;

            if (forceByTime || forceByParts || normalComplete) {
                if (forceByTime) {
                    log.warn("⚡ FORCE completing by time for user {}", userId);
                } else if (forceByParts) {
                    log.warn("⚡ FORCE completing by parts count for user {}", userId);
                } else {
                    log.info("✅ Normal completion for user {}", userId);
                }

                log.info("✅ Triggering completion for user {}: parts={}, length={}",
                        userId, partsCount, fullMessage.length());
                completeHandler.accept(this);
            } else {
                log.info("⏳ Not ready yet for user {}, need {}ms more",
                        userId, (PART_WAIT_TIMEOUT - 100) - timeSinceLastPart);
            }
        }

        synchronized String getAssembledMessage() {
            return fullMessage.toString().trim();
        }

        synchronized void markCompleted() {
            isAssembling = false;
            if (processingTask != null) {
                processingTask.cancel(false);
            }
        }

        boolean isAssembling() {
            return isAssembling;
        }

        Long getUserId() {
            return userId;
        }

        int getPartsCount() {
            return partsCount;
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

        log.info("📨 Incoming message from user {}: length={}", userId, messageText.length());
        log.debug("Message content: [{}]", messageText);

        User user = registerUserIfNeeded(update);
        user.setLastActivity(LocalDateTime.now());
        userRepository.save(user);

        // Команды меню обрабатываем немедленно
        if (isMenuButton(messageText)) {
            log.info("🔘 Menu command detected, clearing assembler for user {}", userId);
            clearMessageAssembler(userId);
            return handleMenuButton(messageText, user);
        }

        // Получаем или создаем сборщик сообщений
        MessageAssembler assembler = messageAssemblers.computeIfAbsent(userId,
                k -> {
                    log.info("🔧 Creating new message assembler for user {}", k);
                    return new MessageAssembler(k, this::handleAssembledMessage);
                });

        // Проверяем, не завершается ли уже обработка предыдущего сообщения
        if (!assembler.isAssembling()) {
            log.info("🔄 Previous assembly completed, creating new assembler for user {}", userId);
            clearMessageAssembler(userId);
            assembler = new MessageAssembler(userId, this::handleAssembledMessage);
            messageAssemblers.put(userId, assembler);
        }

        // Добавляем часть в сборщик
        assembler.addPart(messageText);

        // Возвращаем подтверждение (пользователь увидит что части получаются)
        if (assembler.getPartsCount() == 1) {
            return new BotResponse("📝 Сообщение получено, собираю части...");
        } else {
            return new BotResponse(String.format("📝 Получена часть %d, собираю...", assembler.getPartsCount()));
        }
    }

    public BotResponse processCallbackQuery(@NotNull CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        User user = getUserFromCallback(callbackQuery);

        // ЕДИНСТВЕННОЕ ИЗМЕНЕНИЕ: заменяем clearUserBuffer на clearMessageAssembler
        clearMessageAssembler(callbackQuery.getFrom().getId());

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
                    TARIFF_INFO + getPlanDetailsMessage(prevPlan),
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
                    TARIFF_INFO + getPlanDetailsMessage(nextPlan),
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

    private void handleAssembledMessage(MessageAssembler assembler) {
        Long userId = assembler.getUserId();

        log.info("🎯 handleAssembledMessage START for user {}", userId);

        try {
            assembler.markCompleted();
            String assembledMessage = assembler.getAssembledMessage();

            log.info("🎯 === PROCESSING ASSEMBLED MESSAGE ===");
            log.info("User: {}", userId);
            log.info("Parts assembled: {}", assembler.getPartsCount());
            log.info("Final length: {}", assembledMessage.length());
            log.info("Assembled content: [{}]", assembledMessage);

            // Получаем пользователя
            User user = userRepository.findByTelegramId(userId)
                    .orElseThrow(() -> new RuntimeException("User not found: " + userId));

            log.info("🚀 === SENDING SINGLE REQUEST TO COZE API ===");

            // ОТПРАВЛЯЕМ В COZE API ОДНИМ ЗАПРОСОМ
            String apiResponse = processRegularMessage(assembledMessage, user);

            log.info("✅ === RECEIVED RESPONSE FROM COZE API ===");
            log.info("Response length: {}", apiResponse.length());

            // Отправляем ответ пользователю
            sendResponseToUser(userId, apiResponse);

            log.info("📤 Response sent to user {}", userId);

        } catch (Exception e) {
            log.error("❌ Error processing assembled message for user {}", userId, e);
            sendResponseToUser(userId, "⚠️ Извините, произошла ошибка при обработке вашего сообщения.");
        } finally {
            log.info("🧹 Clearing assembler for user {}", userId);
            // Очищаем сборщик
            clearMessageAssembler(userId);
        }
    }

    private void sendResponseToUser(Long userId, String response) {
        // Здесь пока простая отправка - можно потом улучшить
        try {
            // Нужна ссылка на TelegramBotController или можно через события
            log.info("📤 Sending response to user {}: length={}", userId, response.length());

            // Временное решение - можно улучшить
            ApplicationContext context = ApplicationContextProvider.getApplicationContext();
            TelegramBotController botController = context.getBean(TelegramBotController.class);
            botController.sendSimpleMessage(userId, response);

        } catch (Exception e) {
            log.error("❌ Failed to send response to user {}", userId, e);
        }
    }

    private void clearMessageAssembler(Long userId) {
        MessageAssembler assembler = messageAssemblers.remove(userId);
        if (assembler != null) {
            log.info("🗑️ Cleared message assembler for user {}", userId);
            assembler.markCompleted();
        }
    }

    @Scheduled(fixedRate = 30000) // Каждые 30 секунд
    public void cleanupExpiredAssemblers() {
        long now = System.currentTimeMillis();
        int cleaned = 0;

        Iterator<Map.Entry<Long, MessageAssembler>> iterator = messageAssemblers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, MessageAssembler> entry = iterator.next();
            MessageAssembler assembler = entry.getValue();

            // Проверяем, не истекло ли время сборки
            if (now - assembler.assemblyStartTime > MAX_ASSEMBLY_TIME * 2) {
                log.info("🧹 Cleaning up expired assembler for user {}", entry.getKey());
                assembler.markCompleted(); // Останавливаем задачи
                iterator.remove();
                cleaned++;
            }
        }

        if (cleaned > 0) {
            log.info("🧹 Cleaned up {} expired message assemblers", cleaned);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("🔌 Shutting down message assembly scheduler");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }





    public String processRegularMessage(String message, User user) {
        log.info("🔥 processRegularMessage START: message_length={}", message.length());

        if (subscriptionService.isDailyLimitExceeded(user)) {
            log.info("⚠️ Daily limit exceeded for user {}", user.getTelegramId());
            return MessageTemplates.LIMIT_REACHED;
        }

        user.setAwaitingResponse(true);
        userRepository.save(user);

        log.info("🚀 Calling cozeApiService.sendRequest");
        CozeApiResponse cozeResponse = cozeApiService.sendRequest(message, user);
        log.info("✅ Received response from cozeApiService");

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