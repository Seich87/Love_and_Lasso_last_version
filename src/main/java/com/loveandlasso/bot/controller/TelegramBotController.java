package com.loveandlasso.bot.controller;

import com.loveandlasso.bot.config.TelegramBotConfig;
import com.loveandlasso.bot.constant.BotConstants;
import com.loveandlasso.bot.keyboard.InlineKeyboardFactory;
import com.loveandlasso.bot.keyboard.MainMenuKeyboard;
import com.loveandlasso.bot.model.User;
import com.loveandlasso.bot.repository.UserRepository;
import com.loveandlasso.bot.service.BotService;
import com.loveandlasso.bot.util.MessageUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.loveandlasso.bot.constant.MessageTemplates.ERROR_MESSAGE;
import static com.loveandlasso.bot.constant.MessageTemplates.ERROR_SEND_MESSAGE;

@Component
public class TelegramBotController extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotController.class);
    private static final String EMPTY_MESSAGE_TEXT = ".";

    private final BotService botService;
    private final TelegramBotConfig botConfig;
    private final UserRepository userRepository;
    private final ExecutorService executorService;

    @Autowired
    public TelegramBotController(BotService botService,
                                 TelegramBotConfig botConfig,
                                 UserRepository userRepository) {
        super(botConfig.getToken());
        this.botService = botService;
        this.botConfig = botConfig;
        this.userRepository = userRepository;

        this.executorService = Executors.newFixedThreadPool(10);
    }

    @Override
    public String getBotUsername() {
        return botConfig.getUsername();
    }

    public void onUpdateReceived(Update update) {
        executorService.execute(() -> {
            try {
                if (update.hasMessage() && update.getMessage().hasText()) {
                    processMessage(update);

                } else if (update.hasCallbackQuery()) {
                    processCallbackQuery(update);
                }
            } catch (Exception e) {
                sendErrorMessage(update);
            }
        });
    }

    private void processMessage(@NotNull Update update) {
        Long chatId = update.getMessage().getChatId();
        String messageText = update.getMessage().getText();

        try {
            BotService.BotResponse response = botService.processMessage(update);
            User user = botService.registerUserIfNeeded(update);

            if ("💼 Подписные тарифы".equals(messageText)) {
                if (user != null) {
                    user.setDialogState(BotConstants.DIALOG_SUBSCRIPTION);
                    userRepository.save(user);
                }

                // Скрываем клавиатуру основного меню
                SendMessage removeKeyboardMessage = new SendMessage();
                removeKeyboardMessage.setChatId(chatId.toString());
                removeKeyboardMessage.setText("Загрузка тарифов...");
                removeKeyboardMessage.setReplyMarkup(new ReplyKeyboardRemove(true));

                try {
                    // После отправки сразу удаляем сообщение, чтобы не мешало
                    Message sentMessage = execute(removeKeyboardMessage);
                    DeleteMessage deleteMessage = new DeleteMessage(chatId.toString(), sentMessage.getMessageId());
                    execute(deleteMessage);

                } catch (TelegramApiException ignored) {
                    // Игнорируем ошибки при скрытии клавиатуры
                }

                // Отправляем основное сообщение с тарифами и клавиатурой
                sentMessage(chatId, response);

                return; // Прерываем выполнение, так как уже отправили сообщение
            }

            sendResponse(chatId.toString(), response, messageText, user);

        } catch (Exception e) {

            try {
                SendMessage errorMessage = new SendMessage();
                errorMessage.setChatId(chatId.toString());
                errorMessage.setText(ERROR_SEND_MESSAGE);
                execute(errorMessage);
            } catch (TelegramApiException ignored) {
            }
        }
    }


    private void processCallbackQuery(@NotNull Update update) {
        CallbackQuery callbackQuery = update.getCallbackQuery();
        Long chatId = callbackQuery.getMessage().getChatId();

        try {
            User user = userRepository.findByTelegramId(callbackQuery.getFrom().getId()).orElse(null);
            String data = callbackQuery.getData();

            if (InlineKeyboardFactory.CALLBACK_MAIN_MENU.equals(data)) {

                if (user != null) {
                    user.setDialogState(BotConstants.DIALOG_MAIN);
                    userRepository.save(user);
                }

                SendMessage mainMenuMessage = new SendMessage();
                mainMenuMessage.setChatId(chatId.toString());
                mainMenuMessage.setText("Вы вернулись в главное меню");
                mainMenuMessage.setParseMode("HTML");
                mainMenuMessage.setReplyMarkup(MainMenuKeyboard.create());

                try {
                    DeleteMessage deleteMessage = new DeleteMessage(chatId.toString(), callbackQuery.getMessage().getMessageId());
                    execute(deleteMessage);
                    execute(mainMenuMessage);

                } catch (TelegramApiException ignored) {
                    // Игнорируем ошибки удаления
                }

                answerCallbackQuery(callbackQuery.getId());
                return;
            }

            BotService.BotResponse response = botService.processCallbackQuery(callbackQuery);

            if (InlineKeyboardFactory.CALLBACK_PREV.equals(data) || InlineKeyboardFactory.CALLBACK_NEXT.equals(data)) {
                try {
                    DeleteMessage deleteMessage = new DeleteMessage(chatId.toString(), callbackQuery.getMessage().getMessageId());
                    execute(deleteMessage);
                } catch (TelegramApiException ignored) {
                    // Игнорируем ошибки удаления
                }

                sentMessage(chatId, response);

                answerCallbackQuery(callbackQuery.getId());
                return;
            }

            if (InlineKeyboardFactory.CALLBACK_PAY.equals(data)) {
                sendResponse(chatId.toString(), response,"", user);
                answerCallbackQuery(callbackQuery.getId());
                return;
            }

            sendResponse(chatId.toString(), response, "", user);
            answerCallbackQuery(callbackQuery.getId());

        } catch (Exception e) {
            try {
                answerCallbackQuery(callbackQuery.getId());

                SendMessage errorMessage = new SendMessage();
                errorMessage.setChatId(chatId.toString());
                errorMessage.setText(ERROR_MESSAGE);
                execute(errorMessage);
            } catch (TelegramApiException ignored) {
                // Игнорируем ошибки при отправке сообщения об ошибке
            }
        }
    }


    private void sendResponse(String chatId, @NotNull BotService.BotResponse response, String originalMessage, User user) {
        String text = response.getText();
        if (text == null || text.isEmpty()) {
            text = ERROR_MESSAGE;
        }

        boolean hasActualContent = !text.trim().isEmpty() && !text.equals(EMPTY_MESSAGE_TEXT);

        if (hasActualContent) {
            if (text.length() > BotConstants.MAX_MESSAGE_LENGTH) {
                String[] messageParts = MessageUtils.splitLongMessage(text);
                for (int i = 0; i < messageParts.length; i++) {
                    String messageText = messageParts[i];
                    if (messageText == null || messageText.isEmpty()) {
                        messageText = EMPTY_MESSAGE_TEXT;
                    }
                    SendMessage message = createMessage(chatId, messageText);

                    // К последнему сообщению добавляем inline-клавиатуру (если есть)
                    if (i == messageParts.length - 1 && response.hasInlineKeyboard()) {
                        message.setReplyMarkup(response.getInlineKeyboard());
                    }

                    try {
                        execute(message);
                    } catch (TelegramApiException ignored) {
                        // Игнорируем ошибки отправки
                    }
                }
            } else {
                // Отправляем основное сообщение с inline-клавиатурой (если есть)
                ReplyKeyboard mainReplyMarkup = response.hasInlineKeyboard() ? response.getInlineKeyboard() : null;
                SendMessage message = createMessage(chatId, text, mainReplyMarkup);
                try {
                    execute(message);
                } catch (TelegramApiException ignored) {
                    // Игнорируем ошибки отправки
                }
            }

            // Если есть Reply-клавиатура, отправляем её отдельным сообщением с точкой и удаляем
            if (response.hasReplyKeyboard()) {
                SendMessage replyMessage = new SendMessage();
                replyMessage.setChatId(chatId);
                replyMessage.setText("\n\nМеню для навигации ⬇️");
                replyMessage.setReplyMarkup(response.getReplyKeyboard());

                // Устанавливаем стандартные настройки для Reply-клавиатуры
                if (response.getReplyKeyboard() instanceof ReplyKeyboardMarkup) {
                    ((ReplyKeyboardMarkup) response.getReplyKeyboard()).setOneTimeKeyboard(false);
                }

                try {
                    // Отправляем сообщение с Reply-клавиатурой
                    execute(replyMessage);

                } catch (TelegramApiException ignored) {

                }
            }
        } else {
            // Если нет контента, определяем клавиатуру по старой логике
            ReplyKeyboard replyMarkup;
            if (response.hasInlineKeyboard()) {
                replyMarkup = response.getInlineKeyboard();
            } else if (response.shouldRemoveReplyKeyboard()) {
                replyMarkup = new ReplyKeyboardRemove(true);
            } else if (response.hasReplyKeyboard()) {
                replyMarkup = response.getReplyKeyboard();
            } else {
                replyMarkup = determineKeyboard(originalMessage, text, user);
            }

            if (replyMarkup != null) {
                if (replyMarkup instanceof ReplyKeyboardMarkup) {
                    ((ReplyKeyboardMarkup) replyMarkup).setOneTimeKeyboard(false);
                }
                SendMessage keyboardMessage = new SendMessage();
                keyboardMessage.setChatId(chatId);
                keyboardMessage.setText(EMPTY_MESSAGE_TEXT);
                keyboardMessage.setReplyMarkup(replyMarkup);
                try {
                    execute(keyboardMessage);
                } catch (TelegramApiException ignored) {
                    // Игнорируем ошибки отправки
                }
            }
        }
    }

    public void sendNotificationWithKeyboard(@NotNull Long telegramId, String message, InlineKeyboardMarkup keyboard) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(telegramId.toString());

        if (message == null || message.isEmpty()) {
            message = "Уведомление";
        }

        sendMessage.setText(message);
        sendMessage.setParseMode("HTML");

        if (keyboard != null) {
            sendMessage.setReplyMarkup(keyboard);
        }

        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке уведомления с клавиатурой пользователю {}: {}",
                    telegramId, e.getMessage(), e);
        }
    }

    private void sentMessage(@NotNull Long chatId, @NotNull BotService.BotResponse response) {
        SendMessage mainMessage = new SendMessage();
        mainMessage.setChatId(chatId.toString());
        mainMessage.setText(response.getText());
        mainMessage.setParseMode("HTML");

        if(response.hasInlineKeyboard()) {
            mainMessage.setReplyMarkup(response.getInlineKeyboard());
        }

        try {
            execute(mainMessage);
        } catch (TelegramApiException ignored) {
            // Игнорируем ошибки при отправке сообщения с тарифами
        }
    }

    @NotNull
    private SendMessage createMessage(String chatId, String text) {
        return createMessage(chatId, text, null);
    }

    @NotNull
    private SendMessage createMessage(String chatId, String text, ReplyKeyboard replyMarkup) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);

        if (text == null || text.isEmpty() || text.trim().isEmpty()) {
            text = EMPTY_MESSAGE_TEXT;
        }

        message.setText(text);
        message.setParseMode("HTML");

        if (replyMarkup != null) {
            if (replyMarkup instanceof ReplyKeyboardMarkup) {
                ((ReplyKeyboardMarkup) replyMarkup).setOneTimeKeyboard(false);
            }

            message.setReplyMarkup(replyMarkup);
        }

        return message;
    }

    private void answerCallbackQuery(String callbackQueryId) {
        AnswerCallbackQuery answerCallback = new AnswerCallbackQuery();
        answerCallback.setCallbackQueryId(callbackQueryId);

        try {
            execute(answerCallback);
        } catch (TelegramApiException e) {
            log.error("Ошибка при ответе на callback запрос: {}", e.getMessage(), e);
        }
    }

    @NotNull
    private ReplyKeyboardMarkup determineKeyboard(String originalMessage, String responseText,User user) {

        if (user != null) {
            user = userRepository.findByTelegramId(user.getTelegramId()).orElse(user);
        } else {
            return MainMenuKeyboard.create();
        }

        ReplyKeyboardMarkup keyboard = switch (user.getDialogState()) {
            case BotConstants.DIALOG_MAIN, BotConstants.DIALOG_PAYMENT, BotConstants.DIALOG_AWAITING_MESSAGE -> MainMenuKeyboard.create();

            default -> throw new IllegalStateException("Unexpected value: " + user.getDialogState());
        };
        keyboard.setOneTimeKeyboard(false);

        return keyboard;
    }

    private void sendErrorMessage(@NotNull Update update) {
        String chatId;

        if (update.hasMessage()) {
            chatId = update.getMessage().getChatId().toString();
        } else if (update.hasCallbackQuery()) {
            chatId = update.getCallbackQuery().getMessage().getChatId().toString();
        } else {
            return;
        }

        ReplyKeyboardMarkup keyboard = MainMenuKeyboard.create();
        keyboard.setOneTimeKeyboard(false);

        SendMessage errorMessage = createMessage(
                chatId,
                ERROR_MESSAGE,
                keyboard
        );

        try {
            execute(errorMessage);
        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке сообщения об ошибке: {}", e.getMessage(), e);
        }
    }
}