import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkuo.viddyoop.core.ingest.SourceStager;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/** Controlled mount/restart probe; only stages the caller's synthetic fixture. */
public final class SharedSourceIdentity {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]);
        var snapshot = new SourceStager(Path.of(args[1])).stage(source);
        var attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        System.out.println(new ObjectMapper().writeValueAsString(Map.of(
                "generation", snapshot.generation(), "fingerprint", snapshot.fingerprint(),
                "fileKey", String.valueOf(attributes.fileKey()), "created", attributes.creationTime().toString(),
                "modified", attributes.lastModifiedTime().toString(), "size", attributes.size())));
    }
}
