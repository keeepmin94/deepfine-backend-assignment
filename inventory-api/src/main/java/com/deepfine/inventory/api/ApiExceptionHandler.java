package com.deepfine.inventory.api;

import com.deepfine.inventory.core.StockErrorCode;
import com.deepfine.inventory.core.StockException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(StockException.class)
    ResponseEntity<Object> handleStockException(StockException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case PRODUCT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PRODUCT_NAME_MISMATCH, STOCK_LIMIT_EXCEEDED, INSUFFICIENT_STOCK -> HttpStatus.CONFLICT;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        if (status.is5xxServerError()) {
            LOG.error("Stock operation failed", exception);
        }
        return ResponseEntity.status(status).body(error(exception.code(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpectedException(Exception exception, HttpServletRequest request) {
        LOG.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(StockErrorCode.INTERNAL_ERROR, request.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        StockErrorCode code = statusCode.is4xxClientError()
                ? StockErrorCode.INVALID_REQUEST : StockErrorCode.INTERNAL_ERROR;
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        return super.handleExceptionInternal(exception, error(code, path), headers, statusCode, request);
    }

    private ApiError error(StockErrorCode code, String path) {
        return new ApiError(code.name(), code.message(), path);
    }
}
