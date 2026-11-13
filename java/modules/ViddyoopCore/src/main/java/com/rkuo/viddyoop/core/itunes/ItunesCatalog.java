package com.rkuo.viddyoop.core.itunes;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.sqlite.SQLiteConfig;

/** Candidate matches are evidence for review, never automatic permission to discard input. */
public final class ItunesCatalog {
    public record Info(String libraryId, String source, long indexedAt, Long sourceModifiedAt) {}
    public record Item(long id, String persistentId, String location, String name, String kind, Long durationMs, String dateAdded) {}
    public record Lookup(Info index, List<Item> candidates) {}
    private final Path database;
    public ItunesCatalog(Path database) { this.database = database; }

    public Lookup lookup(String name, long duration, long tolerance) throws SQLException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Candidate name is required");
        return query(name, duration, tolerance);
    }
    public Lookup lookupDuration(long duration, long tolerance) throws SQLException { return query(null, duration, tolerance); }
    private Lookup query(String name, long duration, long tolerance) throws SQLException {
        if (duration < 0 || tolerance < 0) throw new IllegalArgumentException("Invalid candidate duration");
        if (!Files.isRegularFile(database)) return new Lookup(null, List.of());
        SQLiteConfig config = new SQLiteConfig(); config.setReadOnly(true); config.setBusyTimeout(5000);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath(), config.toProperties())) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='itunes_index_meta'")) {
                if (!r.next() || r.getInt(1) == 0) return new Lookup(null, List.of());
            }
            Info info;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM itunes_index_meta WHERE id=1")) {
                if (!r.next()) return new Lookup(null, List.of());
                Number modified = null;
                for (int column = 1; column <= r.getMetaData().getColumnCount(); column++) {
                    if (r.getMetaData().getColumnName(column).equals("source_modified_at")) modified = (Number) r.getObject(column);
                }
                info = new Info(r.getString("library_id"), r.getString("source_path"), r.getLong("indexed_at"),
                        modified == null ? null : modified.longValue());
            }
            long low = tolerance > duration ? 0 : duration - tolerance;
            long high = tolerance > Long.MAX_VALUE - duration ? Long.MAX_VALUE : duration + tolerance;
            List<Item> matches = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("""
                SELECT * FROM itunes_items WHERE (? IS NULL OR name=? COLLATE NOCASE) AND duration_ms BETWEEN ? AND ? ORDER BY id LIMIT 100
                """)) {
                s.setString(1, name); s.setString(2, name); s.setLong(3, low); s.setLong(4, high);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) matches.add(new Item(r.getLong("id"), r.getString("persistent_id"), r.getString("location"),
                            r.getString("name"), r.getString("kind"), r.getLong("duration_ms"), r.getString("date_added")));
                }
            }
            c.commit(); return new Lookup(info, List.copyOf(matches));
        }
    }
}
