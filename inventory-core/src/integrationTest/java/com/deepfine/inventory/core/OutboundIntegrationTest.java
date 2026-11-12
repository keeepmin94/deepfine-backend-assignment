package com.deepfine.inventory.core;

import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
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
@SpringBootTest(classes = OutboundIntegrationTest.TestApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.hikari.maximum-pool-size=8",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED"
})
class OutboundIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Product.class)
    @EnableJpaRepositories(basePackageClasses = ProductRepository.class)
    @Import({StockService.class, InventoryQueryService.class, PostgresTransactionRepository.class})
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
    private InventoryQueryService queryService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearData() {
        removeFailureTrigger();
        jdbc.execute("TRUNCATE stock_movement, inventory, product RESTART IDENTITY");
    }

    @AfterEach
    void removeFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_outbound ON stock_movement");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_outbound_after_flush()");
    }

    @Test
    void partialAndFullOutboundPersistPositiveQuantitiesAndFinalStock() {
        inbound("PRODUCT-A", 10);
        var partial = outbound("PRODUCT-A", 3);
        var full = outbound("PRODUCT-A", 7);
        assertThat(partial.movementType()).isEqualTo(MovementType.OUTBOUND);
        assertThat(partial.quantity()).isEqualTo(3);
        assertThat(partial.quantityAfter()).isEqualTo(7);
        assertThat(full.quantity()).isEqualTo(7);
        assertThat(full.quantityAfter()).isZero();
        assertThat(partial.movementId()).isNotEqualTo(full.movementId());
        assertStock("PRODUCT-A", 0, 3);
        assertThat(jdbc.queryForList("SELECT quantity FROM stock_movement WHERE movement_type = 'OUTBOUND' ORDER BY quantity",
                Long.class)).containsExactly(3L, 7L);
    }

    @Test
    void fullOutboundHandlesLongMaxWithoutOverflow() {
        inbound("PRODUCT-A", Long.MAX_VALUE);
        outbound("PRODUCT-A", Long.MAX_VALUE);
        assertStock("PRODUCT-A", 0, 2);
    }

    @Test
    void insufficientStockLeavesInventoryTimestampAndHistoryUnchanged() {
        inbound("PRODUCT-A", 10);
        var before = jdbc.queryForMap("SELECT * FROM inventory");
        assertStockError(() -> outbound("PRODUCT-A", 11), StockErrorCode.INSUFFICIENT_STOCK);
        assertStock("PRODUCT-A", 10, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM inventory")).isEqualTo(before);
    }

    @Test
    void missingProductIsNotFoundForOutboundAndQueryWithoutCreatingRows() {
        assertStockError(() -> outbound("PRODUCT-A", 1), StockErrorCode.PRODUCT_NOT_FOUND);
        assertStockError(() -> queryService.getStock("PRODUCT-A"), StockErrorCode.PRODUCT_NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
    }

    @Test
    void missingInventoryIsInternalErrorForOutboundAndQueryWithoutRepair() {
        jdbc.update("INSERT INTO product (product_code, name) VALUES (?, ?)", "PRODUCT-A", "상품");
        assertStockError(() -> outbound("PRODUCT-A", 1), StockErrorCode.INTERNAL_ERROR);
        assertStockError(() -> queryService.getStock("PRODUCT-A"), StockErrorCode.INTERNAL_ERROR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
    }

    @Test
    void queryUsesOneProjectionSelectAndTheInventoryTimestamp() {
        inbound("PRODUCT-A", 10);
        outbound("PRODUCT-A", 3);
        jdbc.execute("UPDATE product SET updated_at = TIMESTAMPTZ '2000-01-01 00:00:00+00'");
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var snapshot = queryService.getStock("PRODUCT-A");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(snapshot.productId()).isPositive();
        assertThat(snapshot.productCode()).isEqualTo("PRODUCT-A");
        assertThat(snapshot.name()).isEqualTo("상품");
        assertThat(snapshot.quantity()).isEqualTo(7);
        assertThat(snapshot.updatedAt()).isEqualTo(
                jdbc.queryForObject("SELECT updated_at FROM inventory", java.sql.Timestamp.class).toInstant());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(2);
    }

    @Test
    void queryRejectsInvalidCodeBeforeExecutingSql() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertStockError(() -> queryService.getStock("product-a"), StockErrorCode.INVALID_REQUEST);
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    void deferredMovementFailureRollsBackTheOutboundUpdateAndTimestamp() {
        inbound("PRODUCT-A", 10);
        var before = jdbc.queryForMap("SELECT * FROM inventory");
        jdbc.execute("""
                CREATE FUNCTION fail_outbound_after_flush() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF (SELECT quantity FROM inventory WHERE product_id = NEW.product_id) IS DISTINCT FROM NEW.quantity_after THEN
                        RAISE EXCEPTION 'Outbound inventory was not flushed' USING ERRCODE = 'P0002';
                    END IF;
                    RAISE EXCEPTION 'Injected outbound commit failure' USING ERRCODE = 'P0001';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER fail_outbound AFTER INSERT ON stock_movement
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_outbound_after_flush()
                """);
        assertThatThrownBy(() -> outbound("PRODUCT-A", 3)).satisfies(exception -> {
            Throwable cause = exception;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOfSatisfying(SQLException.class,
                    sqlException -> assertThat(sqlException.getSQLState()).isEqualTo("P0001"));
        });
        assertStock("PRODUCT-A", 10, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM inventory")).isEqualTo(before);
    }

    @RepeatedTest(3)
    void concurrentOutboundWithEnoughStockAllSucceed() throws Exception {
        inbound("PRODUCT-A", 24);
        var outcomes = concurrentlyOutbound(3);
        assertThat(outcomes).hasSize(8).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(outcomes).extracting(outcome -> outcome.result().quantityAfter())
                .containsExactlyInAnyOrder(21L, 18L, 15L, 12L, 9L, 6L, 3L, 0L);
        assertStock("PRODUCT-A", 0, 9);
    }

    @RepeatedTest(3)
    void concurrentExcessOutboundOnlyCommitsWhatTheStockAllows() throws Exception {
        inbound("PRODUCT-A", 10);
        var outcomes = concurrentlyOutbound(3);
        var successes = outcomes.stream().filter(outcome -> outcome.failure() == null).toList();
        var failures = outcomes.stream().filter(outcome -> outcome.failure() != null).toList();
        assertThat(successes).hasSize(3);
        assertThat(failures).hasSize(5).allSatisfy(outcome ->
                assertThat(outcome.failure()).isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.INSUFFICIENT_STOCK)));
        assertThat(successes).extracting(outcome -> outcome.result().quantityAfter())
                .containsExactlyInAnyOrder(7L, 4L, 1L);
        assertStock("PRODUCT-A", 1, 4);
    }

    @Test
    void rowLockBlocksTheSameProductButNotQueriesOrOtherProducts() throws Exception {
        inbound("PRODUCT-A", 10);
        inbound("PRODUCT-B", 20);
        var executor = Executors.newFixedThreadPool(3);
        try (var blocker = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("""
                    SELECT product_id FROM inventory
                    WHERE product_id = (SELECT id FROM product WHERE product_code = ?) FOR UPDATE
                    """)) {
                statement.setString(1, "PRODUCT-A");
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            var pending = executor.submit(() -> outbound("PRODUCT-A", 3));
            String lockSql = awaitInventoryLockWait();
            assertThat(lockSql).containsPattern("(?i)for (no key )?update");
            assertThat(pending.isDone()).isFalse();
            var read = executor.submit(() -> queryService.getStock("PRODUCT-A"));
            var otherProduct = executor.submit(() -> outbound("PRODUCT-B", 1));
            assertThat(read.get(3, TimeUnit.SECONDS).quantity()).isEqualTo(10);
            assertThat(otherProduct.get(3, TimeUnit.SECONDS).quantityAfter()).isEqualTo(19);
            assertThat(pending.isDone()).isFalse();
            blocker.commit();
            assertThat(pending.get(10, TimeUnit.SECONDS).quantityAfter()).isEqualTo(7);
            assertStock("PRODUCT-A", 7, 2);
            assertStock("PRODUCT-B", 19, 2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String awaitInventoryLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            List<String> statements = jdbc.queryForList("""
                    SELECT query FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                      AND lower(query) LIKE '%from inventory%'
                    """, String.class);
            if (statements.size() == 1) {
                return statements.getFirst();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Inventory lock wait was not observed within 5 seconds");
    }

    private List<Outcome> concurrentlyOutbound(long quantity) throws Exception {
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<Future<Outcome>>();
        try {
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Outbound concurrent start timed out");
                        }
                        return new Outcome(outbound("PRODUCT-A", quantity), null);
                    } catch (Exception exception) {
                        return new Outcome(null, exception);
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            var outcomes = new ArrayList<Outcome>();
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (Exception exception) {
                    outcomes.add(new Outcome(null, exception));
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

    private void inbound(String code, long quantity) {
        stockService.inbound(new InboundCommand(code, "상품", quantity));
    }

    private StockMovementResult outbound(String code, long quantity) {
        return stockService.outbound(new OutboundCommand(code, quantity));
    }

    private void assertStockError(Runnable action, StockErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(StockException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private void assertStock(String code, long quantity, long movements) {
        assertThat(queryService.getStock(code).quantity()).isEqualTo(quantity);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_movement movement JOIN product ON product.id = movement.product_id
                WHERE product.product_code = ?
                """, Long.class, code)).isEqualTo(movements);
        assertThat(jdbc.queryForObject("""
                SELECT sum(CASE WHEN movement_type = 'INBOUND' THEN quantity ELSE -quantity END)
                FROM stock_movement movement JOIN product ON product.id = movement.product_id
                WHERE product.product_code = ?
                """, Long.class, code)).isEqualTo(quantity);
    }

    private record Outcome(StockMovementResult result, Exception failure) {
    }
}
