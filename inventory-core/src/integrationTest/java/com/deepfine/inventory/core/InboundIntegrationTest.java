package com.deepfine.inventory.core;

import com.deepfine.inventory.core.dto.command.InboundCommand;
import com.deepfine.inventory.core.dto.result.StockMovementResult;
import com.deepfine.inventory.core.entity.Product;
import com.deepfine.inventory.core.enums.MovementType;
import com.deepfine.inventory.core.enums.StockErrorCode;
import com.deepfine.inventory.core.exception.StockException;
import com.deepfine.inventory.core.repository.PostgresTransactionRepository;
import com.deepfine.inventory.core.repository.ProductRepository;
import com.deepfine.inventory.core.service.StockService;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = InboundIntegrationTest.TestApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=8",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED"
})
class InboundIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Product.class)
    @EnableJpaRepositories(basePackageClasses = ProductRepository.class)
    @Import({StockService.class, PostgresTransactionRepository.class})
    static class TestApplication {
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.14");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearData() {
        removeFailureTrigger();
        jdbc.execute("TRUNCATE stock_movement, inventory, product RESTART IDENTITY");
    }

    @AfterEach
    void removeFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_movement ON stock_movement");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_movement_after_flush()");
    }

    @Test
    void newAndExistingInboundPersistMatchingHistory() {
        var first = inbound("PRODUCT-A", "  상품 A  ", 10);
        var second = inbound("PRODUCT-A", "상품 A", 3);
        assertThat(first.movementType()).isEqualTo(MovementType.INBOUND);
        assertThat(first.quantity()).isEqualTo(10);
        assertThat(first.quantityAfter()).isEqualTo(10);
        assertThat(second.quantityAfter()).isEqualTo(13);
        assertThat(second.movementId()).isNotEqualTo(first.movementId());
        assertThat(first.createdAt()).isNotNull();
        assertState(13, 2);
        assertThat(jdbc.queryForObject("SELECT name FROM product", String.class)).isEqualTo("상품 A");
        assertThat(jdbc.queryForList("SELECT quantity_after FROM stock_movement ORDER BY quantity_after", Long.class))
                .containsExactly(10L, 13L);
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM stock_movement", Long.class)).isEqualTo(13);
    }

    @Test
    void differentCodesCanShareANameAndUnicodeNamesSurviveRoundTrip() {
        String name = "😀".repeat(200);
        inbound("PRODUCT-A", name, 1);
        inbound("PRODUCT-B", name, 1);
        assertThat(jdbc.queryForList("SELECT name FROM product", String.class)).containsExactly(name, name);
        assertThat(count("inventory")).isEqualTo(2);
        assertThat(count("stock_movement")).isEqualTo(2);
    }

    @Test
    void nameMismatchDoesNotChangeProductInventoryOrHistory() {
        inbound("PRODUCT-A", "상품 A", 10);
        var before = jdbc.queryForMap("SELECT * FROM inventory");
        assertThatThrownBy(() -> inbound("PRODUCT-A", "다른 상품", 3))
                .isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.PRODUCT_NAME_MISMATCH));
        assertState(10, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM inventory")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT name FROM product", String.class)).isEqualTo("상품 A");
    }

    @Test
    void overflowRollsBackWithoutAddingHistory() {
        inbound("PRODUCT-A", "상품 A", Long.MAX_VALUE);
        assertThatThrownBy(() -> inbound("PRODUCT-A", "상품 A", 1))
                .isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.STOCK_LIMIT_EXCEEDED));
        assertState(Long.MAX_VALUE, 1);
    }

    @Test
    void missingInventoryIsAnErrorRatherThanAutomaticRepair() {
        jdbc.update("INSERT INTO product (product_code, name) VALUES (?, ?)", "PRODUCT-A", "상품 A");
        assertThatThrownBy(() -> inbound("PRODUCT-A", "상품 A", 1))
                .isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.INTERNAL_ERROR));
        assertThat(count("product")).isEqualTo(1);
        assertThat(count("inventory")).isZero();
        assertThat(count("stock_movement")).isZero();
    }

    @Test
    void commitTimeMovementFailureRollsBackExistingInventory() {
        inbound("PRODUCT-A", "상품 A", 10);
        var before = jdbc.queryForMap("SELECT * FROM inventory");
        installFailureTrigger();
        assertInjectedFailure(() -> inbound("PRODUCT-A", "상품 A", 3));
        assertState(10, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM inventory")).isEqualTo(before);
    }

    @Test
    void commitTimeMovementFailureLeavesNoNewProductInventoryOrHistory() {
        installFailureTrigger();
        assertInjectedFailure(() -> inbound("PRODUCT-A", "상품 A", 10));
        assertThat(count("product")).isZero();
        assertThat(count("inventory")).isZero();
        assertThat(count("stock_movement")).isZero();
    }

    @RepeatedTest(3)
    void concurrentNewRegistrationCreatesOneProductAndInventory() throws Exception {
        var commands = IntStream.range(0, 8)
                .mapToObj(index -> new InboundCommand("PRODUCT-A", "상품 A", 2)).toList();
        var outcomes = concurrently(commands);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(outcomes).extracting(outcome -> outcome.result().quantityAfter())
                .containsExactlyInAnyOrder(2L, 4L, 6L, 8L, 10L, 12L, 14L, 16L);
        assertState(16, 8);
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM stock_movement", Long.class)).isEqualTo(16);
    }

    @RepeatedTest(3)
    void competingNamesOnlyAllowTheRegisteredName() throws Exception {
        var commands = IntStream.range(0, 8)
                .mapToObj(index -> new InboundCommand("PRODUCT-A", index % 2 == 0 ? "상품 A" : "상품 B", 2))
                .toList();
        var outcomes = concurrently(commands);
        String registeredName = jdbc.queryForObject("SELECT name FROM product", String.class);
        long successes = outcomes.stream().filter(outcome -> outcome.failure() == null).count();
        assertThat(successes).isEqualTo(4);
        for (Outcome outcome : outcomes) {
            if (outcome.command().name().equals(registeredName)) {
                assertThat(outcome.failure()).isNull();
                assertThat(outcome.result()).isNotNull();
            } else {
                assertThat(outcome.failure()).isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.PRODUCT_NAME_MISMATCH));
            }
        }
        assertState(8, 4);
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM stock_movement", Long.class)).isEqualTo(8);
    }

    @RepeatedTest(3)
    void concurrentExistingInboundDoesNotLoseUpdates() throws Exception {
        inbound("PRODUCT-A", "상품 A", 10);
        var commands = IntStream.range(0, 8)
                .mapToObj(index -> new InboundCommand("PRODUCT-A", "상품 A", 2)).toList();
        var outcomes = concurrently(commands);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(outcomes).extracting(outcome -> outcome.result().quantityAfter())
                .containsExactlyInAnyOrder(12L, 14L, 16L, 18L, 20L, 22L, 24L, 26L);
        assertState(26, 9);
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM stock_movement", Long.class)).isEqualTo(26);
    }

    @ParameterizedTest
    @ValueSource(strings = {"상품 A", "상품 B"})
    void uncommittedRegistrationWaitsThenUsesAFreshSelect(String requestedName) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var blocker = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            long productId;
            try (var statement = blocker.prepareStatement(
                    "INSERT INTO product (product_code, name) VALUES (?, ?) RETURNING id")) {
                statement.setString(1, "PRODUCT-A");
                statement.setString(2, "상품 A");
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    productId = rows.getLong(1);
                }
            }
            try (var statement = blocker.prepareStatement("INSERT INTO inventory (product_id) VALUES (?)")) {
                statement.setLong(1, productId);
                statement.executeUpdate();
            }
            var command = new InboundCommand("PRODUCT-A", requestedName, 5);
            var pending = executor.submit(() -> {
                try {
                    return new Outcome(command, stockService.inbound(command), null);
                } catch (Exception exception) {
                    return new Outcome(command, null, exception);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                long waitingCount = jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                          AND query LIKE '%ON CONFLICT (product_code)%'
                        """, Long.class);
                if (waitingCount == 1) {
                    waiting = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(waiting).isTrue();
            assertThat(pending.isDone()).isFalse();
            blocker.commit();
            Outcome outcome = pending.get(10, TimeUnit.SECONDS);
            if (requestedName.equals("상품 A")) {
                assertThat(outcome.failure()).isNull();
                assertThat(outcome.result().quantityAfter()).isEqualTo(5);
                assertState(5, 1);
            } else {
                assertThat(outcome.failure()).isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.PRODUCT_NAME_MISMATCH));
                assertState(0, 0);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private StockMovementResult inbound(String code, String name, long quantity) {
        return stockService.inbound(new InboundCommand(code, name, quantity));
    }

    private List<Outcome> concurrently(List<InboundCommand> commands) throws Exception {
        var ready = new CountDownLatch(commands.size());
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(commands.size());
        var futures = new ArrayList<Future<Outcome>>();
        try {
            for (InboundCommand command : commands) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent start timed out");
                        }
                        return new Outcome(command, stockService.inbound(command), null);
                    } catch (Exception exception) {
                        return new Outcome(command, null, exception);
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
                    outcomes.add(new Outcome(commands.get(index), null, exception));
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

    private void installFailureTrigger() {
        jdbc.execute("""
                CREATE FUNCTION fail_movement_after_flush() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF (SELECT quantity FROM inventory WHERE product_id = NEW.product_id) IS DISTINCT FROM NEW.quantity_after THEN
                        RAISE EXCEPTION 'Inventory was not flushed before failure injection' USING ERRCODE = 'P0002';
                    END IF;
                    RAISE EXCEPTION 'Injected movement failure at commit' USING ERRCODE = 'P0001';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER fail_movement AFTER INSERT ON stock_movement
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_movement_after_flush()
                """);
    }

    private void assertInjectedFailure(Runnable action) {
        assertThatThrownBy(action::run).satisfies(exception -> {
            Throwable cause = exception;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOfSatisfying(SQLException.class,
                    sqlException -> assertThat(sqlException.getSQLState()).isEqualTo("P0001"));
        });
    }

    private void assertState(long quantity, long movements) {
        assertThat(count("product")).isEqualTo(1);
        assertThat(count("inventory")).isEqualTo(1);
        assertThat(count("stock_movement")).isEqualTo(movements);
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory", Long.class)).isEqualTo(quantity);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private record Outcome(InboundCommand command, StockMovementResult result, Exception failure) {
    }
}
