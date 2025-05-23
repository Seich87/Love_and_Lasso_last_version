package com.loveandlasso.bot.keyboard;

import org.jetbrains.annotations.NotNull;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;

public class InlineKeyboardFactory {

    public static final String CALLBACK_TEST_MODE = "test_mode";

    public static final String CALLBACK_HELP_MODE = "help_mode";

    public static final String CALLBACK_CONTACT = "contact_button";

    public static final String CALLBACK_START_DIALOG = "start_dialog";

    public static final String CALLBACK_PREV = "prev_button";

    public static final String CALLBACK_NEXT = "next_button";

    public static final String CALLBACK_PAY = "pay_button";

    public static final String CALLBACK_MAIN_MENU = "mainMenu_button";

    @NotNull
    public static InlineKeyboardMarkup createTestModeKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Кнопка "Тестовый режим"
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton testModeButton = new InlineKeyboardButton();
        testModeButton.setText("🚀 Тестовый режим");
        testModeButton.setCallbackData(CALLBACK_TEST_MODE);
        row1.add(testModeButton);
        rows.add(row1);

        markup.setKeyboard(rows);
        return markup;
    }

    @NotNull
    public static InlineKeyboardMarkup createHelpKeyboard() {
        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        // Кнопка связи для техподдержки
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton contactButton = new InlineKeyboardButton();
        contactButton.setText("📞 Связаться");
        // Используем прямую URL-ссылку для открытия чата
        contactButton.setUrl("https://t.me/Estreman");
        row1.add(contactButton);

        rowsInline.add(row1);
        markupInline.setKeyboard(rowsInline);

        return markupInline;
    }

    @NotNull
    public static InlineKeyboardMarkup createStartDialogKeyboard() {
        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        // Кнопка для начала диалога
        List<InlineKeyboardButton> row = new ArrayList<>();
        InlineKeyboardButton startDialogButton = new InlineKeyboardButton();
        startDialogButton.setText("🚀 Начать диалог");
        startDialogButton.setCallbackData(CALLBACK_START_DIALOG);
        row.add(startDialogButton);

        rowsInline.add(row);
        markupInline.setKeyboard(rowsInline);

        return markupInline;
    }

    @NotNull
    public static InlineKeyboardMarkup createNavigationWithPaymentKeyboard() {
        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();

        InlineKeyboardButton prevButton = new InlineKeyboardButton();
        prevButton.setText("⬅️");
        prevButton.setCallbackData(CALLBACK_PREV);
        row1.add(prevButton);

        InlineKeyboardButton payButton = new InlineKeyboardButton();
        payButton.setText("Оплатить");
        payButton.setCallbackData(CALLBACK_PAY);
        row1.add(payButton);

        InlineKeyboardButton nextButton = new InlineKeyboardButton();
        nextButton.setText("➡️");
        nextButton.setCallbackData(CALLBACK_NEXT);
        row1.add(nextButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton mainMenu = new InlineKeyboardButton();
        mainMenu.setText("Назад в главное меню");
        mainMenu.setCallbackData(CALLBACK_MAIN_MENU);
        row2.add(mainMenu);

        rowsInline.add(row1);
        rowsInline.add(row2);
        markupInline.setKeyboard(rowsInline);

        return markupInline;
    }

    @NotNull
    public static InlineKeyboardMarkup createNavigationWithPaymentForFreeKeyboard() {
        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();

        InlineKeyboardButton prevButton = new InlineKeyboardButton();
        prevButton.setText("⬅️");
        prevButton.setCallbackData(CALLBACK_PREV);
        row1.add(prevButton);

        InlineKeyboardButton payButton = new InlineKeyboardButton();
        payButton.setText("Активировать");
        payButton.setCallbackData(CALLBACK_PAY);
        row1.add(payButton);

        InlineKeyboardButton nextButton = new InlineKeyboardButton();
        nextButton.setText("➡️");
        nextButton.setCallbackData(CALLBACK_NEXT);
        row1.add(nextButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton mainMenu = new InlineKeyboardButton();
        mainMenu.setText("Назад в главное меню");
        mainMenu.setCallbackData(CALLBACK_MAIN_MENU);
        row2.add(mainMenu);

        rowsInline.add(row1);
        rowsInline.add(row2);
        markupInline.setKeyboard(rowsInline);

        return markupInline;
    }
}
