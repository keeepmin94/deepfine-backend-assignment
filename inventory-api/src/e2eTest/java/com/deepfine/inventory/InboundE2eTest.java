package com.deepfine.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class InboundE2eTest {

    private static final String PATH = "/api/v1/stock/inbounds";

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
        jdbc.execute("DROP TRIGGER IF EXISTS reject_http_movement ON stock_movement");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_http_movement_at_commit()");
    }

    @Test
    void inboundReturns201AndPersistsTheResponseValues() throws Exception {
        var response = post("""
                {"productCode":"PRODUCT-A","name":"  상품 A  ","quantity":10}
                """);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.path("productCode").asText()).isEqualTo("PRODUCT-A");
        assertThat(body.path("movementType").asText()).isEqualTo("INBOUND");
        assertThat(body.path("quantity").asLong()).isEqualTo(10);
        assertThat(body.path("quantityAfter").asLong()).isEqualTo(10);
        assertThat(body.path("movementId").asLong()).isPositive();
        assertThat(body.path("createdAt").asText()).endsWith("Z");
        Instant createdAt = Instant.parse(body.path("createdAt").asText());
        assertThat(jdbc.queryForObject("SELECT created_at FROM stock_movement", java.sql.Timestamp.class).toInstant())
                .isEqualTo(createdAt);
        assertThat(jdbc.queryForObject("SELECT id FROM stock_movement", Long.class))
                .isEqualTo(body.path("movementId").asLong());
        assertThat(jdbc.queryForObject("SELECT name FROM product", String.class)).isEqualTo("상품 A");
        assertState(10, 1);
    }

    @Test
    void repeatingTheSameRequestProducesTwoMovements() throws Exception {
        String request = """
                {"productCode":"PRODUCT-A","name":"상품 A","quantity":10}
                """;
        var first = post(request);
        var second = post(request);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        var firstBody = mapper.readTree(first.body());
        var secondBody = mapper.readTree(second.body());
        assertThat(firstBody.path("movementId").asLong()).isNotEqualTo(secondBody.path("movementId").asLong());
        assertThat(firstBody.path("quantityAfter").asLong()).isEqualTo(10);
        assertThat(secondBody.path("quantityAfter").asLong()).isEqualTo(20);
        assertState(20, 2);
    }

    @Test
    void nameMismatchReturns409WithoutChangingStock() throws Exception {
        assertThat(post("""
                {"productCode":"PRODUCT-A","name":"상품 A","quantity":10}
                """).statusCode()).isEqualTo(201);
        var response = post("""
                {"productCode":"PRODUCT-A","name":"다른 상품","quantity":3}
                """);
        assertError(response, 409, "PRODUCT_NAME_MISMATCH");
        assertState(10, 1);
        assertThat(jdbc.queryForObject("SELECT name FROM product", String.class)).isEqualTo("상품 A");
    }

    @Test
    void overflowReturns409WithoutAddingHistory() throws Exception {
        assertThat(post("""
                {"productCode":"PRODUCT-A","name":"상품 A","quantity":9223372036854775807}
                """).statusCode()).isEqualTo(201);
        var response = post("""
                {"productCode":"PRODUCT-A","name":"상품 A","quantity":1}
                """);
        assertError(response, 409, "STOCK_LIMIT_EXCEEDED");
        assertState(Long.MAX_VALUE, 1);
    }

    @Test
    void codePointLengthIsEnforcedOverRealHttp() throws Exception {
        String name = "😀".repeat(200);
        var accepted = post(mapper.writeValueAsString(new Payload("A".repeat(64), name, 1)));
        assertThat(accepted.statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT name FROM product", String.class)).isEqualTo(name);
        var rejected = post(mapper.writeValueAsString(new Payload("PRODUCT-B", name + "😀", 1)));
        assertError(rejected, 400, "INVALID_REQUEST");
        assertState(1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "null",
            "[]",
            "{bad-json}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"상품\"}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"상품\",\"quantity\":null}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"상품\",\"quantity\":0}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"상품\",\"quantity\":-1}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"상품\",\"quantity\":1.9}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"상품\",\"quantity\":1.0}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"상품\",\"quantity\":\"10\"}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"상품\",\"quantity\":true}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"상품\",\"quantity\":9223372036854775808}",
            "{\"productCode\":\"product-a\",\"name\":\"상품\",\"quantity\":1}",
            "{\"productCode\":\" A\",\"name\":\"상품\",\"quantity\":1}",
            "{\"productCode\":\"한글\",\"name\":\"상품\",\"quantity\":1}",
            "{\"productCode\":null,\"name\":\"상품\",\"quantity\":1}",
            "{\"productCode\":123,\"name\":\"상품\",\"quantity\":1}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":null,\"quantity\":1}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"  \",\"quantity\":1}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":123,\"quantity\":1}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"\\u0000\",\"quantity\":1}",
            "{\"productCode\":\"PRODUCT-A\",\"name\":\"\\uD800\",\"quantity\":1}"
    })
    void invalidInputReturns400AndLeavesNoRows(String json) throws Exception {
        assertError(post(json), 400, "INVALID_REQUEST");
        assertEmptyDatabase();
    }

    @Test
    void missingInventoryReturns500WithoutExposingInternalsOrRepairingData() throws Exception {
        jdbc.update("INSERT INTO product (product_code, name) VALUES (?, ?)", "PRODUCT-A", "상품 A");
        var response = post("""
                {"productCode":"PRODUCT-A","name":"상품 A","quantity":1}
                """);
        assertError(response, 500, "INTERNAL_ERROR");
        assertThat(response.body()).doesNotContain("SQLException", "SELECT", "inventory", "stackTrace", "Exception");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
    }

    @Test
    void commitFailureReturns500AndRollsBackInsteadOfReturning201() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION reject_http_movement_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Injected HTTP commit failure' USING ERRCODE = 'P0001';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_http_movement AFTER INSERT ON stock_movement
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_http_movement_at_commit()
                """);
        var response = post("""
                {"productCode":"PRODUCT-A","name":"상품 A","quantity":10}
                """);
        assertError(response, 500, "INTERNAL_ERROR");
        assertThat(response.body()).doesNotContain("P0001", "Injected", "stock_movement", "Exception");
        assertEmptyDatabase();
    }

    @Test
    void unsupportedMethodKeeps405RatherThanBecoming500() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertError(response, 405, "INVALID_REQUEST");
        assertThat(response.headers().firstValue("Allow")).hasValue("POST");
        assertEmptyDatabase();
    }

    @Test
    void unsupportedMediaTypeKeeps415RatherThanBecoming500() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("not-json"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertError(response, 415, "INVALID_REQUEST");
        assertEmptyDatabase();
    }

    private HttpResponse<String> post(String json) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = mapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("path").asText()).isEqualTo(PATH);
        assertThat(body.path("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    private void assertState(long quantity, long movements) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory", Long.class)).isEqualTo(quantity);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(movements);
    }

    private void assertEmptyDatabase() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
    }

    private record Payload(String productCode, String name, long quantity) {
    }
}
