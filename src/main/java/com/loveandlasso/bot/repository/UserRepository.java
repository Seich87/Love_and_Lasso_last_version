package com.loveandlasso.bot.repository;

import com.loveandlasso.bot.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByTelegramId(Long telegramId);

    // Поиск по ID платежа
    Optional<User> findByCurrentPaymentId(String paymentId);
}
