package com.loveandlasso.bot.model;

public enum SubscriptionType {

    ROMANTIC("Романтик"),

    ALPHA("Альфа"),

    LOVELACE("Ловелас"),

    TEST("Тестовый режим");

    private final String displayName;

    SubscriptionType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
