package com.loveandlasso.bot.repository;

import com.loveandlasso.bot.model.UsageLog;
import com.loveandlasso.bot.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface UsageLogRepository extends JpaRepository<UsageLog, Long> {

    @Query("SELECT COUNT(u) FROM UsageLog u WHERE u.user = :user AND u.requestTime >= :startTime AND u.requestTime <= :endTime")
    int countRequestsInTimeRange(@Param("user") User user, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

}
