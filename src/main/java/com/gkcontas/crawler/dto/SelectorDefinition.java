package com.gkcontas.crawler.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @param css       CSS selector, e.g. {@code h1.product-title}
 * @param attribute attribute to read instead of the text, e.g. {@code src} or {@code href}
 * @param multiple  when true every match is returned as a list; otherwise the first one
 */
public record SelectorDefinition(
        @NotBlank(message = "must not be blank") String css,
        String attribute,
        Boolean multiple) {

    public boolean isMultiple() {
        return Boolean.TRUE.equals(multiple);
    }
}
