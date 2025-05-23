package com.loveandlasso.bot.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    private Long telegramId;

    private String firstName;

    private String lastName;

    private String username;

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;

    @Column(name = "last_activity")
    private LocalDateTime lastActivity;

    private boolean active;

    @Column(name = "awaiting_response")
    private boolean awaitingResponse;

    @Column(name = "dialog_state")
    private String dialogState;

    @Column(name = "selected_plan")
    private String selectedPlan;

    @Column(name = "current_payment_id")
    private String currentPaymentId;

    @Column(name = "subscription_end_date")
    private LocalDateTime subscriptionEndDate;
}