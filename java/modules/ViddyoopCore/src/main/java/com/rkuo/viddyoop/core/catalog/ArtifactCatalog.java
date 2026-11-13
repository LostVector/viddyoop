package com.rkuo.viddyoop.core.catalog;

import java.nio.file.Path;
import java.sql.*;

/** Expected publication identity survives the filesystem/catalog commit gap. */
public final class ArtifactCatalog {
    public record Artifact(String submissionId, String source, String destination, String checksum,
                           long durationMs, int width, int height, long encodeDurationMs, String machine,
                           boolean committed) {}
    private final String url;

    public ArtifactCatalog(Path database) throws SQLException {
        url = "jdbc:sqlite:" + database.toAbsolutePath();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            ScratchReservations.createTable(s);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS artifacts (
                  submission_id TEXT PRIMARY KEY, source TEXT NOT NULL,
                  destination TEXT NOT NULL UNIQUE, checksum TEXT NOT NULL,
                  duration_ms INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL,
                  encode_duration_ms INTEGER NOT NULL, machine TEXT NOT NULL,
                  committed INTEGER NOT NULL DEFAULT 0)
                """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS encode_stats (
                  submission_id TEXT PRIMARY KEY, source_path TEXT, final_path TEXT,
                  duration_ms INTEGER, width INTEGER, height INTEGER,
                  encode_duration_ms INTEGER, machine TEXT, finished_at INTEGER)
                """);
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        catch (SQLException e) { c.close(); throw e; }
        return c;
    }

    public Artifact expected(String submissionId) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT * FROM artifacts WHERE submission_id=?")) {
            s.setString(1, submissionId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return null;
                return new Artifact(r.getString("submission_id"), r.getString("source"),
                        r.getString("destination"), r.getString("checksum"), r.getLong("duration_ms"),
                        r.getInt("width"), r.getInt("height"), r.getLong("encode_duration_ms"),
                        r.getString("machine"), r.getInt("committed") != 0);
            }
        }
    }

    public void reserve(Artifact artifact) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("""
            INSERT INTO artifacts(submission_id, source, destination, checksum, duration_ms,
              width, height, encode_duration_ms, machine) VALUES(?,?,?,?,?,?,?,?,?)
            ON CONFLICT(submission_id) DO NOTHING
            """)) {
            s.setString(1, artifact.submissionId()); s.setString(2, artifact.source());
            s.setString(3, artifact.destination()); s.setString(4, artifact.checksum());
            s.setLong(5, artifact.durationMs()); s.setInt(6, artifact.width()); s.setInt(7, artifact.height());
            s.setLong(8, artifact.encodeDurationMs()); s.setString(9, artifact.machine());
            s.executeUpdate();
        }
        Artifact existing = expected(artifact.submissionId());
        if (!existing.destination().equals(artifact.destination()) || !existing.checksum().equals(artifact.checksum())
                || !existing.source().equals(artifact.source())) {
            throw new SQLException("Submission already reserved with different artifact identity");
        }
    }

    public void commit(String submissionId) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO encode_stats(submission_id, source_path, final_path, duration_ms, width,
                      height, encode_duration_ms, machine, finished_at)
                    SELECT submission_id, source, destination, duration_ms, width, height,
                      encode_duration_ms, machine, ? FROM artifacts WHERE submission_id=?
                    ON CONFLICT(submission_id) DO NOTHING
                    """)) {
                    s.setLong(1, System.currentTimeMillis()); s.setString(2, submissionId); s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("UPDATE artifacts SET committed=1 WHERE submission_id=?")) {
                    s.setString(1, submissionId);
                    if (s.executeUpdate() != 1) throw new SQLException("Unknown artifact: " + submissionId);
                }
                try (PreparedStatement s = c.prepareStatement(
                        "UPDATE scratch_reservations SET released=1 WHERE submission_id=?")) {
                    s.setString(1, submissionId); s.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }
}
