package com.loveandlasso.bot.constant;

public class BotConstants {

    public static final String PAYMENT_SUCCEEDED = "succeeded";

    public static final String PAYMENT_FAILED = "failed";

    public static final String PAYMENT_CANCELED = "canceled";

    public static final String DIALOG_MAIN = "MAIN";

    public static final String DIALOG_AWAITING_MESSAGE = "AWAITING_MESSAGE";

    public static final String DIALOG_SUBSCRIPTION = "SUBSCRIPTION";

    public static final String DIALOG_PAYMENT = "PAYMENT";

    public static final String DIALOG_PLAN_DETAILS = "plan_details";

    public static final int ROMANTIC_REQUESTS_PER_DAY = 50;

    public static final int ALPHA_REQUESTS_PER_DAY = 150;

    public static final int LOVELACE_REQUESTS_PER_DAY = Integer.MAX_VALUE; // безлимит

    public static final int FREE_REQUESTS_PER_DAY = 5; // Лимит для бесплатных пользователей

    public static final double ROMANTIC_PRICE_MONTHLY = 690.00;

    public static final double ALPHA_PRICE_MONTHLY = 990.00;

    public static final double LOVELACE_PRICE_MONTHLY = 1690.00;

    public static final double FREE_REQUESTS_PRICE_MONTHLY = 0.00;

    public static final int SUBSCRIPTION_MONTHLY_DAYS = 30;

    public static final int MAX_MESSAGE_LENGTH = 4096; // Лимит Telegram Bot API
    public static final int COZE_API_MAX_MESSAGE_LENGTH = 5000; // Было 8000
    public static final int COZE_API_SAFE_LENGTH = 4100; // Было 7500

}
