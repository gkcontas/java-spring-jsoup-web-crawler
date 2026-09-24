package com.gkcontas.crawler.exception;

/**
 * The URL was refused before any request was made.
 *
 * <p>Carries the reason because a caller who typed the wrong thing deserves to know why,
 * and an operator reading the logs needs to tell a typo apart from an attempt to reach
 * the internal network.
 */
public class BlockedUrlException extends RuntimeException {

    public BlockedUrlException(String url, String reason) {
        super("Refused to fetch '%s': %s".formatted(url, reason));
    }
}
