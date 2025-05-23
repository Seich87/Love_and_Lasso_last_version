package com.loveandlasso.bot.repository;

import com.loveandlasso.bot.model.Subscription;
import com.loveandlasso.bot.model.SubscriptionType;
import com.loveandlasso.bot.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    @Query("SELECT s FROM Subscription s WHERE s.user = :user AND s.active = true AND s.endDate > :now")
    Optional<Subscription> findActiveSubscription(@Param("user") User user, @Param("now") LocalDateTime now);

    boolean existsByUserAndType(User user, SubscriptionType type);
}
