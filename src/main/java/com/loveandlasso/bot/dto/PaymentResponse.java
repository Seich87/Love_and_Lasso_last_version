package com.loveandlasso.bot.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private String id;

    private String status;

    private double amount;

    private String currency;

    private String paymentUrl;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    private Boolean paid;

    private String description;
}
