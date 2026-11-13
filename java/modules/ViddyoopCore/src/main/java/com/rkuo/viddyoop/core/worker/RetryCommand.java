package com.rkuo.viddyoop.core.worker;

import com.rkuo.viddyoop.core.status.StatusCatalog;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name="retry", mixinStandardHelpOptions=true,
        description="Resume a failed pipeline at its failed activity after repair; completed stages replay from history.")
public final class RetryCommand implements Callable<Integer> {
    @Option(names="--database") Path database = Path.of(env("VIDYOOP_DATABASE", "/db/catalog.db"));
    @Option(names="--temporal") String target = env("VIDYOOP_TEMPORAL", "localhost:7233");
    @Option(names="--namespace") String namespace = env("VIDYOOP_NAMESPACE", "default");
    @Option(names="--submission", required=true) String submission;
    @Option(names="--run-id", required=true, description="Failed current run ID from Temporal; prevents retrying stale history.") String runId;
    @Option(names="--reason", required=true) String reason;
    @Option(names="--dry-run", description="Inspect the reset point without changing catalog, files, or Temporal state.") boolean dryRun;
    @Spec Model.CommandSpec spec;

    @Override public Integer call() throws Exception {
        UUID.fromString(submission); UUID.fromString(runId);
        if (reason.isBlank()) throw new IllegalArgumentException("A repair reason is required");
        if (!Files.isRegularFile(database)) throw new IllegalArgumentException("Catalog does not exist: " + database);
        database = database.toRealPath();
        if (dryRun) return execute();
        try (FileChannel owner = FileChannel.open(database.resolveSibling(database.getFileName() + ".retry.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = owner.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another retry command owns this catalog");
            return execute();
        }
    }
    private int execute() throws Exception {
        var rows = new StatusCatalog().read(database, submission, 1);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown submission: " + submission);
        var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target)
                .setRpcTimeout(Duration.ofSeconds(5)).setRpcRetryOptions(RpcRetryOptions.newBuilder().setMaximumAttempts(1).build()).build());
        try {
            var client = WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
            var result = new FailedPipelineRetry(client).retry(rows.get(0), runId, reason, dryRun);
            spec.commandLine().getOut().println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));
            return 0;
        } finally { service.shutdownNow(); }
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
