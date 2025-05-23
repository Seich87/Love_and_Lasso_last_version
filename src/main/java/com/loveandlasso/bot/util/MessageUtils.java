package com.loveandlasso.bot.util;

import com.loveandlasso.bot.constant.BotConstants;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class MessageUtils {

    @NotNull
    public static String[] splitLongMessage(@NotNull String text) {
        List<String> parts = new ArrayList<>();
        int maxLength = BotConstants.MAX_MESSAGE_LENGTH;

        for (int i = 0; i < text.length(); i += maxLength) {
            parts.add(text.substring(i, Math.min(i + maxLength, text.length())));
        }

        return parts.toArray(new String[0]);
    }
}
