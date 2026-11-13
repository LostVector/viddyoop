import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkuo.viddyoop.core.media.MediaFiles;
import java.nio.file.*;
import java.sql.*;
import java.util.Map;
import org.sqlite.SQLiteConfig;

/** Source-launched only by the disposable Compose restore drill; never migrates the catalog. */
public final class CheckRestoredCatalog {
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    public static void main(String[] args) throws Exception {
        Path db = Path.of(args[0]); String id = args[1];
        require(Files.isRegularFile(db), "Restored catalog missing");
        var config = new SQLiteConfig(); config.setReadOnly(true); config.setBusyTimeout(5000);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db, config.toProperties())) {
            c.setAutoCommit(false);
            String namespace, namespaceId;
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT namespace,namespace_id FROM temporal_binding WHERE singleton=1")) {
                require(r.next(), "Restored namespace binding missing");
                namespace = r.getString(1); namespaceId = r.getString(2);
                require(namespace != null && !namespace.isBlank() && namespaceId != null && !namespaceId.isBlank(), "Restored namespace binding incomplete");
            }
            for (String table : new String[]{"submissions", "artifacts", "encode_stats"}) {
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + table)) {
                    require(r.next() && r.getInt(1) == 1, "Expected one row in " + table);
                }
            }
            try (var s = c.prepareStatement("SELECT a.destination,a.checksum,a.committed,s.staged_path,s.fingerprint "
                    + "FROM artifacts a JOIN submissions s ON s.id=a.submission_id JOIN encode_stats e ON e.submission_id=s.id WHERE s.id=?")) {
                s.setString(1, id);
                try (var r = s.executeQuery()) {
                    require(r.next() && r.getInt("committed") == 1, "Restored submission not committed");
                    require(r.getString("destination").equals("/library/movie.mp4"), "Unexpected destination");
                    String checksum = r.getString("checksum");
                    require(checksum.equals(MediaFiles.checksum(Path.of(r.getString("destination")), () -> {})), "Library checksum mismatch");
                    require(r.getString("fingerprint").equals(MediaFiles.checksum(Path.of(r.getString("staged_path")), () -> {})), "Snapshot fingerprint mismatch");
                    System.out.println(new ObjectMapper().writeValueAsString(Map.of(
                            "submissionId", id, "artifactChecksum", checksum, "statsRows", 1, "snapshotVerified", true,
                            "namespace", namespace, "namespaceId", namespaceId)));
                }
            }
        }
    }
}
