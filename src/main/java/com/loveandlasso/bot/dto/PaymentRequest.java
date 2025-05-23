package com.loveandlasso.bot.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import com.loveandlasso.bot.model.SubscriptionType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {

    private Long userId;

    private Double amount;

    private String currency;

    private String description;

    private SubscriptionType subscriptionType;

    private String returnUrl;
}
