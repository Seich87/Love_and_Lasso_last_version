package com.loveandlasso.bot.service;

import com.loveandlasso.bot.constant.BotConstants;
import com.loveandlasso.bot.model.User;
import com.loveandlasso.bot.model.Subscription;
import com.loveandlasso.bot.model.SubscriptionType;
import com.loveandlasso.bot.model.UsageLog;
import com.loveandlasso.bot.repository.SubscriptionRepository;
import com.loveandlasso.bot.repository.UsageLogRepository;
import com.loveandlasso.bot.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;

import static com.loveandlasso.bot.constant.BotConstants.*;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final UsageLogRepository usageLogRepository;
    private final UserRepository userRepository;

    @Autowired
    public SubscriptionService(SubscriptionRepository subscriptionRepository, UsageLogRepository usageLogRepository, UserRepository userRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.usageLogRepository = usageLogRepository;
        this.userRepository = userRepository;
    }

    public Optional<Subscription> getActiveSubscription(User user) {
        LocalDateTime now = LocalDateTime.now();

        return subscriptionRepository.findActiveSubscription(user, now);
    }

    @Transactional
    public Subscription createSubscription(User user, SubscriptionType type) {

        Optional<Subscription> activeSubscription = getActiveSubscription(user);
        activeSubscription.ifPresent(subscription -> {
            subscription.setActive(false);
            subscriptionRepository.save(subscription);
        });

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endDate;

        if (type == SubscriptionType.TEST) {
            endDate = now.plusDays(3);
        } else {
            endDate = now.plusDays(BotConstants.SUBSCRIPTION_MONTHLY_DAYS);
        }

        int requestsPerDay = switch (type) {
            case ROMANTIC -> ROMANTIC_REQUESTS_PER_DAY;
            case ALPHA -> ALPHA_REQUESTS_PER_DAY;
            case LOVELACE -> LOVELACE_REQUESTS_PER_DAY;
            case TEST -> FREE_REQUESTS_PER_DAY;
        };

        double price = switch (type) {
            case ROMANTIC -> ROMANTIC_PRICE_MONTHLY;
            case ALPHA -> ALPHA_PRICE_MONTHLY;
            case LOVELACE -> LOVELACE_PRICE_MONTHLY;
            case TEST -> FREE_REQUESTS_PRICE_MONTHLY;
        };

        user.setSubscriptionEndDate(endDate);
        userRepository.save(user);

        Subscription subscription = Subscription.builder()
                .user(user)
                .type(type)
                .startDate(now)
                .endDate(endDate)
                .active(true)
                .autoRenewal(false)
                .requestsPerDay(requestsPerDay)
                .price(price)
                .build();

        return subscriptionRepository.save(subscription);
    }

    @Transactional
    public void createTestSubscription(User user) {
        createSubscription(user, SubscriptionType.TEST);
    }

    public boolean hasUserUsedTestPlan(User user) {

        return subscriptionRepository.existsByUserAndType(user, SubscriptionType.TEST);
    }

    public boolean isDailyLimitExceeded(User user) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = LocalDate.now().atTime(LocalTime.MAX);

        int usedToday = usageLogRepository.countRequestsInTimeRange(user, startOfDay, endOfDay);
        int limit = getDailyRequestLimit(user);

        return usedToday >= limit;
    }

    public int getDailyRequestLimit(User user) {
        Optional<Subscription> activeSubscription = getActiveSubscription(user);

        return activeSubscription.map(Subscription::getRequestsPerDay)
                .orElse(BotConstants.FREE_REQUESTS_PER_DAY);
    }

    public int getRemainingRequests(User user) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = LocalDate.now().atTime(LocalTime.MAX);

        int usedToday = usageLogRepository.countRequestsInTimeRange(user, startOfDay, endOfDay);
        int limit = getDailyRequestLimit(user);

        return Math.max(0, limit - usedToday);
    }

    @Transactional
    public void logUsage(User user, String query, Integer messageId, boolean successful, Integer tokenCount) {
        UsageLog usageLog = UsageLog.builder()
                .user(user)
                .requestTime(LocalDateTime.now())
                .requestType("chat")
                .successful(successful)
                .tokenCount(tokenCount)
                .userQuery(query)
                .messageId(messageId)
                .build();

        usageLogRepository.save(usageLog);
    }

    public Map<String, Double> getAllSubscriptionPrices() {
        return Map.of(
                "romanticMonthly", ROMANTIC_PRICE_MONTHLY,
                "alphaMonthly", ALPHA_PRICE_MONTHLY,
                "lovelaceMonthly", LOVELACE_PRICE_MONTHLY,
                "testMonthly", FREE_REQUESTS_PRICE_MONTHLY
        );
    }
}
