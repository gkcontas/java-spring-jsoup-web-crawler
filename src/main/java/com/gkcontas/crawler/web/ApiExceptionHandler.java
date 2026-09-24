package com.gkcontas.crawler.web;

import com.gkcontas.crawler.exception.BlockedUrlException;
import com.gkcontas.crawler.exception.CrawlJobNotFoundException;
import com.gkcontas.crawler.exception.FetchFailedException;
import com.gkcontas.crawler.exception.InvalidSelectorException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 400 and not 403: the caller asked for something this service will not do, which is
     * a malformed request rather than a permission the caller might otherwise hold.
     */
    @ExceptionHandler(BlockedUrlException.class)
    public ProblemDetail handleBlockedUrl(BlockedUrlException exception) {
        return problem(HttpStatus.BAD_REQUEST, "URL not allowed", exception.getMessage());
    }

    @ExceptionHandler(InvalidSelectorException.class)
    public ProblemDetail handleInvalidSelector(InvalidSelectorException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid selector", exception.getMessage());
    }

    /**
     * 502: the request was fine, the upstream site is the problem. Reporting it as 500
     * would point the finger at this service and send whoever is debugging to the wrong
     * logs.
     */
    @ExceptionHandler(FetchFailedException.class)
    public ProblemDetail handleFetchFailed(FetchFailedException exception) {
        return problem(HttpStatus.BAD_GATEWAY, "Could not fetch the page", exception.getMessage());
    }

    @ExceptionHandler(CrawlJobNotFoundException.class)
    public ProblemDetail handleJobNotFound(CrawlJobNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Unknown crawl job", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidBody(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> "%s %s".formatted(error.getField(), error.getDefaultMessage()))
                .orElse("Invalid request body.");
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        problemDetail.setTitle(title);
        problemDetail.setDetail(detail);
        return problemDetail;
    }
}
