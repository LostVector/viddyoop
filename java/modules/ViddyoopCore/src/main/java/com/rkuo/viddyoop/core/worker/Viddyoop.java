package com.rkuo.viddyoop.core.worker;

import com.rkuo.viddyoop.core.activities.*;
import com.rkuo.viddyoop.core.catalog.*;
import com.rkuo.viddyoop.core.ingest.*;
import com.rkuo.viddyoop.core.media.*;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name="viddyoop", version="viddyoop 1.0.0-SNAPSHOT", mixinStandardHelpOptions=true, subcommands={Viddyoop.Pipeline.class, Viddyoop.Status.class, Viddyoop.Reencode.class, Viddyoop.Health.class, Viddyoop.ItunesIndex.class, ItunesImportCommand.class, RetryCommand.class})
public final class Viddyoop implements Runnable {
    public static void main(String[] args) { System.exit(new CommandLine(new Viddyoop()).execute(args)); }
    public void run() { new CommandLine(this).usage(System.out); }

    @Command(name="pipeline", mixinStandardHelpOptions=true, description="Run the Temporal pipeline worker and inbox scanner.")
    public static final class Pipeline implements Callable<Integer> {
        @Option(names="--inbox") Path inbox = Path.of(env("VIDYOOP_INBOX", "/inbox"));
        @Option(names="--work") Path work = Path.of(env("VIDYOOP_WORK", "/work"));
        @Option(names="--library") Path library = Path.of(env("VIDYOOP_LIBRARY", "/library"));
        @Option(names="--archive") Path archive = Path.of(env("VIDYOOP_ARCHIVE", "/archive"));
        @Option(names="--archive-policy") String archivePolicy = env("VIDYOOP_ARCHIVE_POLICY", "movies");
        @Option(names="--database") Path database = Path.of(env("VIDYOOP_DATABASE", "/db/catalog.db"));
        @Option(names="--temporal") String target = env("VIDYOOP_TEMPORAL", "localhost:7233");
        @Option(names="--namespace") String namespace = env("VIDYOOP_NAMESPACE", "default");
        @Option(names="--queue") String queue = env("VIDYOOP_QUEUE", "viddyoop");
        @Option(names="--handbrake") String handbrake = env("VIDYOOP_HANDBRAKE", "/usr/bin/HandBrakeCLI");
        @Option(names="--profile") String profile = env("VIDYOOP_PROFILE", HandBrakeAdapter.HEVC_PROFILE);
        @Option(names="--mkvmerge") String mkvmerge = env("VIDYOOP_MKVMERGE", "/usr/bin/mkvmerge");
        @Option(names="--mkvextract") String mkvextract = env("VIDYOOP_MKVEXTRACT", "/usr/bin/mkvextract");
        @Option(names="--ffmpeg") String ffmpeg = env("VIDYOOP_FFMPEG", "/usr/bin/ffmpeg");
        @Option(names="--encode-workers") int encodeWorkers = Integer.parseInt(env("VIDYOOP_ENCODE_WORKERS", "1"));
        @Option(names="--preprocess-workers") int preprocessWorkers = Integer.parseInt(env("VIDYOOP_PREPROCESS_WORKERS", "1"));
        @Option(names="--publish-workers") int publishWorkers = Integer.parseInt(env("VIDYOOP_PUBLISH_WORKERS", "1"));
        @Option(names="--settle-ms") long settleMs = Long.parseLong(env("VIDYOOP_SETTLE_MS", "60000"));
        @Option(names="--poll-ms") long pollMs = Long.parseLong(env("VIDYOOP_POLL_MS", "5000"));
        @Option(names="--scratch-multiplier") int scratchMultiplier = Integer.parseInt(env("VIDYOOP_SCRATCH_MULTIPLIER", "4"));
        @Option(names="--min-free-bytes") long minFreeBytes = Long.parseLong(env("VIDYOOP_MIN_FREE_BYTES", "10737418240"));

