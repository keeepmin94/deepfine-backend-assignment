package com.deepfine.inventory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.deepfine.inventory.api.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inventory.lock-timeout-ms=250")
class RequestTraceAndBusyE2eTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.14");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private HttpClient client;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE stock_movement, inventory, product RESTART IDENTITY");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        client.close();
        jdbc.execute("DROP TRIGGER IF EXISTS timeout_at_http_commit ON stock_movement");
        jdbc.execute("DROP FUNCTION IF EXISTS timeout_at_http_commit()");
        jdbc.execute("DROP TABLE IF EXISTS http_timeout_gate");
    }

    @Test
    void identicalRequestsGetDifferentServerIdsAndRemainSeparateMovements() throws Exception {
        String json = """
                {"productCode":"PRODUCT-A","name":"상품","quantity":1}
                """;
        var first = post("/api/v1/stock/inbounds", json);
        var second = post("/api/v1/stock/inbounds", json);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(id(first)).isNotEqualTo(id(second)).isNotEqualTo("client-supplied-id");
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(2);
    }

    @Test
    void concurrentErrorResponsesAndCompletionLogsKeepTheirOwnIds() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<HttpResponse<String>>>();
        try (var logs = new CompletionLogs(8)) {
            for (int index = 0; index < 8; index++) {
                String path = "/api/v1/products/MISSING-" + index + "/stock";
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("HTTP start timed out");
                    }
                    return get(path);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var responses = new ArrayList<HttpResponse<String>>();
            var failures = new ArrayList<Exception>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (var future : futures) {
                try {
                    responses.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (Exception exception) {
                    failures.add(exception);
                }
            }
            assertThat(failures).isEmpty();
            assertThat(responses).hasSize(8);
            var ids = new ArrayList<String>();
            for (var response : responses) {
                assertError(response, 404, "PRODUCT_NOT_FOUND");
                ids.add(id(response));
            }
            assertThat(ids).doesNotHaveDuplicates();
            assertThat(logs.completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(logs.events).hasSize(8);
            assertThat(logs.events.stream().map(event -> event.getMDCPropertyMap().get("requestId")).toList())
                    .containsExactlyInAnyOrderElementsOf(ids);
            for (var response : responses) {
                String path = mapper.readTree(response.body()).path("path").asText();
                assertThat(logs.events).anySatisfy(event -> {
                    assertThat(event.getMDCPropertyMap().get("requestId")).isEqualTo(id(response));
                    assertThat(event.getFormattedMessage()).contains("path=" + path, "status=404");
                });
            }
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rowLockTimeoutReturns503AndPreservesStockUntilAnExplicitRetry() throws Exception {
        seedStock();
        var before = jdbc.queryForMap("SELECT * FROM inventory");
        try (var blocker = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement();
                    var rows = statement.executeQuery("SELECT product_id FROM inventory FOR UPDATE")) {
                assertThat(rows.next()).isTrue();
            }
            assertError(post("/api/v1/stock/outbounds", """
                    {"productCode":"PRODUCT-A","quantity":3}
                    """), 503, "STOCK_BUSY");
            assertThat(jdbc.queryForMap("SELECT * FROM inventory")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(1);
            blocker.rollback();
        }
        assertThat(post("/api/v1/stock/outbounds", """
                {"productCode":"PRODUCT-A","quantity":3}
                """).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(2);
    }

    @Test
    void registrationConflictReturns503WithoutPartialData() throws Exception {
        try (var blocker = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("INSERT INTO product (product_code, name) VALUES (?, ?)")) {
                statement.setString(1, "PRODUCT-A");
                statement.setString(2, "상품");
                statement.executeUpdate();
            }
            assertError(post("/api/v1/stock/inbounds", """
                    {"productCode":"PRODUCT-A","name":"상품","quantity":5}
                    """), 503, "STOCK_BUSY");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
            blocker.rollback();
        }
        assertThat(post("/api/v1/stock/inbounds", """
                {"productCode":"PRODUCT-A","name":"상품","quantity":5}
                """).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(1);
    }

    @Test
    void timeoutAtCommitAfterTheInventoryUpdateRollsBackRatherThanReturning201() throws Exception {
        seedStock();
        var before = jdbc.queryForMap("SELECT * FROM inventory");
        jdbc.execute("CREATE TABLE http_timeout_gate (id INTEGER PRIMARY KEY)");
        jdbc.execute("INSERT INTO http_timeout_gate VALUES (1)");
        jdbc.execute("""
                CREATE FUNCTION timeout_at_http_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF (SELECT quantity FROM inventory WHERE product_id = NEW.product_id) IS DISTINCT FROM NEW.quantity_after THEN
                        RAISE EXCEPTION 'Inventory was not flushed' USING ERRCODE = 'P0001';
                    END IF;
                    PERFORM id FROM http_timeout_gate WHERE id = 1 FOR UPDATE;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER timeout_at_http_commit AFTER INSERT ON stock_movement
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION timeout_at_http_commit()
                """);
        try (var blocker = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement();
                    var rows = statement.executeQuery("SELECT id FROM http_timeout_gate FOR UPDATE")) {
                assertThat(rows.next()).isTrue();
            }
            assertError(post("/api/v1/stock/inbounds", """
                    {"productCode":"PRODUCT-A","name":"상품","quantity":3}
                    """), 503, "STOCK_BUSY");
            assertThat(jdbc.queryForMap("SELECT * FROM inventory")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(1);
            blocker.rollback();
        }
    }

    @Test
    void validationAndUnknownRouteErrorsHaveConsistentTraceBodies() throws Exception {
        assertError(post("/api/v1/stock/inbounds", "{bad-json}"), 400, "INVALID_REQUEST");
        assertError(get("/unknown-resource"), 404, "INVALID_REQUEST");
    }

    private void seedStock() throws Exception {
        assertThat(post("/api/v1/stock/inbounds", """
                {"productCode":"PRODUCT-A","name":"상품","quantity":10}
                """).statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return client.send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("X-Request-Id", "client-supplied-id");
    }

    private String id(HttpResponse<String> response) {
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
        return requestId;
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("path").asText()).isEqualTo(response.request().uri().getPath());
        assertThat(body.path("requestId").asText()).isEqualTo(id(response));
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(response.body()).doesNotContain("SQLException", "Exception", "SELECT", "stackTrace", "55P03");
    }

    private static final class CompletionLogs extends AppenderBase<ILoggingEvent> implements AutoCloseable {

        private final Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch completed;

        private CompletionLogs(int count) {
            completed = new CountDownLatch(count);
            start();
            logger.addAppender(this);
        }

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
            completed.countDown();
        }

        @Override
        public void close() {
            logger.detachAppender(this);
            stop();
        }
    }
}
