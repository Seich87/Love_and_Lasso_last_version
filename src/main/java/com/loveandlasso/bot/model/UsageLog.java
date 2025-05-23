package com.loveandlasso.bot.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;

@Entity
@Table(name = "usage_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id")
    private Integer messageId;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "request_time", nullable = false)
    private LocalDateTime requestTime;

    @Column(name = "request_type")
    private String requestType;

    private boolean successful;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "user_query", length = 10000)
    private String userQuery;
}