        @Override public Integer call() throws Exception {
            if (encodeWorkers < 1 || preprocessWorkers < 1 || publishWorkers < 1 || settleMs < 0
                    || pollMs < 1 || minFreeBytes < 0 || scratchMultiplier < 1 || queue.isBlank()) {
                throw new IllegalArgumentException("Invalid concurrency, polling, capacity, or queue configuration");
            }
            if (!Set.of("movies", "none").contains(archivePolicy)) throw new IllegalArgumentException("Archive policy must be movies or none");
            if (!HandBrakeAdapter.supportsProfile(profile)) throw new IllegalArgumentException("Unsupported encode profile: " + profile);
            for (Path directory : List.of(inbox, work, library, database.toAbsolutePath().getParent())) {
                Files.createDirectories(directory);
            }
            Path realInbox = inbox.toRealPath(), realWork = work.toRealPath(), realLibrary = library.toRealPath();
            if (realInbox.startsWith(realWork) || realWork.startsWith(realInbox)
                    || realInbox.startsWith(realLibrary) || realLibrary.startsWith(realInbox)
                    || realWork.startsWith(realLibrary) || realLibrary.startsWith(realWork)) {
                throw new IllegalArgumentException("Inbox, work, and library must be separate directory trees");
            }
            if (archivePolicy.equals("movies")) {
                Files.createDirectories(archive);
                Path realArchive = archive.toRealPath();
                for (Path directory : List.of(realInbox, realWork, realLibrary)) {
                    if (directory.startsWith(realArchive) || realArchive.startsWith(directory)) {
                        throw new IllegalArgumentException("Archive must be a separate directory tree");
                    }
                }
            }
            try (FileChannel owner = FileChannel.open(work.resolve(".pipeline.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = owner.tryLock()) {
                if (lock == null) throw new IllegalStateException("Another pipeline owns this work directory");
                return runWorker();
            }
        }

        private int runWorker() throws Exception {
            var submissions = new SubmissionCatalog(database);
            var artifacts = new ArtifactCatalog(database);
            var reservations = new ScratchReservations(database);
            var inboxGuard = new SharedInboxGuard(inbox, work, submissions);
            var adapter = new HandBrakeAdapter(handbrake, new MediaProcessRunner());
            var activities = new NativeMediaActivities(work, adapter, new ArtifactPublisher(artifacts, library),
                    new MediaNormalizer(mkvmerge, mkvextract, ffmpeg, new MediaProcessRunner()), new SourceCleanup(artifacts, inbox), new SourceArchiver(archive));
            var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
            var client = WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
            var factory = WorkerFactory.newInstance(client);
            PipelineWorkers.register(factory, queue, activities, preprocessWorkers, encodeWorkers, publishWorkers);
            var coordinator = new IngestCoordinator(new SourceStager(work), submissions, snapshot -> {
                adapter.scan(snapshot.staged(), snapshot.staged().resolveSibling(snapshot.generation() + ".scan.log"), () -> {});
            });
            var dispatcher = new SubmissionDispatcher(submissions, new PipelineWorkflowStarter(client, queue), input -> {
                if (reservations.hasReservation(input.submissionId)) return true;
                Path staged = Path.of(input.stagedSourcePath);
                if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                    System.err.println("Waiting for source snapshot recovery: " + input.submissionId);
                    return false;
                }
                long bytes = ScratchReservations.estimate(Files.size(staged), scratchMultiplier);
                boolean admitted = reservations.reserve(input.submissionId, bytes, Files.getFileStore(work).getUsableSpace(), minFreeBytes);
                if (!admitted) System.err.println("Waiting for scratch capacity: " + input.submissionId);
                return admitted;
            });
            Thread shutdown = new Thread(() -> { factory.shutdownNow(); service.shutdownNow(); }, "pipeline-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            Thread ingestThread = Thread.currentThread();
            var readiness = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "pipeline-readiness"); thread.setDaemon(true); return thread;
            });
            try {
                TemporalCatalogBinding.verify(database, service, namespace);
                factory.start();
                readiness.scheduleAtFixedRate(() -> {
                    if (ingestThread.isAlive() && factory.isStarted() && !factory.isShutdown()) {
                        try { PipelineHeartbeat.write(work); }
                        catch (java.io.IOException e) { System.err.println("Cannot update readiness: " + e); }
                    }
                }, 0, 5, java.util.concurrent.TimeUnit.SECONDS);
                var watcher = new IngestWatcher();
                record Accepted(String version, Path snapshot) {}
                Map<Path, Accepted> accepted = new HashMap<>();
                Set<String> extensions = Set.of(".mkv", ".mp4", ".m4v", ".avi", ".ts");
                SharedInboxGuard.Status lastPause = null;
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        dispatcher.dispatchPending();
                        var admission = inboxGuard.check();
                        if (admission.paused()) {
                            if (!admission.equals(lastPause)) System.err.println(admission.reason() + " See " + work.resolve(".ingest-reconcile.json"));
                            lastPause = admission;
                            Thread.sleep(pollMs); continue;
                        }
                        lastPause = null;
                        accepted.keySet().removeIf(path -> !Files.exists(path, LinkOption.NOFOLLOW_LINKS));
                        for (Path source : watcher.scanOnce(inbox, extensions, System.currentTimeMillis(), settleMs)) {
                            try {
                                var attrs = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                                String version = attrs.fileKey() + ":" + attrs.creationTime() + ":" + attrs.lastModifiedTime() + ":" + attrs.size();
                                var previous = accepted.get(source);
                                if (previous != null && version.equals(previous.version())
                                        && Files.isRegularFile(previous.snapshot(), LinkOption.NOFOLLOW_LINKS)) continue;
                                long usable = Files.getFileStore(work).getUsableSpace();
                                long intakeBytes = Math.addExact(attrs.size(), ScratchReservations.estimate(attrs.size(), scratchMultiplier));
                                if (intakeBytes > reservations.available(usable, minFreeBytes)) {
                                    System.err.println("Deferring input: insufficient scratch capacity: " + source);
                                    continue;
                                }
                                String name = com.rkuo.viddyoop.core.policy.LibraryNames.outputName(source);
                                var input = coordinator.accept(source, profile, "discovery", library.resolve(name), 0, archivePolicy, archivePolicy.equals("none") ? null : archive);
                                accepted.put(source, new Accepted(version, Path.of(input.stagedSourcePath)));
                                dispatcher.dispatchPending();
                            } catch (InterruptedException e) { throw e; }
                            catch (Exception e) { System.err.println("Ingest failed for " + source + ": " + e); }
                        }
                        dispatcher.dispatchPending();
                    } catch (InterruptedException e) { throw e; }
                    catch (Exception e) { System.err.println("Pipeline poll failed: " + e); }
                    Thread.sleep(pollMs);
                }
                return 0;
            } finally {
                readiness.shutdownNow();
                try {
                    readiness.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
                    PipelineHeartbeat.clear(work);
                } finally {
                    factory.shutdownNow(); service.shutdownNow();
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) {}
                }
            }
        }
    }
    @Command(name="itunes-index", mixinStandardHelpOptions=true, description="Index a read-only iTunes XML export, once or on a Temporal schedule.")
    public static final class ItunesIndex implements Callable<Integer> {
        @Option(names="--database") Path database = Path.of(env("VIDYOOP_DATABASE", "/db/catalog.db"));
        @Option(names="--xml") Path xml = Path.of(env("VIDYOOP_ITUNES_XML", "/itunes-lib/iTunes Library.xml"));
        @Option(names="--serve") boolean serve;
        @Option(names="--temporal") String target = env("VIDYOOP_TEMPORAL", "localhost:7233");
        @Option(names="--namespace") String namespace = env("VIDYOOP_NAMESPACE", "default");
        @Option(names="--queue") String queue = env("VIDYOOP_ITUNES_INDEX_QUEUE", "viddyoop-itunes-index");
        @Option(names="--schedule-id") String scheduleId = env("VIDYOOP_ITUNES_INDEX_SCHEDULE", "viddyoop-itunes-index");
        @Option(names="--interval-minutes", defaultValue="15") long intervalMinutes;
        @Spec Model.CommandSpec spec;

        @Override public Integer call() throws Exception {
            Files.createDirectories(database.toAbsolutePath().getParent());
            var indexer = new com.rkuo.viddyoop.core.itunes.ItunesIndexer(database);
            if (!serve) {
                var result = indexer.index(xml, () -> {});
                spec.commandLine().getOut().println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));
                return 0;
            }
            if (intervalMinutes < 1 || queue.isBlank() || scheduleId.isBlank()) throw new IllegalArgumentException("Invalid index schedule configuration");
            try (FileChannel owner = FileChannel.open(database.resolveSibling(database.getFileName() + ".itunes-index.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = owner.tryLock()) {
                if (lock == null) throw new IllegalStateException("An iTunes index worker already owns this catalog");
                var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
                var client = WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
                var factory = WorkerFactory.newInstance(client);
                var worker = factory.newWorker(queue, WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(1).build());
                worker.registerWorkflowImplementationTypes(com.rkuo.viddyoop.core.itunes.ItunesIndexWorkflowImpl.class);
                worker.registerActivitiesImplementations(new com.rkuo.viddyoop.core.itunes.ItunesIndexActivity() {
                    public com.rkuo.viddyoop.core.itunes.ItunesIndexer.Result index() {
                        try { return indexer.index(xml, () -> io.temporal.activity.Activity.getExecutionContext().heartbeat("Indexing iTunes export")); }
                        catch (Exception e) { throw new IllegalStateException("iTunes indexing failed", e); }
                    }
                });
                Thread shutdown = new Thread(() -> { factory.shutdownNow(); service.shutdownNow(); }, "itunes-index-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                try {
                    TemporalCatalogBinding.verify(database, service, namespace);
                    factory.start();
                    var schedules = io.temporal.client.schedules.ScheduleClient.newInstance(service,
                            io.temporal.client.schedules.ScheduleClientOptions.newBuilder().setNamespace(namespace).build());
                    com.rkuo.viddyoop.core.itunes.ItunesIndexSchedule.ensure(schedules, scheduleId, queue, java.time.Duration.ofMinutes(intervalMinutes));
                    new java.util.concurrent.CountDownLatch(1).await();
                    return 0;
                } finally {
                    factory.shutdownNow(); service.shutdownNow();
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) {}
                }
            }
        }
    }

    @Command(name="health", mixinStandardHelpOptions=true, description="Check worker liveness and Temporal namespace readiness.")
    public static final class Health implements Callable<Integer> {
        @Option(names="--work") Path work = Path.of(env("VIDYOOP_WORK", "/work"));
        @Option(names="--temporal") String target = env("VIDYOOP_TEMPORAL", "localhost:7233");
        @Option(names="--namespace") String namespace = env("VIDYOOP_NAMESPACE", "default");
        @Override public Integer call() {
            if (!PipelineHeartbeat.fresh(work, System.currentTimeMillis())) return 1;
            var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target)
                    .setRpcTimeout(java.time.Duration.ofSeconds(3))
                    .setRpcRetryOptions(RpcRetryOptions.newBuilder().setMaximumAttempts(1).build()).build());
            try {
                service.blockingStub().withDeadlineAfter(3, java.util.concurrent.TimeUnit.SECONDS).describeNamespace(
                        io.temporal.api.workflowservice.v1.DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build());
                return 0;
            } catch (RuntimeException e) { return 1; }
            finally { service.shutdownNow(); }
        }
    }

    @Command(name="reencode", mixinStandardHelpOptions=true,
            description="Register a new encode from a retained snapshot; the running pipeline dispatches it.")
    public static final class Reencode implements Callable<Integer> {
        @Option(names="--database") Path database = Path.of(env("VIDYOOP_DATABASE", "/db/catalog.db"));
        @Option(names="--library") Path library = Path.of(env("VIDYOOP_LIBRARY", "/library"));
        @Option(names="--submission", required=true) String submission;
        @Option(names="--request-key", required=true) String requestKey;
        @Option(names="--output", required=true, description="New filename directly inside the configured library.") Path output;
        @Spec Model.CommandSpec spec;

        @Override public Integer call() throws Exception {
            if (!Files.isRegularFile(database)) throw new IllegalArgumentException("Catalog does not exist: " + database);
            if (requestKey.isBlank()) throw new IllegalArgumentException("Request key must not be blank");
            if (output.isAbsolute() || output.getNameCount() != 1 || output.toString().equals(".") || output.toString().equals("..")
                    || !output.toString().toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                throw new IllegalArgumentException("Output must be a single .mp4 filename");
            }
            var catalog = new SubmissionCatalog(database);
            var original = catalog.find(submission);
            if (original == null) throw new IllegalArgumentException("Unknown submission: " + submission);
            Path destination = library.toAbsolutePath().normalize().resolve(output);
            if (destination.equals(Path.of(original.finalPath).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Re-encode requires a different output name");
            }
            if (original.stagedSourcePath == null || original.stagedSourcePath.equals(original.sourcePath)) {
                throw new IllegalArgumentException("Submission has no owned source snapshot");
            }
            Path snapshot = Path.of(original.stagedSourcePath);
            if (!original.sourceFingerprint.equals(MediaFiles.checksum(snapshot, () -> {}))) {
                throw new IllegalArgumentException("Source snapshot fingerprint mismatch");
            }
            // A re-encode owns no inbox generation: it must never clean up a later arrival.
            var registered = catalog.register(Path.of(original.sourcePath), original.sourceFingerprint, original.encodeProfile,
                    "reencode:" + submission + ":" + requestKey, destination, original.precedence, snapshot, null,
                    original.archivePolicy, original.archiveRoot == null ? null : Path.of(original.archiveRoot));
            spec.commandLine().getOut().println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(registered));
            return 0;
        }
    }

    @Command(name="status", mixinStandardHelpOptions=true, description="Read catalog artifacts and Temporal execution status.")
    public static final class Status implements Callable<Integer> {
        @Option(names="--database") Path database = Path.of(env("VIDYOOP_DATABASE", "/db/catalog.db"));
        @Option(names="--temporal") String target = env("VIDYOOP_TEMPORAL", "localhost:7233");
        @Option(names="--namespace") String namespace = env("VIDYOOP_NAMESPACE", "default");
        @Option(names="--submission") String submission;
        @Option(names="--limit", defaultValue="100") int limit;
        @Option(names="--json") boolean json;
        @Option(names="--offline", description="Read catalog only; do not contact Temporal.") boolean offline;
        @Spec Model.CommandSpec spec;

        public record Row(com.rkuo.viddyoop.core.status.StatusCatalog.Submission submission,
                          com.rkuo.viddyoop.core.status.WorkflowStatus.Execution execution) {}

        @Override public Integer call() throws Exception {
            var records = new com.rkuo.viddyoop.core.status.StatusCatalog().read(database, submission, limit);
            if (submission != null && records.isEmpty()) {
                spec.commandLine().getErr().println("Unknown submission: " + submission);
                return 1;
            }
            WorkflowServiceStubs service = offline ? null : WorkflowServiceStubs.newServiceStubs(
                    WorkflowServiceStubsOptions.newBuilder().setTarget(target).setRpcTimeout(java.time.Duration.ofSeconds(5))
                            .setRpcRetryOptions(RpcRetryOptions.newBuilder().setMaximumAttempts(1).build()).build());
            List<Row> rows = new ArrayList<>();
            int exit = 0;
            try {
                var probe = offline ? null : new com.rkuo.viddyoop.core.status.WorkflowStatus(WorkflowClient.newInstance(service,
                        WorkflowClientOptions.newBuilder().setNamespace(namespace).build()));
                com.rkuo.viddyoop.core.status.WorkflowStatus.Execution unavailable = null;
                for (var record : records) {
                    var execution = unavailable != null ? unavailable : offline ? new com.rkuo.viddyoop.core.status.WorkflowStatus.Execution("OFFLINE", "", "")
                            : probe.inspect(record);
                    rows.add(new Row(record, execution));
                    if (execution.state().equals("UNAVAILABLE")) { exit = 2; unavailable = execution; }
                }
            } finally { if (service != null) service.shutdownNow(); }
            var out = spec.commandLine().getOut();
            if (json) out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rows));
            else {
                out.println("SUBMISSION\tRUN_ID\tSTATE\tSTAGE\tARTIFACT\tIMPORT\tSCRATCH_BYTES\tSOURCE\tDESTINATION\tFAILURE");
                for (var row : rows) out.println(String.join("\t", row.submission().id(), row.execution().runId(), row.execution().state(),
                        clean(row.execution().stage()), row.submission().artifactState(), row.submission().delivery()==null?"no-receipt":row.submission().delivery().outcome(), Long.toString(row.submission().scratchReservedBytes()), clean(row.submission().source()),
                        clean(row.submission().destination()), clean(row.execution().failure())));
            }
            return exit;
        }
        private static String clean(String value) { return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '); }
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
