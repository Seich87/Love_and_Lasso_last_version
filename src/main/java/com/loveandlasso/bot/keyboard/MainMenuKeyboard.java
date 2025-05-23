package com.loveandlasso.bot.keyboard;

import org.jetbrains.annotations.NotNull;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.ArrayList;
import java.util.List;

public class MainMenuKeyboard {

    @NotNull
    public static ReplyKeyboardMarkup create() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("👤 Мой профиль"));
        row1.add(new KeyboardButton("ℹ\uFE0F Инструкция"));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("💼 Подписные тарифы"));
        row2.add(new KeyboardButton("\uD83D\uDD27 Тех.поддержка"));

        List<KeyboardRow> keyboard = new ArrayList<>();
        keyboard.add(row1);
        keyboard.add(row2);

        ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup();
        replyKeyboardMarkup.setKeyboard(keyboard);
        replyKeyboardMarkup.setResizeKeyboard(true);
        replyKeyboardMarkup.setOneTimeKeyboard(false);
        replyKeyboardMarkup.setSelective(false);

        return replyKeyboardMarkup;
    }
}
