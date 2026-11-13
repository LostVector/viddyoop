package com.rkuo.viddyoop.core.catalog;

import java.nio.file.Path;
import java.sql.*;

/** Durable capacity allocations, not workflow state. Actual free space also counts retained files. */
public final class ScratchReservations {
    private final String url;
    public ScratchReservations(Path database) throws SQLException {
        url = "jdbc:sqlite:" + database.toAbsolutePath();
        try (Connection c = connect(); Statement s = c.createStatement()) { createTable(s); }
    }

    static void createTable(Statement s) throws SQLException {
        s.executeUpdate("""
            CREATE TABLE IF NOT EXISTS scratch_reservations (
              submission_id TEXT PRIMARY KEY, bytes INTEGER NOT NULL CHECK(bytes>=0),
              released INTEGER NOT NULL DEFAULT 0 CHECK(released IN (0,1)))
            """);
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        catch (SQLException e) { c.close(); throw e; }
        return c;
    }

    public static long estimate(long sourceBytes, int multiplier) {
        if (sourceBytes < 0 || multiplier < 1) throw new IllegalArgumentException("Invalid scratch estimate");
        return Math.multiplyExact(sourceBytes, multiplier);
    }

    /** Idempotent across the reserve/start/acknowledge gaps. Serializes competing admissions. */
    public boolean reserve(String submissionId, long bytes, long usableBytes, long floor) throws SQLException {
        if (submissionId == null || submissionId.isBlank() || bytes < 0 || usableBytes < 0 || floor < 0) {
            throw new IllegalArgumentException("Invalid reservation");
        }
        try (Connection c = connect(); Statement transaction = c.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                try (PreparedStatement s = c.prepareStatement("SELECT bytes FROM scratch_reservations WHERE submission_id=?")) {
                    s.setString(1, submissionId);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            if (r.getLong(1) != bytes) throw new SQLException("Reservation estimate changed for " + submissionId);
                            transaction.execute("COMMIT");
                            return true;
                        }
                    }
                }
                long outstanding = outstanding(c);
                if (floor > usableBytes || outstanding > usableBytes - floor || bytes > usableBytes - floor - outstanding) {
                    transaction.execute("ROLLBACK");
                    return false;
                }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO scratch_reservations(submission_id,bytes) VALUES(?,?)")) {
                    s.setString(1, submissionId); s.setLong(2, bytes); s.executeUpdate();
                }
                transaction.execute("COMMIT");
                return true;
            } catch (SQLException | RuntimeException e) { transaction.execute("ROLLBACK"); throw e; }
        }
    }

    public boolean hasReservation(String submissionId) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT 1 FROM scratch_reservations WHERE submission_id=?")) {
            s.setString(1, submissionId);
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    public long available(long usableBytes, long floor) throws SQLException {
        if (usableBytes < 0 || floor < 0) throw new IllegalArgumentException("Invalid capacity");
        try (Connection c = connect()) {
            long outstanding = outstanding(c);
            return floor > usableBytes || outstanding > usableBytes - floor ? 0 : usableBytes - floor - outstanding;
        }
    }

    private static long outstanding(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT COALESCE(SUM(bytes),0) FROM scratch_reservations WHERE released=0")) {
            if (!r.next()) throw new SQLException("Cannot read scratch reservations");
            return r.getLong(1);
        }
    }
}
