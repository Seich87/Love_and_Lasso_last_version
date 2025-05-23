package com.loveandlasso.bot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CozeData {

    private String id;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("bot_id")
    private String botId;

    @JsonProperty("created_at")
    private Long createdAt;

    @JsonProperty("last_error")
    private CozeError lastError;

    private String status;
}
