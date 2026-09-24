package com.gkcontas.crawler.exception;

public class FetchFailedException extends RuntimeException {

    public FetchFailedException(String message) {
        super(message);
    }

    public FetchFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
