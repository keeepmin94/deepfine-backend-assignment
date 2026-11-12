package com.deepfine.inventory.api.exception;

import com.deepfine.inventory.api.dto.response.ApiError;
import com.deepfine.inventory.api.filter.RequestIdFilter;
import com.deepfine.inventory.core.enums.StockErrorCode;
import com.deepfine.inventory.core.exception.StockException;
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
        HttpStatus status = status(exception.code());
        if (status.is5xxServerError()) {
            LOG.error("Stock operation failed", exception);
        } else {
            LOG.warn("Stock request rejected code={}", exception.code());
        }
        return ResponseEntity.status(status).body(error(exception.code(), request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpectedException(Exception exception, HttpServletRequest request) {
        StockErrorCode code = DatabaseFailureClassifier.classify(exception);
        if (code == StockErrorCode.INTERNAL_ERROR) {
            LOG.error("Unhandled request failure", exception);
        } else {
            LOG.warn("Database transaction rejected code={}", code);
        }
        return ResponseEntity.status(status(code)).body(error(code, request));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        StockErrorCode code = statusCode.is4xxClientError()
                ? StockErrorCode.INVALID_REQUEST : StockErrorCode.INTERNAL_ERROR;
        var servletRequest = ((ServletWebRequest) request).getRequest();
        return super.handleExceptionInternal(exception, error(code, servletRequest), headers, statusCode, request);
    }

    private HttpStatus status(StockErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case PRODUCT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PRODUCT_NAME_MISMATCH, STOCK_LIMIT_EXCEEDED, INSUFFICIENT_STOCK -> HttpStatus.CONFLICT;
            case STOCK_BUSY, TRANSACTION_RETRY_REQUIRED -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private ApiError error(StockErrorCode code, HttpServletRequest request) {
        return new ApiError(code.name(), code.message(), request.getRequestURI(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
