package com.rkuo.viddyoop.core.worker;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog;
import com.rkuo.viddyoop.core.itunes.*;
import io.temporal.activity.Activity;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name="itunes-import", mixinStandardHelpOptions=true,
        description="Deliver/reconcile one committed artifact, or run the Temporal import worker.")
public final class ItunesImportCommand implements Callable<Integer> {
    @Option(names="--database") Path database = Path.of(env("VIDYOOP_DATABASE", "/db/catalog.db"));
    @Option(names="--library") Path library = Path.of(env("VIDYOOP_LIBRARY", "/library"));
    @Option(names="--add-folder") Path watched = Path.of(env("VIDYOOP_ITUNES_ADD", "/itunes-delivery/Automatically Add to iTunes.localized"));
    @Option(names="--staging") Path staging = Path.of(env("VIDYOOP_ITUNES_STAGING", "/itunes-delivery/.viddyoop-staging"));
    @Option(names="--host-media-root") Path hostMedia = optional("VIDYOOP_ITUNES_HOST_MEDIA_ROOT");
    @Option(names="--media-root") Path mountedMedia = optional("VIDYOOP_ITUNES_MEDIA_ROOT");
    @Option(names="--rejected-folder") Path rejected = optional("VIDYOOP_ITUNES_REJECTED");
    @Option(names="--submission") String submission;
    @Option(names="--serve") boolean serve;
    @Option(names="--reconcile-only", description="Observe/repair receipts without publishing a new watched copy (use after restore).") boolean reconcileOnly;
    @Option(names="--temporal") String target = env("VIDYOOP_TEMPORAL", "localhost:7233");
    @Option(names="--namespace") String namespace = env("VIDYOOP_NAMESPACE", "default");
    @Option(names="--queue") String queue = env("VIDYOOP_ITUNES_IMPORT_QUEUE", "viddyoop-itunes-import");
    @Option(names="--check-seconds") int checkSeconds = Integer.parseInt(env("VIDYOOP_ITUNES_IMPORT_CHECK_SECONDS", "60"));
    @Option(names="--scan-seconds") int scanSeconds = Integer.parseInt(env("VIDYOOP_ITUNES_IMPORT_SCAN_SECONDS", "10"));
    @Spec Model.CommandSpec spec;

    @Override public Integer call() throws Exception {
        if (serve == (submission != null)) throw new IllegalArgumentException("Choose --serve or --submission ID");
        if (checkSeconds < 1 || scanSeconds < 1 || queue.isBlank()) throw new IllegalArgumentException("Invalid import polling configuration");
        if (serve && (hostMedia == null || mountedMedia == null)) throw new IllegalArgumentException("Automatic import requires both host and mounted media roots");
        if (!Files.isRegularFile(database)) throw new IllegalArgumentException("Pipeline catalog must already exist");
        var readOnly = new org.sqlite.SQLiteConfig(); readOnly.setReadOnly(true);
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath(), readOnly.toProperties());
             var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='artifacts'")) {
            if (!r.next() || r.getInt(1) != 1) throw new IllegalArgumentException("Catalog has no pipeline artifact manifest");
        }
        var artifacts = new ArtifactCatalog(database);
        var receipts = new ItunesDeliveryCatalog(database);
        var index = new ItunesCatalog(database);
        var reconciler = new ItunesReconciler(artifacts, receipts, index, hostMedia, mountedMedia, rejected);
        var delivery = new ItunesDelivery(artifacts, receipts, library, watched, staging, reconciler);
        var processor = new ItunesImportProcessor(receipts, delivery, reconciler, reconcileOnly);
        if (!serve) {
            var result = processor.process(submission, () -> {});
            spec.commandLine().getOut().println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));
            return 0;
        }
        try (FileChannel owner = FileChannel.open(database.resolveSibling(database.getFileName() + ".itunes-import.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = owner.tryLock()) {
            if (lock == null) throw new IllegalStateException("An iTunes import worker already owns this catalog");
            var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
            var client = WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
            var factory = WorkerFactory.newInstance(client);
            var worker = factory.newWorker(queue, WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(1).build());
            worker.registerWorkflowImplementationTypes(ItunesImportWorkflowImpl.class);
            worker.registerActivitiesImplementations(new ItunesImportActivity() {
                public ItunesDeliveryCatalog.Delivery process(String id) {
                    try {
                        if (index.lookupDuration(0, 0).index() == null) throw new IllegalStateException("No completed iTunes index is available");
                        return processor.process(id, () -> Activity.getExecutionContext().heartbeat("Delivering/reconciling iTunes artifact")); }
                    catch (Exception e) { throw new IllegalStateException("iTunes import failed", e); }
                }
            });
            var starter = new ItunesImportStarter(client, queue);
            Thread shutdown = new Thread(() -> { factory.shutdownNow(); service.shutdownNow(); }, "itunes-import-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                com.rkuo.viddyoop.core.catalog.TemporalCatalogBinding.verify(database, service, namespace);
                factory.start();
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        if (index.lookupDuration(0, 0).index() == null) {
                            System.err.println("Waiting for a completed iTunes index before automatic delivery");
                            Thread.sleep(scanSeconds * 1000L); continue;
                        }
                        for (String id : receipts.pendingArtifactIds()) {
                            var receipt = receipts.find(id);
                            if (receipt == null || (receipt.outcome() != ItunesDeliveryCatalog.Outcome.CONFIRMED
                                    && receipt.outcome() != ItunesDeliveryCatalog.Outcome.REJECTED)) starter.start(id, checkSeconds);
                        }
                    } catch (InterruptedException e) { throw e; }
                    catch (Exception e) { System.err.println("Import discovery failed: " + e); }
                    Thread.sleep(scanSeconds * 1000L);
                }
                return 0;
            } finally {
                factory.shutdownNow(); service.shutdownNow();
                try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) {}
            }
        }
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
    private static Path optional(String name) {
        String value = System.getenv(name); return value == null || value.isBlank() ? null : Path.of(value);
    }
}
