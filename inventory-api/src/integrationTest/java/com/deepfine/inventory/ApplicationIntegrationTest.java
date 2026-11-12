package com.deepfine.inventory;

import java.sql.Connection;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.14");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    void applicationStartsWithMigratedEmptyPostgresSchema() throws Exception {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        flyway.validate();
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("16.14");
        }
    }
}
