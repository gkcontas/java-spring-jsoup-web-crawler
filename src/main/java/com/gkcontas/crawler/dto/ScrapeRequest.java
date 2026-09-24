package com.gkcontas.crawler.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

public record ScrapeRequest(
        @NotBlank(message = "must not be blank") String url,
        @NotEmpty(message = "must not be empty") Map<String, @Valid SelectorDefinition> selectors) {
}
