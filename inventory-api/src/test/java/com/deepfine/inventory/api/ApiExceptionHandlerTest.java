package com.deepfine.inventory.api;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    @ParameterizedTest
    @CsvSource({"55P03, STOCK_BUSY", "40P01, TRANSACTION_RETRY_REQUIRED"})
    void recognizedDatabaseFailuresReturn503WithoutExposingSql(String sqlState, String code) {
        var request = request();
        var response = new ApiExceptionHandler().handleUnexpectedException(
                new RuntimeException("private SQL", new SQLException("secret details", sqlState)), request);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        var body = (ApiError) response.getBody();
        assertThat(body.code()).isEqualTo(code);
        assertThat(body.requestId()).isEqualTo(request.getAttribute(RequestIdFilter.ATTRIBUTE));
        assertThat(body.message()).doesNotContain("private SQL", "secret details", sqlState);
        assertThat(body.path()).isEqualTo("/api/v1/stock/inbounds");
    }

    @Test
    void integrityFailureIs500RatherThan503() {
        var response = new ApiExceptionHandler().handleUnexpectedException(
                new DataIntegrityViolationException("private constraint", new SQLException("secret details", "23514")),
                request());
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(((ApiError) response.getBody()).code()).isEqualTo("INTERNAL_ERROR");
        assertThat(((ApiError) response.getBody()).message()).doesNotContain("constraint", "secret");
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/stock/inbounds");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, UUID.randomUUID().toString());
        return request;
    }
}
