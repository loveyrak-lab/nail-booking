package com.teera.nail_booking.reservation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Proves the database itself rejects overlapping bookings (the no_overlap
 * exclusion constraint), independent of any Java check.
 *
 * Needs Docker running. Starts a throw-away PostgreSQL 16, applies the real
 * Flyway migrations (V1..V5), then inserts rows with plain JDBC.
 */
class NoOverlapConstraintTest {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:16")
            .withEnv("POSTGRES_DB", "test")
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private static String url;

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
        url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/test";
        Flyway.configure()
                .dataSource(url, "test", "test")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void cleanTable() throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("DELETE FROM booking")) {
            ps.executeUpdate();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "test", "test");
    }

    /** Same shape as the staff "block time" insert, so no customer row is needed. */
    private static void insertBlock(String start, String end) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO booking (customer_id, service_id, start_time, end_time, status, source, note)
                     VALUES (NULL, NULL, ?, ?, 'BLOCKED', 'STAFF', 'test')
                     """)) {
            ps.setObject(1, OffsetDateTime.parse(start));
            ps.setObject(2, OffsetDateTime.parse(end));
            ps.executeUpdate();
        }
    }

    @Test
    void overlappingRangeIsRejectedByTheDatabase() throws SQLException {
        insertBlock("2026-12-01T10:00:00+07:00", "2026-12-01T11:00:00+07:00");

        SQLException e = assertThrows(SQLException.class,
                () -> insertBlock("2026-12-01T10:30:00+07:00", "2026-12-01T11:30:00+07:00"));

        assertTrue(String.valueOf(e.getMessage()).contains("no_overlap"),
                "error should name the no_overlap constraint, got: " + e.getMessage());
    }

    @Test
    void backToBackRangesAreAllowed() throws SQLException {
        insertBlock("2026-12-01T10:00:00+07:00", "2026-12-01T11:00:00+07:00");

        // starts exactly when the first one ends: ranges are half-open
        assertDoesNotThrow(() -> insertBlock("2026-12-01T11:00:00+07:00", "2026-12-01T12:00:00+07:00"));
    }

    @Test
    void cancelledRowNoLongerHoldsItsTime() throws SQLException {
        insertBlock("2026-12-01T10:00:00+07:00", "2026-12-01T11:00:00+07:00");

        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("UPDATE booking SET status = 'CANCELLED'")) {
            ps.executeUpdate();
        }

        assertDoesNotThrow(() -> insertBlock("2026-12-01T10:00:00+07:00", "2026-12-01T11:00:00+07:00"));
    }
}
