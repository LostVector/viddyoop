package com.rkuo.viddyoop.core.catalog;

import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Domain registrations, not a second workflow state machine. One connection per operation. */
public final class SubmissionCatalog {
    private final String url;

    public SubmissionCatalog(Path database) throws SQLException {
        url = "jdbc:sqlite:" + database.toAbsolutePath();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS submissions (
                  id TEXT PRIMARY KEY, source_path TEXT NOT NULL,
                  fingerprint TEXT NOT NULL, profile TEXT NOT NULL, staged_path TEXT, source_generation TEXT, archive_policy TEXT, archive_root TEXT,
                  request_key TEXT NOT NULL, final_path TEXT NOT NULL,
                  precedence INTEGER NOT NULL, start_acknowledged INTEGER NOT NULL DEFAULT 0,
                  UNIQUE(source_path, fingerprint, profile, request_key))
                """);
            boolean hasStagedPath = false, hasGeneration = false, hasArchivePolicy = false, hasArchiveRoot = false;
            try (ResultSet columns = s.executeQuery("PRAGMA table_info(submissions)")) {
                while (columns.next()) {
                    hasStagedPath |= "staged_path".equals(columns.getString("name"));
                    hasArchivePolicy |= "archive_policy".equals(columns.getString("name"));
                    hasArchiveRoot |= "archive_root".equals(columns.getString("name"));
                    hasGeneration |= "source_generation".equals(columns.getString("name"));
                }
            }
            if (!hasArchivePolicy) s.executeUpdate("ALTER TABLE submissions ADD COLUMN archive_policy TEXT");
            if (!hasArchiveRoot) s.executeUpdate("ALTER TABLE submissions ADD COLUMN archive_root TEXT");
            if (!hasGeneration) s.executeUpdate("ALTER TABLE submissions ADD COLUMN source_generation TEXT");
            if (!hasStagedPath) s.executeUpdate("ALTER TABLE submissions ADD COLUMN staged_path TEXT");
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    /**
     * fingerprint must describe an immutable claimed/staged source, not just its pathname.
     * Use a stable requestKey for automatic discovery; explicit re-encodes supply a new
     * request key and reuse it on request retries. This method never reads or deletes media.
     */
    public PipelineInput register(Path source, String fingerprint, String profile,
                                  String requestKey, Path destination, int precedence) throws SQLException {
        return register(source, fingerprint, profile, requestKey, destination, precedence, source);
    }

    public PipelineInput register(Path source, String fingerprint, String profile,
                                  String requestKey, Path destination, int precedence, Path stagedSource) throws SQLException {
        return register(source, fingerprint, profile, requestKey, destination, precedence, stagedSource, null);
    }

    public PipelineInput register(Path source, String fingerprint, String profile, String requestKey,
                                  Path destination, int precedence, Path stagedSource, String generation) throws SQLException {
        return register(source, fingerprint, profile, requestKey, destination, precedence, stagedSource, generation, null, null);
    }

    public PipelineInput register(Path source, String fingerprint, String profile, String requestKey,
                                  Path destination, int precedence, Path stagedSource, String generation,
                                  String archivePolicy, Path archiveRoot) throws SQLException {
        if (archivePolicy != null && !archivePolicy.equals("none") && !archivePolicy.equals("movies")) {
            throw new IllegalArgumentException("Unsupported archive policy");
        }
        if ("movies".equals(archivePolicy) && archiveRoot == null) throw new IllegalArgumentException("Archive root required");
        String root = archiveRoot == null ? null : archiveRoot.toAbsolutePath().normalize().toString();
        for (String value : new String[] {fingerprint, profile, requestKey}) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("identity fields are required");
        }
        String sourcePath = source.toAbsolutePath().normalize().toString();
        String finalPath = destination.toAbsolutePath().normalize().toString();
        try (Connection c = connect()) {
            try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO submissions(id, source_path, fingerprint, profile, request_key, final_path, precedence, staged_path, source_generation, archive_policy, archive_root)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(source_path, fingerprint, profile, request_key) DO NOTHING
                """)) {
                s.setString(1, UUID.randomUUID().toString());
                s.setString(2, sourcePath); s.setString(3, fingerprint); s.setString(4, profile);
                s.setString(5, requestKey); s.setString(6, finalPath); s.setInt(7, precedence);
                s.setString(8, stagedSource.toAbsolutePath().normalize().toString());
                s.setString(9, generation); s.setString(10, archivePolicy); s.setString(11, root);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("""
                SELECT * FROM submissions WHERE source_path=? AND fingerprint=? AND profile=? AND request_key=?
                """)) {
                s.setString(1, sourcePath); s.setString(2, fingerprint);
                s.setString(3, profile); s.setString(4, requestKey);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new SQLException("Registration disappeared");
                    PipelineInput input = read(r);
                    if (!finalPath.equals(input.finalPath) || precedence != input.precedence
                            || !java.util.Objects.equals(root, input.archiveRoot)
                            || !java.util.Objects.equals(archivePolicy, input.archivePolicy)) {
                        throw new IllegalArgumentException("Existing submission has different destination, priority, or archive policy");
                    }
                    return input;
                }
            }
        }
    }

    public PipelineInput find(String submissionId) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT * FROM submissions WHERE id=?")) {
            s.setString(1, submissionId);
            try (ResultSet r = s.executeQuery()) { return r.next() ? read(r) : null; }
        }
    }

    public List<PipelineInput> pendingStarts() throws SQLException {
        List<PipelineInput> result = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT * FROM submissions WHERE start_acknowledged=0")) {
            while (r.next()) result.add(read(r));
        }
        return result;
    }

    public List<Path> registeredSourcePaths() throws SQLException {
        List<Path> paths = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT DISTINCT source_path FROM submissions ORDER BY source_path")) {
            while (r.next()) paths.add(Path.of(r.getString(1)));
        }
        return List.copyOf(paths);
    }

    public void acknowledgeStart(String submissionId) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "UPDATE submissions SET start_acknowledged=1 WHERE id=?")) {
            s.setString(1, submissionId);
            if (s.executeUpdate() != 1) throw new SQLException("Unknown submission: " + submissionId);
        }
    }

    private static PipelineInput read(ResultSet r) throws SQLException {
        PipelineInput input = new PipelineInput(r.getString("source_path"), r.getString("final_path"), r.getInt("precedence"));
        input.archivePolicy = r.getString("archive_policy");
        input.archiveRoot = r.getString("archive_root");
        input.sourceGeneration = r.getString("source_generation");
        input.stagedSourcePath = r.getString("staged_path");
        input.submissionId = r.getString("id");
        input.sourceFingerprint = r.getString("fingerprint");
        input.encodeProfile = r.getString("profile");
        return input;
    }
}
