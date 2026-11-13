package com.rkuo.viddyoop.core.status;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.sqlite.SQLiteConfig;

/** Read-only projection; status never creates/migrates databases or repairs jobs. */
public final class StatusCatalog {
    public record Delivery(String outcome, String note, Long deliveredAt, Long observedAt) {}
    public record Submission(String id, String source, String destination, String profile,
                             boolean startAcknowledged, String artifactState, String checksum, long scratchReservedBytes, Delivery delivery) {}

    public List<Submission> read(Path database, String submissionId, int limit) throws IOException, SQLException {
        if (limit < 1 || limit > 10000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        if (!Files.isRegularFile(database)) throw new IOException("Catalog does not exist: " + database);
        SQLiteConfig config = new SQLiteConfig(); config.setReadOnly(true); config.setBusyTimeout(5000);
        List<Submission> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath(), config.toProperties())) {
            boolean artifacts;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='artifacts'")) {
                artifacts = r.next() && r.getInt(1) > 0;
            }
            boolean reservations;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='scratch_reservations'")) {
                reservations = r.next() && r.getInt(1) > 0;
            }
            boolean deliveries;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='itunes_deliveries'")) {
                deliveries = r.next() && r.getInt(1) > 0;
            }
            String query = "SELECT s.*," + (artifacts ? "a.committed, a.checksum" : "NULL AS committed, NULL AS checksum")
                    + (reservations ? ", CASE WHEN r.released=0 THEN r.bytes ELSE 0 END AS scratch_reserved" : ", 0 AS scratch_reserved")
                    + (deliveries ? ", d.outcome AS import_outcome,d.note AS import_note,d.delivered_at AS import_delivered,d.observed_at AS import_observed"
                            : ", NULL AS import_outcome,NULL AS import_note,NULL AS import_delivered,NULL AS import_observed")
                    + " FROM submissions s " + (artifacts ? "LEFT JOIN artifacts a ON a.submission_id=s.id " : "")
                    + (reservations ? "LEFT JOIN scratch_reservations r ON r.submission_id=s.id " : "")
                    + (deliveries ? "LEFT JOIN itunes_deliveries d ON d.submission_id=s.id " : "")
                    + "WHERE (? IS NULL OR s.id=?) ORDER BY s.rowid DESC LIMIT ?";
            try (PreparedStatement s = c.prepareStatement(query)) {
                s.setString(1, submissionId); s.setString(2, submissionId); s.setInt(3, limit);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        String checksum = r.getString("checksum");
                        Delivery delivery = r.getString("import_outcome") == null ? null : new Delivery(r.getString("import_outcome"),
                                r.getString("import_note"), nullable(r,"import_delivered"), nullable(r,"import_observed"));
                        rows.add(new Submission(r.getString("id"), r.getString("source_path"), r.getString("final_path"),
                                r.getString("profile"), r.getInt("start_acknowledged") != 0,
                                checksum == null ? "unregistered" : r.getInt("committed") != 0 ? "committed" : "reserved", checksum, r.getLong("scratch_reserved"), delivery));
                    }
                }
            }
        }
        return List.copyOf(rows);
    }
    private static Long nullable(ResultSet r,String name)throws SQLException { long value=r.getLong(name); return r.wasNull()?null:value; }

}
