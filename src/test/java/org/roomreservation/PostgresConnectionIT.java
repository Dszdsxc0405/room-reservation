package org.roomreservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PostgresConnectionIT {

    @Autowired
    private DataSource dataSource;

    @Value("${test.database.name}")
    private String expectedDatabase;

    @Test
    void connectsToTheDedicatedPostgresTestDatabase() throws Exception {
        assertTrue(expectedDatabase.equals("rr_ci") || expectedDatabase.endsWith("_test"),
                "Integration tests require a dedicated test database name");
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT current_database()")) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(expectedDatabase, connection.getCatalog());
            result.next();
            assertEquals(expectedDatabase, result.getString(1));
        }
    }
}
