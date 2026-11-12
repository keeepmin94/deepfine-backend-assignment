package com.deepfine.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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
        properties = "inventory.lock-timeout-ms=10000")
class DeadlockE2eTest {

    private static final long GATE_KEY = 42424004L;

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
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private HttpClient client;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE stock_movement, inventory, product RESTART IDENTITY");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        client.close();
        jdbc.execute("DROP TRIGGER IF EXISTS force_http_deadlock ON stock_movement");
        jdbc.execute("DROP FUNCTION IF EXISTS force_http_deadlock()");
    }

    @RepeatedTest(3)
    void actualDatabaseDeadlockAbortsTheHttpTransactionAndReturns503WithoutPartialWrites() throws Exception {
        assertThat(post("/api/v1/stock/inbounds", """
                {"productCode":"PRODUCT-A","name":"상품","quantity":10}
                """).statusCode()).isEqualTo(201);
        assertThat(post("/api/v1/stock/inbounds", """
                {"productCode":"PRODUCT-B","name":"상품","quantity":20}
                """).statusCode()).isEqualTo(201);
        var before = jdbc.queryForList("SELECT * FROM inventory ORDER BY product_id");
        jdbc.execute("""
                CREATE FUNCTION force_http_deadlock() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.product_id = (SELECT id FROM product WHERE product_code = 'PRODUCT-A') THEN
                        PERFORM pg_advisory_xact_lock(42424004);
                        PERFORM product_id FROM inventory
                        WHERE product_id = (SELECT id FROM product WHERE product_code = 'PRODUCT-B') FOR UPDATE;
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER force_http_deadlock BEFORE INSERT ON stock_movement
                FOR EACH ROW EXECUTE FUNCTION force_http_deadlock()
                """);
        var executor = Executors.newFixedThreadPool(2);
        try (var gate = connect(); var blocker = connect()) {
            try (var statement = gate.prepareStatement("SELECT pg_advisory_lock(?)")) {
                statement.setLong(1, GATE_KEY);
                statement.execute();
            }
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("SELECT set_config('deadlock_timeout', '1h', true)");
            }
            int blockerPid;
            try (var statement = blocker.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(rows.next()).isTrue();
                blockerPid = rows.getInt(1);
            }
            lockInventory(blocker, "PRODUCT-B");
            var http = executor.submit(() -> post("/api/v1/stock/outbounds", """
                    {"productCode":"PRODUCT-A","quantity":3}
                    """));
            int httpPid = awaitHttpGateWait();
            var competingLock = executor.submit(() -> {
                lockInventory(blocker, "PRODUCT-A");
                return true;
            });
            awaitBlockerWait(blockerPid, httpPid);
            try (var statement = gate.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1, GATE_KEY);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isTrue();
                }
            }
            var failures = new ArrayList<Exception>();
            HttpResponse<String> response = null;
            try {
                response = http.get(15, TimeUnit.SECONDS);
            } catch (Exception exception) {
                failures.add(exception);
            }
            try {
                assertThat(competingLock.get(5, TimeUnit.SECONDS)).isTrue();
            } catch (Exception exception) {
                failures.add(exception);
            }
            blocker.rollback();
            assertThat(failures).isEmpty();
            assertThat(response).isNotNull();
            assertThat(response.statusCode()).isEqualTo(503);
            var body = mapper.readTree(response.body());
            assertThat(body.path("code").asText()).isEqualTo("TRANSACTION_RETRY_REQUIRED");
            String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
            assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
            assertThat(body.path("requestId").asText()).isEqualTo(requestId);
            assertThat(body.path("path").asText()).isEqualTo("/api/v1/stock/outbounds");
            assertThat(response.body()).doesNotContain("40P01", "SELECT", "deadlock detected", "Exception", "stock_movement");
            assertThat(jdbc.queryForList("SELECT * FROM inventory ORDER BY product_id")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        jdbc.execute("DROP TRIGGER force_http_deadlock ON stock_movement");
        assertThat(post("/api/v1/stock/outbounds", """
                {"productCode":"PRODUCT-A","quantity":3}
                """).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("""
                SELECT quantity FROM inventory JOIN product ON product.id = inventory.product_id
                WHERE product_code = 'PRODUCT-A'
                """, Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(3);
    }

    private int awaitHttpGateWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var pids = jdbc.queryForList("""
                    SELECT pid FROM pg_stat_activity WHERE datname = current_database()
                    AND wait_event_type = 'Lock' AND wait_event = 'advisory'
                    """, Integer.class);
            if (pids.size() == 1) {
                return pids.getFirst();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("HTTP transaction did not reach the deadlock gate");
    }

    private void awaitBlockerWait(int blockerPid, int httpPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",
                    Boolean.class, httpPid, blockerPid))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Competing transaction did not wait for the HTTP transaction");
    }

    private void lockInventory(Connection connection, String code) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT product_id FROM inventory
                WHERE product_id = (SELECT id FROM product WHERE product_code = ?) FOR UPDATE
                """)) {
            statement.setString(1, code);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
