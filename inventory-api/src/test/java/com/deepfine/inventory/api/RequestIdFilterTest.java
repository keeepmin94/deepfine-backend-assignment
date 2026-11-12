package com.deepfine.inventory.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private Map<String, String> originalContext;

    @BeforeEach
    void clearContext() {
        originalContext = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    @AfterEach
    void restoreContext() {
        MDC.clear();
        if (originalContext != null) {
            MDC.setContextMap(originalContext);
        }
    }

    @Test
    void generatesNewIdsForEachRequestRatherThanTrustingClientIds() throws Exception {
        var filter = new RequestIdFilter();
        var first = new MockHttpServletRequest("GET", "/first");
        var second = new MockHttpServletRequest("GET", "/second");
        first.addHeader(RequestIdFilter.HEADER, "client-supplied-id");
        second.addHeader(RequestIdFilter.HEADER, "client-supplied-id");
        var firstResponse = new MockHttpServletResponse();
        var secondResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, (request, response) -> {
            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo(first.getAttribute(RequestIdFilter.ATTRIBUTE));
        });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        filter.doFilter(second, secondResponse, (request, response) -> {
            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo(second.getAttribute(RequestIdFilter.ATTRIBUTE));
        });
        String firstId = firstResponse.getHeader(RequestIdFilter.HEADER);
        String secondId = secondResponse.getHeader(RequestIdFilter.HEADER);
        assertThat(UUID.fromString(firstId).toString()).isEqualTo(firstId);
        assertThat(UUID.fromString(secondId).toString()).isEqualTo(secondId);
        assertThat(firstId).isNotEqualTo(secondId).isNotEqualTo("client-supplied-id");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void restoresTheWholePreviousContextAfterCompletion() throws Exception {
        MDC.setContextMap(Map.of("requestId", "outer-id", "existing", "keep"));
        var request = new MockHttpServletRequest("GET", "/normal");
        new RequestIdFilter().doFilter(request, new MockHttpServletResponse(), (servletRequest, response) -> {
            assertThat(MDC.get("requestId")).isNotEqualTo("outer-id");
            MDC.put("temporary", "remove");
        });
        assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(
                Map.of("requestId", "outer-id", "existing", "keep"));
    }

    @Test
    void restoresThePreviousContextEvenWhenDownstreamThrows() {
        MDC.setContextMap(Map.of("existing", "keep"));
        var request = new MockHttpServletRequest("GET", "/failed");
        assertThatThrownBy(() -> new RequestIdFilter().doFilter(request, new MockHttpServletResponse(),
                (servletRequest, response) -> {
                    MDC.put("temporary", "remove");
                    throw new IOException("downstream failure");
                })).isInstanceOf(IOException.class);
        assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(Map.of("existing", "keep"));
    }

    @Test
    void completionLogContainsTheSameIdWithoutRequestBody() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        var appender = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest("POST", "/example");
            request.setContent("private-request-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            new RequestIdFilter().doFilter(request, response, (servletRequest, servletResponse) -> response.setStatus(201));
            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            assertThat(event.getMDCPropertyMap().get("requestId")).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
            assertThat(event.getFormattedMessage()).contains("status=201").doesNotContain("private-request-body");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
