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
public class UsageInfo {

    @JsonProperty("prompt_tokens")
    private Integer promptTokens;

    @JsonProperty("completion_tokens")
    private Integer completionTokens;

    @JsonProperty("total_tokens")
    private Integer totalTokens;

    public int total_tokens() {
        return totalTokens != null ? totalTokens : 0;
    }

    public int prompt_tokens() {
        return promptTokens != null ? promptTokens : 0;
    }

    public int completion_tokens() {
        return completionTokens != null ? completionTokens : 0;
    }
}
