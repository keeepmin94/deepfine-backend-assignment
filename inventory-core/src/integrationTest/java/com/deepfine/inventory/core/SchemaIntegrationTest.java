package com.deepfine.inventory.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SchemaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.14");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void clearData() throws SQLException {
        execute("TRUNCATE stock_movement, inventory, product RESTART IDENTITY");
    }

    @Test
    void migrationUsesPostgres16AndCanRunAgainWithoutChanges() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("16.14");
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        }
        var result = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        assertThat(result.migrationsExecuted).isZero();
    }

    @Test
    void nativeInsertsReceiveIdentityTimestampsAndZeroStockDefault() throws SQLException {
        long productId = insertProduct("PRODUCT-A");
        execute("INSERT INTO inventory (product_id) VALUES (" + productId + ")");
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT product.id, product.created_at, product.updated_at,
                               inventory.quantity, inventory.updated_at AS stock_updated_at
                        FROM product JOIN inventory ON inventory.product_id = product.id
                        """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong("id")).isPositive();
            assertThat(rows.getObject("created_at")).isNotNull();
            assertThat(rows.getObject("updated_at")).isNotNull();
            assertThat(rows.getLong("quantity")).isZero();
            assertThat(rows.getObject("stock_updated_at")).isNotNull();
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void productCodeIsUniqueButNameCanBeShared() throws SQLException {
        insertProduct("PRODUCT-A");
        insertProduct("PRODUCT-B");
        assertThatThrownBy(() -> insertProduct("PRODUCT-A"))
                .isInstanceOf(SQLException.class)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo("23505");
    }

    @Test
    void inventoryIsUniqueForEachProduct() throws SQLException {
        long productId = insertProduct("PRODUCT-A");
        execute("INSERT INTO inventory (product_id) VALUES (" + productId + ")");
        assertSqlState("INSERT INTO inventory (product_id) VALUES (" + productId + ")", "23505");
    }

    @Test
    void foreignKeysRejectMissingProductsAndPreventDeletingReferencedProducts() throws SQLException {
        assertSqlState("INSERT INTO inventory (product_id) VALUES (999)", "23503");
        assertSqlState("""
                INSERT INTO stock_movement (product_id, movement_type, quantity, quantity_after)
                VALUES (999, 'INBOUND', 1, 1)
                """, "23503");
        long productId = insertProduct("PRODUCT-A");
        execute("INSERT INTO inventory (product_id) VALUES (" + productId + ")");
        assertSqlState("DELETE FROM product WHERE id = " + productId, "23503");
    }

    @Test
    void inventoryRejectsNegativeQuantityAndAllowsLongMax() throws SQLException {
        long productId = insertProduct("PRODUCT-A");
        assertSqlState("INSERT INTO inventory (product_id, quantity) VALUES (" + productId + ", -1)", "23514");
        execute("INSERT INTO inventory (product_id, quantity) VALUES (" + productId + ", 9223372036854775807)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'INVALID', 1, 1",
            "'INBOUND', 0, 0",
            "'OUTBOUND', -1, 0",
            "'INBOUND', 1, -1"
    })
    void movementChecksRejectInvalidValues(String values) throws SQLException {
        long productId = insertProduct("PRODUCT-A");
        assertSqlState("""
                INSERT INTO stock_movement (product_id, movement_type, quantity, quantity_after)
                VALUES (%d, %s)
                """.formatted(productId, values), "23514");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INBOUND", "OUTBOUND"})
    void validMovementGetsIdentityAndTimestamp(String movementType) throws SQLException {
        long productId = insertProduct("PRODUCT-A");
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO stock_movement (product_id, movement_type, quantity, quantity_after)
                        VALUES (?, ?, 1, 0) RETURNING id, created_at
                        """)) {
            statement.setLong(1, productId);
            statement.setString(2, movementType);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("id")).isPositive();
                assertThat(rows.getObject("created_at")).isNotNull();
            }
        }
    }

    @Test
    void productNameLengthUsesDatabaseCharactersIncludingSupplementaryUnicode() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO product (product_code, name) VALUES (?, ?)")) {
            statement.setString(1, "PRODUCT-A");
            statement.setString(2, "😀".repeat(200));
            assertThat(statement.executeUpdate()).isEqualTo(1);
            statement.setString(1, "PRODUCT-B");
            statement.setString(2, "😀".repeat(201));
            assertThatThrownBy(statement::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo("22001");
        }
    }

    private long insertProduct(String productCode) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO product (product_code, name) VALUES (?, ?) RETURNING id")) {
            statement.setString(1, productCode);
            statement.setString(2, "상품");
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private void assertSqlState(String sql, String expectedState) {
        assertThatThrownBy(() -> execute(sql))
                .isInstanceOf(SQLException.class)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo(expectedState);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
