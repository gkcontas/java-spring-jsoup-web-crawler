package com.gkcontas.crawler.exception;

public class InvalidSelectorException extends RuntimeException {

    public InvalidSelectorException(String field, String css, String reason) {
        super("Selector for field '%s' is not valid CSS ('%s'): %s".formatted(field, css, reason));
    }
}
