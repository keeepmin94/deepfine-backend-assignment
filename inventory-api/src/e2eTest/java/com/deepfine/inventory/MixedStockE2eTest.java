package com.deepfine.inventory;

import com.deepfine.inventory.core.MovementType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        properties = "spring.datasource.hikari.maximum-pool-size=8")
class MixedStockE2eTest {

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
        removeAudit();
        jdbc.execute("TRUNCATE stock_movement, inventory, product RESTART IDENTITY");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        jdbc.execute("""
                CREATE FUNCTION verify_http_movement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF (SELECT quantity FROM inventory WHERE product_id = NEW.product_id) IS DISTINCT FROM NEW.quantity_after THEN
                        RAISE EXCEPTION 'HTTP movement does not match inventory' USING ERRCODE = 'P0001';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER verify_http_movement AFTER INSERT ON stock_movement
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_http_movement()
                """);
    }

    @AfterEach
    void tearDown() {
        client.close();
        removeAudit();
    }

    @RepeatedTest(3)
    void concurrentHttpRegistrationCreatesOneProductAndAllSuccessfulMovements() throws Exception {
        var outcomes = concurrently(Collections.nCopies(8, new Operation(MovementType.INBOUND, 2)));
        assertThat(outcomes).hasSize(8).allSatisfy(outcome -> {
            assertThat(outcome.failure()).isNull();
            assertThat(outcome.response().statusCode()).isEqualTo(201);
        });
        assertResponsesAndDatabase(0, null, outcomes, 16);
    }

    @RepeatedTest(3)
    void concurrentHttpMixedOperationsWithEnoughStockAllSucceed() throws Exception {
        assertMixedScenario(100, 7, 11, 4, 4);
    }

    @RepeatedTest(3)
    void concurrentHttpMixedOperationsRejectEveryImpossibleOutbound() throws Exception {
        assertMixedScenario(2, 1, 7, 0, 0);
    }

    @RepeatedTest(3)
    void concurrentHttpMixedOperationsAccountForOrderDependentShortage() throws Exception {
        assertMixedScenario(6, 1, 3, 2, 3);
    }

    private void assertMixedScenario(long initial, long inboundQuantity, long outboundQuantity,
            int minimumOutboundSuccesses, int maximumOutboundSuccesses) throws Exception {
        var seed = post(new Operation(MovementType.INBOUND, initial));
        assertThat(seed.statusCode()).isEqualTo(201);
        var operations = new ArrayList<Operation>();
        for (int index = 0; index < 4; index++) {
            operations.add(new Operation(MovementType.INBOUND, inboundQuantity));
            operations.add(new Operation(MovementType.OUTBOUND, outboundQuantity));
        }
        var outcomes = concurrently(operations);
        assertThat(outcomes).hasSize(8).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        var inboundOutcomes = outcomes.stream().filter(outcome -> outcome.operation().type() == MovementType.INBOUND).toList();
        var outboundSuccesses = outcomes.stream().filter(outcome -> outcome.operation().type() == MovementType.OUTBOUND
                && outcome.response().statusCode() == 201).toList();
        assertThat(inboundOutcomes).hasSize(4)
                .allSatisfy(outcome -> assertThat(outcome.response().statusCode()).isEqualTo(201));
        assertThat(outboundSuccesses.size()).isBetween(minimumOutboundSuccesses, maximumOutboundSuccesses);
        var failures = outcomes.stream().filter(outcome -> outcome.response().statusCode() != 201).toList();
        assertThat(failures).hasSize(4 - outboundSuccesses.size());
        for (Outcome outcome : failures) {
            assertThat(outcome.operation().type()).isEqualTo(MovementType.OUTBOUND);
            assertThat(outcome.response().statusCode()).isEqualTo(409);
            assertThat(mapper.readTree(outcome.response().body()).path("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
        }
        long expectedQuantity = initial + 4 * inboundQuantity - outboundSuccesses.size() * outboundQuantity;
        assertResponsesAndDatabase(initial, seed, outcomes, expectedQuantity);
    }

    private void assertResponsesAndDatabase(long initial, HttpResponse<String> seed,
            List<Outcome> outcomes, long expectedQuantity) throws Exception {
        var requestIds = new HashSet<String>();
        var movementIds = new ArrayList<Long>();
        long inboundTotal = 0;
        long outboundTotal = 0;
        if (seed != null) {
            assertThat(requestIds.add(requestId(seed))).isTrue();
            movementIds.add(mapper.readTree(seed.body()).path("movementId").asLong());
        }
        for (Outcome outcome : outcomes) {
            assertThat(outcome.failure()).isNull();
            var response = outcome.response();
            String requestId = requestId(response);
            assertThat(requestIds.add(requestId)).isTrue();
            var body = mapper.readTree(response.body());
            if (response.statusCode() == 201) {
                assertThat(body.size()).isEqualTo(6);
                assertThat(body.path("productCode").asText()).isEqualTo("PRODUCT-A");
                assertThat(body.path("movementType").asText()).isEqualTo(outcome.operation().type().name());
                assertThat(body.path("quantity").asLong()).isEqualTo(outcome.operation().quantity());
                assertThat(body.path("quantityAfter").asLong()).isNotNegative();
                long movementId = body.path("movementId").asLong();
                assertThat(movementId).isPositive();
                var stored = jdbc.queryForMap("SELECT * FROM stock_movement WHERE id = ?", movementId);
                assertThat(stored.get("movement_type")).isEqualTo(outcome.operation().type().name());
                assertThat(stored.get("quantity")).isEqualTo(outcome.operation().quantity());
                assertThat(stored.get("quantity_after")).isEqualTo(body.path("quantityAfter").asLong());
                assertThat(body.path("createdAt").asText()).endsWith("Z");
                assertThat(((java.sql.Timestamp) stored.get("created_at")).toInstant())
                        .isEqualTo(Instant.parse(body.path("createdAt").asText()));
                movementIds.add(movementId);
                if (outcome.operation().type() == MovementType.INBOUND) {
                    inboundTotal += outcome.operation().quantity();
                } else {
                    outboundTotal += outcome.operation().quantity();
                }
            } else {
                assertThat(response.statusCode()).isEqualTo(409);
                assertThat(body.size()).isEqualTo(4);
                assertThat(body.path("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
                assertThat(body.path("requestId").asText()).isEqualTo(requestId);
                assertThat(body.path("path").asText()).isEqualTo("/api/v1/stock/outbounds");
            }
        }
        assertThat(initial + inboundTotal - outboundTotal).isEqualTo(expectedQuantity);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory", Long.class)).isEqualTo(expectedQuantity);
        assertThat(jdbc.queryForList("SELECT id FROM stock_movement", Long.class))
                .containsExactlyInAnyOrderElementsOf(movementIds);
        assertThat(jdbc.queryForObject("""
                SELECT sum(CASE WHEN movement_type = 'INBOUND' THEN quantity ELSE -quantity END) FROM stock_movement
                """, Long.class)).isEqualTo(expectedQuantity);
        var query = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/products/PRODUCT-A/stock"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(query, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(requestIds.add(requestId(response))).isTrue();
        var snapshot = mapper.readTree(response.body());
        assertThat(snapshot.path("quantity").asLong()).isEqualTo(expectedQuantity);
        assertThat(snapshot.path("name").asText()).isEqualTo("상품");
        assertThat(Instant.parse(snapshot.path("updatedAt").asText())).isEqualTo(
                jdbc.queryForObject("SELECT updated_at FROM inventory", java.sql.Timestamp.class).toInstant());
    }

    private List<Outcome> concurrently(List<Operation> operations) throws Exception {
        var ready = new CountDownLatch(operations.size());
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(operations.size());
        var futures = new ArrayList<Future<Outcome>>();
        try {
            for (Operation operation : operations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Mixed HTTP start timed out");
                        }
                        return new Outcome(operation, post(operation), null);
                    } catch (Exception exception) {
                        return new Outcome(operation, null, exception);
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            var outcomes = new ArrayList<Outcome>();
            for (int index = 0; index < futures.size(); index++) {
                try {
                    outcomes.add(futures.get(index).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (Exception exception) {
                    outcomes.add(new Outcome(operations.get(index), null, exception));
                }
            }
            return outcomes;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private HttpResponse<String> post(Operation operation) throws Exception {
        boolean inbound = operation.type() == MovementType.INBOUND;
        String path = inbound ? "/api/v1/stock/inbounds" : "/api/v1/stock/outbounds";
        Map<String, Object> payload = inbound
                ? Map.of("productCode", "PRODUCT-A", "name", "상품", "quantity", operation.quantity())
                : Map.of("productCode", "PRODUCT-A", "quantity", operation.quantity());
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String requestId(HttpResponse<String> response) {
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
        return requestId;
    }

    private void removeAudit() {
        jdbc.execute("DROP TRIGGER IF EXISTS verify_http_movement ON stock_movement");
        jdbc.execute("DROP FUNCTION IF EXISTS verify_http_movement()");
    }

    private record Operation(MovementType type, long quantity) {
    }

    private record Outcome(Operation operation, HttpResponse<String> response, Exception failure) {
    }
}
