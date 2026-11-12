package com.deepfine.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboundAndQueryE2eTest {

    private static final String OUTBOUND_PATH = "/api/v1/stock/outbounds";

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
        jdbc.execute("DROP TRIGGER IF EXISTS reject_outbound_http ON stock_movement");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_outbound_http_at_commit()");
    }

    @Test
    void inboundOutboundAndQueryWorkThroughRealHttpIncludingFullConsumption() throws Exception {
        seedStock(10);
        var response = post(OUTBOUND_PATH, """
                {"productCode":"PRODUCT-A","quantity":3}
                """);
        assertThat(response.statusCode()).isEqualTo(201);
        var movement = mapper.readTree(response.body());
        assertThat(movement.size()).isEqualTo(6);
        assertThat(movement.path("movementId").asLong()).isPositive();
        assertThat(movement.path("productCode").asText()).isEqualTo("PRODUCT-A");
        assertThat(movement.path("movementType").asText()).isEqualTo("OUTBOUND");
        assertThat(movement.path("quantity").asLong()).isEqualTo(3);
        assertThat(movement.path("quantityAfter").asLong()).isEqualTo(7);
        assertThat(movement.path("createdAt").asText()).endsWith("Z");
        assertThat(jdbc.queryForObject("SELECT created_at FROM stock_movement WHERE id = ?",
                java.sql.Timestamp.class, movement.path("movementId").asLong()).toInstant())
                .isEqualTo(Instant.parse(movement.path("createdAt").asText()));

        var query = getStock("PRODUCT-A");
        assertThat(query.statusCode()).isEqualTo(200);
        var snapshot = mapper.readTree(query.body());
        assertThat(snapshot.size()).isEqualTo(5);
        assertThat(snapshot.path("productId").asLong()).isEqualTo(
                jdbc.queryForObject("SELECT id FROM product", Long.class));
        assertThat(snapshot.path("productCode").asText()).isEqualTo("PRODUCT-A");
        assertThat(snapshot.path("name").asText()).isEqualTo("상품 A");
        assertThat(snapshot.path("quantity").asLong()).isEqualTo(7);
        assertThat(snapshot.path("updatedAt").asText()).endsWith("Z");
        assertThat(Instant.parse(snapshot.path("updatedAt").asText())).isEqualTo(
                jdbc.queryForObject("SELECT updated_at FROM inventory", java.sql.Timestamp.class).toInstant());
        assertState(7, 2);

        assertThat(post(OUTBOUND_PATH, """
                {"productCode":"PRODUCT-A","quantity":7}
                """).statusCode()).isEqualTo(201);
        var empty = getStock("PRODUCT-A");
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(empty.body()).path("quantity").asLong()).isZero();
        assertState(0, 3);
        assertError(post(OUTBOUND_PATH, """
                {"productCode":"PRODUCT-A","quantity":1}
                """), 409, "INSUFFICIENT_STOCK", OUTBOUND_PATH);
        assertState(0, 3);
    }

    @Test
    void insufficientStockReturns409WithoutChangingTimestampOrHistory() throws Exception {
        seedStock(10);
        var before = jdbc.queryForMap("SELECT * FROM inventory");
        var response = post(OUTBOUND_PATH, """
                {"productCode":"PRODUCT-A","quantity":11}
                """);
        assertError(response, 409, "INSUFFICIENT_STOCK", OUTBOUND_PATH);
        assertState(10, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM inventory")).isEqualTo(before);
    }

    @Test
    void unknownProductReturns404ForOutboundAndQueryWithoutCreatingRows() throws Exception {
        assertError(post(OUTBOUND_PATH, """
                {"productCode":"PRODUCT-A","quantity":1}
                """), 404, "PRODUCT_NOT_FOUND", OUTBOUND_PATH);
        assertError(getStock("PRODUCT-A"), 404, "PRODUCT_NOT_FOUND", "/api/v1/products/PRODUCT-A/stock");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
    }

    @Test
    void missingInventoryReturns500ForBothApisWithoutRepairingIt() throws Exception {
        jdbc.update("INSERT INTO product (product_code, name) VALUES (?, ?)", "PRODUCT-A", "상품 A");
        var outbound = post(OUTBOUND_PATH, """
                {"productCode":"PRODUCT-A","quantity":1}
                """);
        var query = getStock("PRODUCT-A");
        assertError(outbound, 500, "INTERNAL_ERROR", OUTBOUND_PATH);
        assertError(query, 500, "INTERNAL_ERROR", "/api/v1/products/PRODUCT-A/stock");
        assertThat(outbound.body()).doesNotContain("SELECT", "inventory", "Exception", "stackTrace");
        assertThat(query.body()).doesNotContain("SELECT", "inventory", "Exception", "stackTrace");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
    }

    @Test
    void longMaxCanBeFullyConsumedThroughHttp() throws Exception {
        seedStock(Long.MAX_VALUE);
        var response = post(OUTBOUND_PATH, """
                {"productCode":"PRODUCT-A","quantity":9223372036854775807}
                """);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(mapper.readTree(response.body()).path("quantityAfter").asLong()).isZero();
        assertState(0, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{bad-json}",
            "{\"productCode\":\"PRODUCT-A\"}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":null}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":0}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":-1}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":1.0}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":1.9}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":\"3\"}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":true}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":9223372036854775808}",
            "{\"productCode\":\"product-a\",\"quantity\":1}",
            "{\"productCode\":\" A\",\"quantity\":1}",
            "{\"productCode\":null,\"quantity\":1}",
            "{\"productCode\":123,\"quantity\":1}",
            "{\"quantity\":1}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":-9223372036854775809}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":1e0}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":[]}",
            "{\"productCode\":\"PRODUCT-A\",\"quantity\":{}}",
            "{\"productCode\":1.5,\"quantity\":1}",
            "{\"productCode\":true,\"quantity\":1}",
            "{\"productCode\":\"\",\"quantity\":1}"
    })
    void invalidOutboundInputReturns400AndPreservesStock(String json) throws Exception {
        seedStock(10);
        assertError(post(OUTBOUND_PATH, json), 400, "INVALID_REQUEST", OUTBOUND_PATH);
        assertState(10, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"product-a", "A.B", "한글", " A", "A ", "A\n"})
    void invalidPathCodeReturns400RatherThanProductNotFound(String code) throws Exception {
        var response = getStock(code);
        assertThat(response.statusCode()).isEqualTo(400);
        var body = mapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isZero();
    }

    @Test
    void pathLengthBoundaryIsValidatedWithoutChangingTheCode() throws Exception {
        String code = "A".repeat(64);
        assertThat(post("/api/v1/stock/inbounds", mapper.writeValueAsString(new InboundPayload(code, "상품", 1)))
                .statusCode()).isEqualTo(201);
        var accepted = getStock(code);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(accepted.body()).path("productCode").asText()).isEqualTo(code);
        var rejected = getStock(code + "A");
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(rejected.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertState(1, 1);
    }

    @Test
    void codeAndQuantityLimitsWorkTogetherAcrossAllThreeApis() throws Exception {
        String code = "A".repeat(64);
        assertThat(post("/api/v1/stock/inbounds", mapper.writeValueAsString(
                new InboundPayload(code, "상품", Long.MAX_VALUE))).statusCode()).isEqualTo(201);
        var rejectedInbound = post("/api/v1/stock/inbounds", mapper.writeValueAsString(
                new InboundPayload(code + "A", "상품", 1)));
        assertError(rejectedInbound, 400, "INVALID_REQUEST", "/api/v1/stock/inbounds");
        var rejectedOutbound = post(OUTBOUND_PATH, mapper.writeValueAsString(
                java.util.Map.of("productCode", code + "A", "quantity", 1)));
        assertError(rejectedOutbound, 400, "INVALID_REQUEST", OUTBOUND_PATH);
        assertState(Long.MAX_VALUE, 1);
        var accepted = post(OUTBOUND_PATH, mapper.writeValueAsString(
                java.util.Map.of("productCode", code, "quantity", Long.MAX_VALUE)));
        assertThat(accepted.statusCode()).isEqualTo(201);
        var query = getStock(code);
        assertThat(query.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(query.body()).path("productCode").asText()).isEqualTo(code);
        assertThat(mapper.readTree(query.body()).path("quantity").asLong()).isZero();
        assertState(0, 2);
    }

    @Test
    void outboundCommitFailureReturns500AndRestoresQuantityAndTimestamp() throws Exception {
        seedStock(10);
        var before = jdbc.queryForMap("SELECT * FROM inventory");
        jdbc.execute("""
                CREATE FUNCTION reject_outbound_http_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Injected outbound HTTP commit failure' USING ERRCODE = 'P0001';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_outbound_http AFTER INSERT ON stock_movement
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_outbound_http_at_commit()
                """);
        var response = post(OUTBOUND_PATH, """
                {"productCode":"PRODUCT-A","quantity":3}
                """);
        assertError(response, 500, "INTERNAL_ERROR", OUTBOUND_PATH);
        assertThat(response.body()).doesNotContain("P0001", "Injected", "stock_movement", "Exception");
        assertState(10, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM inventory")).isEqualTo(before);
    }

    @RepeatedTest(3)
    void waitingHttpOutboundRechecksStockAfterTheEarlierTransactionCommits() throws Exception {
        assertWaitingOutboundAfterEarlierTransaction(true);
    }

    @RepeatedTest(3)
    void waitingHttpOutboundUsesRestoredStockAfterTheEarlierTransactionRollsBack() throws Exception {
        assertWaitingOutboundAfterEarlierTransaction(false);
    }

    private void assertWaitingOutboundAfterEarlierTransaction(boolean commitEarlier) throws Exception {
        seedStock(5);
        var before = jdbc.queryForMap("SELECT * FROM inventory");
        var executor = Executors.newSingleThreadExecutor();
        try (var blocker = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (var statement = blocker.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(rows.next()).isTrue();
                blockerPid = rows.getInt(1);
            }
            long productId;
            java.sql.Timestamp stagedUpdatedAt;
            // JDBC stages an earlier transaction; only the waiting request uses the actual HTTP service path.
            try (var statement = blocker.prepareStatement("""
                    UPDATE inventory SET quantity = 1, updated_at = CURRENT_TIMESTAMP
                    WHERE product_id = (SELECT id FROM product WHERE product_code = 'PRODUCT-A')
                    RETURNING product_id, updated_at
                    """)) {
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    productId = rows.getLong(1);
                    stagedUpdatedAt = rows.getTimestamp(2);
                }
            }
            long stagedMovementId;
            try (var statement = blocker.prepareStatement("""
                    INSERT INTO stock_movement (product_id, movement_type, quantity, quantity_after)
                    VALUES (?, 'OUTBOUND', 4, 1) RETURNING id
                    """)) {
                statement.setLong(1, productId);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    stagedMovementId = rows.getLong(1);
                }
            }
            var snapshotWhileLocked = getStock("PRODUCT-A");
            assertThat(snapshotWhileLocked.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(snapshotWhileLocked.body()).path("quantity").asLong()).isEqualTo(5);
            assertThat(jdbc.queryForMap("SELECT * FROM inventory")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(1);
            var pending = executor.submit(() -> post(OUTBOUND_PATH, """
                    {"productCode":"PRODUCT-A","quantity":3}
                    """));
            awaitOutboundLockWait(blockerPid);
            assertThat(pending.isDone()).isFalse();
            if (commitEarlier) {
                blocker.commit();
            } else {
                blocker.rollback();
            }
            var response = pending.get(10, TimeUnit.SECONDS);
            String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
            assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
            if (commitEarlier) {
                assertError(response, 409, "INSUFFICIENT_STOCK", OUTBOUND_PATH);
                assertState(1, 2);
                assertThat(jdbc.queryForObject("SELECT updated_at FROM inventory", java.sql.Timestamp.class))
                        .isEqualTo(stagedUpdatedAt);
                assertThat(jdbc.queryForObject("SELECT id FROM stock_movement WHERE movement_type = 'OUTBOUND'",
                        Long.class)).isEqualTo(stagedMovementId);
                assertThat(jdbc.queryForObject("SELECT quantity FROM stock_movement WHERE movement_type = 'OUTBOUND'",
                        Long.class)).isEqualTo(4);
                assertThat(jdbc.queryForObject("SELECT quantity_after FROM stock_movement WHERE movement_type = 'OUTBOUND'",
                        Long.class)).isEqualTo(1);
            } else {
                assertThat(response.statusCode()).isEqualTo(201);
                var movement = mapper.readTree(response.body());
                assertThat(movement.size()).isEqualTo(6);
                assertThat(movement.path("productCode").asText()).isEqualTo("PRODUCT-A");
                assertThat(movement.path("movementType").asText()).isEqualTo("OUTBOUND");
                assertThat(movement.path("quantity").asLong()).isEqualTo(3);
                assertThat(movement.path("quantityAfter").asLong()).isEqualTo(2);
                assertState(2, 2);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE id = ?", Long.class,
                        stagedMovementId)).isZero();
                assertThat(jdbc.queryForObject("SELECT id FROM stock_movement WHERE movement_type = 'OUTBOUND'",
                        Long.class)).isEqualTo(movement.path("movementId").asLong());
                assertThat(jdbc.queryForObject("SELECT quantity FROM stock_movement WHERE movement_type = 'OUTBOUND'",
                        Long.class)).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT quantity_after FROM stock_movement WHERE movement_type = 'OUTBOUND'",
                        Long.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT created_at FROM stock_movement WHERE movement_type = 'OUTBOUND'",
                        java.sql.Timestamp.class).toInstant()).isEqualTo(Instant.parse(movement.path("createdAt").asText()));
            }
            var query = getStock("PRODUCT-A");
            assertThat(query.statusCode()).isEqualTo(200);
            var snapshot = mapper.readTree(query.body());
            assertThat(snapshot.path("quantity").asLong()).isEqualTo(commitEarlier ? 1 : 2);
            assertThat(Instant.parse(snapshot.path("updatedAt").asText())).isEqualTo(
                    jdbc.queryForObject("SELECT updated_at FROM inventory", java.sql.Timestamp.class).toInstant());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitOutboundLockWait(int blockerPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            long waitingCount = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                      AND lower(query) LIKE '%from inventory%'
                      AND ? = ANY(pg_blocking_pids(pid))
                    """, Long.class, blockerPid);
            if (waitingCount == 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("HTTP outbound did not wait for the expected PostgreSQL backend within 5 seconds");
    }

    private void seedStock(long quantity) throws Exception {
        var response = post("/api/v1/stock/inbounds", mapper.writeValueAsString(
                new InboundPayload("PRODUCT-A", "상품 A", quantity)));
        assertThat(response.statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getStock(String code) throws Exception {
        String encoded = URLEncoder.encode(code, StandardCharsets.UTF_8).replace("+", "%20");
        var request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/products/" + encoded + "/stock"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code, String path) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = mapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("path").asText()).isEqualTo(path);
        assertThat(body.path("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    private void assertState(long quantity, long movements) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory", Long.class)).isEqualTo(quantity);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(movements);
        assertThat(jdbc.queryForObject("""
                SELECT sum(CASE WHEN movement_type = 'INBOUND' THEN quantity ELSE -quantity END) FROM stock_movement
                """, Long.class)).isEqualTo(quantity);
    }

    private record InboundPayload(String productCode, String name, long quantity) {
    }
}
