package com.loveandlasso.bot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CozeApiResponse {

    private CozeData data;

    private Integer code;

    private String msg;

    private String responseText;

    private String id;

    private String object;

    private Long created;

    private String model;

    private List<ChoiceItem> choices;

    private UsageInfo usage;

}