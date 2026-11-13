package com.rkuo.viddyoop.core.itunes;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog.Artifact;
import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;

/** External delivery receipts. Temporal owns orchestration; these facts prevent duplicate side effects. */
public final class ItunesDeliveryCatalog {
    public enum Outcome { PREPARED, DELIVERED, AMBIGUOUS, REJECTED, CONFIRMED }
    public record Delivery(String submissionId, String artifactPath, String checksum, String watchedPath, String stagedPath,
                           long preparedAt, Long publishIntentAt, Long deliveredAt, Long observedAt, Outcome outcome, String note, String observedLibraryId, Long observedTrackId, String observedPersistentId) {}
    private final String url;
    public ItunesDeliveryCatalog(Path database) throws SQLException {
        url = "jdbc:sqlite:" + database.toAbsolutePath();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS itunes_deliveries (
                  submission_id TEXT PRIMARY KEY, artifact_path TEXT NOT NULL, checksum TEXT NOT NULL,
                  watched_path TEXT NOT NULL UNIQUE, staged_path TEXT NOT NULL UNIQUE,
                  prepared_at INTEGER NOT NULL, publish_intent_at INTEGER, delivered_at INTEGER,
                  outcome TEXT NOT NULL, note TEXT NOT NULL, observed_at INTEGER, observed_library_id TEXT, observed_track_id INTEGER, observed_persistent_id TEXT)
                """);
            java.util.Set<String> columns = new java.util.HashSet<>();
            try (ResultSet r = s.executeQuery("PRAGMA table_info(itunes_deliveries)")) {
                while (r.next()) columns.add(r.getString("name"));
            }
            for (String column : java.util.List.of("observed_library_id", "observed_track_id", "observed_persistent_id")) {
                if (!columns.contains(column)) s.executeUpdate("ALTER TABLE itunes_deliveries ADD COLUMN " + column
                        + (column.equals("observed_track_id") ? " INTEGER" : " TEXT"));
            }
        }
    }
    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        catch (SQLException e) { c.close(); throw e; }
        return c;
    }
    public Delivery find(String id) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT * FROM itunes_deliveries WHERE submission_id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return null;
                return new Delivery(r.getString("submission_id"), r.getString("artifact_path"), r.getString("checksum"),
                        r.getString("watched_path"), r.getString("staged_path"), r.getLong("prepared_at"),
                        nullable(r, "publish_intent_at"), nullable(r, "delivered_at"), nullable(r, "observed_at"), Outcome.valueOf(r.getString("outcome")), r.getString("note"), r.getString("observed_library_id"),
                        nullable(r, "observed_track_id"), r.getString("observed_persistent_id"));
            }
        }
    }
    public java.util.List<String> pendingArtifactIds() throws SQLException {
        var ids = new java.util.ArrayList<String>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("""
            SELECT a.submission_id FROM artifacts a LEFT JOIN itunes_deliveries d ON d.submission_id=a.submission_id
            WHERE a.committed=1 AND (d.submission_id IS NULL OR d.outcome NOT IN ('CONFIRMED','REJECTED')) ORDER BY a.rowid
            """)) {
            while (r.next()) ids.add(r.getString(1));
        }
        return java.util.List.copyOf(ids);
    }

    public Delivery prepare(Artifact artifact, Path watched, Path staged) throws SQLException, java.io.IOException {
        watched = watched.toAbsolutePath().normalize().getParent().toRealPath().resolve(watched.getFileName());
        staged = staged.toAbsolutePath().normalize().getParent().toRealPath().resolve(staged.getFileName());
        if (!artifact.committed()) throw new IllegalArgumentException("Only committed artifacts can be delivered");
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("""
            INSERT INTO itunes_deliveries(submission_id,artifact_path,checksum,watched_path,staged_path,prepared_at,outcome,note)
            VALUES(?,?,?,?,?,?,'PREPARED','Copy not yet published') ON CONFLICT(submission_id) DO NOTHING
            """)) {
            s.setString(1, artifact.submissionId()); s.setString(2, artifact.destination()); s.setString(3, artifact.checksum());
            s.setString(4, watched.toString()); s.setString(5, staged.toString()); s.setLong(6, System.currentTimeMillis()); s.executeUpdate();
        }
        Delivery row = find(artifact.submissionId());
        if (!Objects.equals(row.artifactPath(), artifact.destination()) || !Objects.equals(row.checksum(), artifact.checksum())
                || !row.watchedPath().equals(watched.toString()) || !row.stagedPath().equals(staged.toString())) {
            throw new SQLException("Delivery identity/configuration differs from its durable receipt");
        }
        return row;
    }
    void publishIntent(String id) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "UPDATE itunes_deliveries SET publish_intent_at=COALESCE(publish_intent_at,?),note='Publish intent recorded; outcome not yet acknowledged' WHERE submission_id=?")) {
            s.setLong(1, System.currentTimeMillis()); s.setString(2, id);
            if (s.executeUpdate() != 1) throw new SQLException("Unknown delivery");
        }
    }
    void delivered(String id) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("""
            UPDATE itunes_deliveries SET delivered_at=COALESCE(delivered_at,?),outcome='DELIVERED',note='Complete copy placed in watched folder'
            WHERE submission_id=? AND outcome NOT IN ('CONFIRMED','REJECTED')
            """)) {
            s.setLong(1, System.currentTimeMillis()); s.setString(2, id); s.executeUpdate();
        }
    }
    void observe(String id, Outcome outcome, String note) throws SQLException {
        if (outcome != Outcome.AMBIGUOUS && outcome != Outcome.REJECTED) throw new IllegalArgumentException("Invalid observation");
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "UPDATE itunes_deliveries SET outcome=?,note=?,observed_at=? WHERE submission_id=? AND outcome NOT IN ('CONFIRMED','REJECTED')")) {
            s.setString(1, outcome.name()); s.setString(2, note); s.setLong(3, System.currentTimeMillis()); s.setString(4, id); s.executeUpdate();
        }
    }
    void confirmed(String id, ItunesCatalog.Info index, ItunesCatalog.Item item) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("""
            UPDATE itunes_deliveries SET outcome='CONFIRMED',note='Exact content verified in indexed media',observed_at=?,
              observed_library_id=?,observed_track_id=?,observed_persistent_id=?
            WHERE submission_id=? AND outcome NOT IN ('CONFIRMED','REJECTED')
            """)) {
            s.setLong(1, System.currentTimeMillis()); s.setString(2, index.libraryId()); s.setLong(3, item.id());
            s.setString(4, item.persistentId()); s.setString(5, id); s.executeUpdate();
        }
    }

    private static Long nullable(ResultSet r, String column) throws SQLException {
        long value = r.getLong(column); return r.wasNull() ? null : value;
    }
}
