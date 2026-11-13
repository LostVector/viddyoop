import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkuo.viddyoop.core.catalog.SubmissionCatalog;
import com.rkuo.viddyoop.core.ingest.*;
import com.rkuo.viddyoop.core.media.*;
import java.nio.file.*;

/** Registers one synthetic input at the durable registration/start gap for a guest reboot trial. */
public final class PrepareSharedSubmission {
    public static void main(String[] args) throws Exception {
        Path inbox=Path.of("/inbox"),work=Path.of("/work");
        if (SourceFilesystem.inspect(inbox).epoch().isEmpty()) throw new IllegalStateException("This trial requires a guest sharing filesystem");
        var catalog=new SubmissionCatalog(Path.of("/db/catalog.db"));
        var service=io.temporal.serviceclient.WorkflowServiceStubs.newServiceStubs(
                io.temporal.serviceclient.WorkflowServiceStubsOptions.newBuilder().setTarget("temporal:7233").build());
        try { com.rkuo.viddyoop.core.catalog.TemporalCatalogBinding.verify(Path.of("/db/catalog.db"),service,"default"); }
        finally { service.shutdownNow(); }
        if (new SharedInboxGuard(inbox,work,catalog).check().paused()) throw new IllegalStateException("Fixture is not a fresh shared inbox");
        var adapter=new HandBrakeAdapter("/usr/bin/HandBrakeCLI",new MediaProcessRunner());
        var coordinator=new IngestCoordinator(new SourceStager(work),catalog,
                snapshot->adapter.scan(snapshot.staged(),work.resolve("prepare.scan.log"),()->{}));
        var input=coordinator.accept(inbox.resolve("movie.mkv"),"apple-tv-v1","discovery",Path.of("/library/movie.mp4"),0,"none",null);
        System.out.println(new ObjectMapper().writeValueAsString(input));
    }
}
