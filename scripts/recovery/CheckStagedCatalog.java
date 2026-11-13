import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkuo.viddyoop.core.media.MediaFiles;
import java.nio.file.*;
import java.sql.*;
import java.util.Map;
import org.sqlite.SQLiteConfig;

/** Read-only evidence before the isolated VM-loss trial; workflow state comes from Temporal. */
public final class CheckStagedCatalog {
    public static void main(String[] args) throws Exception {
        var config = new SQLiteConfig(); config.setReadOnly(true); config.setBusyTimeout(5000);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + Path.of(args[0]), config.toProperties())) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                for (String table : new String[]{"submissions", "artifacts", "encode_stats"}) {
                    try (var r = s.executeQuery("SELECT count(*) FROM " + table)) {
                        if (!r.next() || r.getInt(1) != (table.equals("submissions") ? 1 : 0)) {
                            throw new IllegalStateException("Unexpected pre-publication rows in " + table);
                        }
                    }
                }
            }
            try (var s = c.prepareStatement("SELECT s.staged_path,s.fingerprint,b.namespace_id FROM submissions s CROSS JOIN temporal_binding b WHERE s.id=? AND b.singleton=1")) {
                s.setString(1, args[1]);
                try (var r = s.executeQuery()) {
                    if (!r.next()) throw new IllegalStateException("Submission or namespace binding missing");
                    String fingerprint = r.getString(2), namespaceId = r.getString(3);
                    if (namespaceId.isBlank() || !fingerprint.equals(MediaFiles.checksum(Path.of(r.getString(1)), () -> {}))) {
                        throw new IllegalStateException("Unverified staged input or namespace binding");
                    }
                    System.out.println(new ObjectMapper().writeValueAsString(Map.of("submissionId",args[1],"namespaceId",namespaceId,"fingerprint",fingerprint)));
                }
            }
        }
    }
}
