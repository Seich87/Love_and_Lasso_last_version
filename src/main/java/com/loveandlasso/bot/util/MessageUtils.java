package com.loveandlasso.bot.util;

import com.loveandlasso.bot.constant.BotConstants;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class MessageUtils {

    @NotNull
    public static String[] splitLongMessage(@NotNull String text) {
        if (text.length() <= BotConstants.MAX_MESSAGE_LENGTH) {
            return new String[]{text};
        }

        List<String> parts = new ArrayList<>();
        int maxLength = BotConstants.MAX_MESSAGE_LENGTH;

        // Разбиваем текст умно
        int start = 0;
        while (start < text.length()) {
            if (start + maxLength >= text.length()) {
                // Последняя часть
                parts.add(text.substring(start));
                break;
            }

            // Ищем лучшее место для разрыва
            int end = findBestSplitPoint(text, start, start + maxLength);
            parts.add(text.substring(start, end));
            start = end;
        }

        return parts.toArray(new String[0]);
    }

    private static int findBestSplitPoint(String text, int start, int maxEnd) {
        if (maxEnd >= text.length()) {
            return text.length();
        }

        String segment = text.substring(start, maxEnd);

        // 1. Ищем конец предложения (приоритет 1)
        int sentenceEnd = findLastSentenceEnd(segment);
        if (sentenceEnd > segment.length() * 0.7) { // Если предложение занимает больше 70%
            return start + sentenceEnd + 1;
        }

        // 2. Ищем конец абзаца (приоритет 2)
        int paragraphEnd = segment.lastIndexOf("\n\n");
        if (paragraphEnd > segment.length() * 0.6) {
            return start + paragraphEnd + 2;
        }

        // 3. Ищем конец строки (приоритет 3)
        int lineEnd = segment.lastIndexOf('\n');
        if (lineEnd > segment.length() * 0.6) {
            return start + lineEnd + 1;
        }

        // 4. Ищем пробел (приоритет 4)
        int spaceIndex = segment.lastIndexOf(' ');
        if (spaceIndex > segment.length() * 0.5) {
            return start + spaceIndex + 1;
        }

        // 5. В крайнем случае - режем по максимальной длине
        return maxEnd;
    }

    private static int findLastSentenceEnd(String text) {
        int lastDot = text.lastIndexOf('.');
        int lastExclamation = text.lastIndexOf('!');
        int lastQuestion = text.lastIndexOf('?');

        return Math.max(lastDot, Math.max(lastExclamation, lastQuestion));
    }
}
