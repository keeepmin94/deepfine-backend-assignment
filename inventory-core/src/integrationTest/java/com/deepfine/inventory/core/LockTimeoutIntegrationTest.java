package com.deepfine.inventory.core;

import com.deepfine.inventory.core.dto.command.InboundCommand;
import com.deepfine.inventory.core.dto.command.OutboundCommand;
import com.deepfine.inventory.core.entity.Product;
import com.deepfine.inventory.core.repository.PostgresTransactionRepository;
import com.deepfine.inventory.core.repository.ProductRepository;
import com.deepfine.inventory.core.service.StockService;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = LockTimeoutIntegrationTest.TestApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED"
})
class LockTimeoutIntegrationTest {

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
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    private PostgresTransactionRepository transactionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearData() {
        jdbc.execute("TRUNCATE stock_movement, inventory, product RESTART IDENTITY");
    }

    @Test
    void defaultTimeoutIsThreeSecondsAndCommitRestoresTheSameConnection() {
        int backendPid = backendPid();
        assertThat(timeout()).isEqualTo("0");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            transactionRepository.applyLockTimeout();
            assertThat(timeout()).isEqualTo("3s");
            assertThat(backendPid()).isEqualTo(backendPid);
        });
        assertThat(backendPid()).isEqualTo(backendPid);
        assertThat(timeout()).isEqualTo("0");
    }

    @Test
    void rollbackAlsoRestoresTheSamePooledConnection() {
        int backendPid = backendPid();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            transactionRepository.applyLockTimeout();
            assertThat(timeout()).isEqualTo("3s");
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("test rollback");
        assertThat(backendPid()).isEqualTo(backendPid);
        assertThat(timeout()).isEqualTo("0");
    }

    @Test
    void rowLockTimeoutRollsBackAndDoesNotLeakIntoTheNextPoolUser() throws Exception {
        stockService.inbound(new InboundCommand("PRODUCT-A", "상품", 10));
        int backendPid = backendPid();
        var before = jdbc.queryForMap("SELECT * FROM inventory");
        try (var blocker = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement();
                    var rows = statement.executeQuery("SELECT product_id FROM inventory FOR UPDATE")) {
                assertThat(rows.next()).isTrue();
            }
            Throwable failure = failureOf(() -> stockService.outbound(new OutboundCommand("PRODUCT-A", 3)));
            assertLockTimeout(failure);
            assertThat(backendPid()).isEqualTo(backendPid);
            assertThat(timeout()).isEqualTo("0");
            assertThat(jdbc.queryForMap("SELECT * FROM inventory")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(1);
            blocker.rollback();
        }
        var retry = stockService.outbound(new OutboundCommand("PRODUCT-A", 3));
        assertThat(retry.quantityAfter()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(2);
        assertThat(timeout()).isEqualTo("0");
    }

    @Test
    void newRegistrationConflictAlsoUsesTheTimeoutBeforeAnyInsertCanWait() throws Exception {
        int backendPid = backendPid();
        try (var blocker = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("INSERT INTO product (product_code, name) VALUES (?, ?)")) {
                statement.setString(1, "PRODUCT-A");
                statement.setString(2, "상품");
                statement.executeUpdate();
            }
            Throwable failure = failureOf(() -> stockService.inbound(new InboundCommand("PRODUCT-A", "상품", 5)));
            assertLockTimeout(failure);
            assertThat(backendPid()).isEqualTo(backendPid);
            assertThat(timeout()).isEqualTo("0");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
            blocker.rollback();
        }
        assertThat(stockService.inbound(new InboundCommand("PRODUCT-A", "상품", 5)).quantityAfter()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isEqualTo(1);
    }

    private Throwable failureOf(Callable<?> operation) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(() -> {
                try {
                    operation.call();
                    return null;
                } catch (Exception exception) {
                    return exception;
                }
            }).get(8, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertLockTimeout(Throwable failure) {
        assertThat(failure).isInstanceOf(DataAccessException.class);
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOfSatisfying(SQLException.class,
                exception -> assertThat(exception.getSQLState()).isEqualTo("55P03"));
    }

    private int backendPid() {
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private String timeout() {
        return jdbc.queryForObject("SHOW lock_timeout", String.class);
    }
}
