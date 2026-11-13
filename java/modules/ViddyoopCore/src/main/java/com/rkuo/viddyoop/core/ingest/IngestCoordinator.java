package com.rkuo.viddyoop.core.ingest;

import com.rkuo.viddyoop.core.catalog.SubmissionCatalog;
import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Staging precedes registration, so every pending workflow has a complete source snapshot. */
public final class IngestCoordinator {
    @FunctionalInterface public interface Validator {
        void validate(SourceStager.Snapshot snapshot) throws IOException, InterruptedException;
    }
    private final Validator validator;
    private final SourceStager stager;
    private final SubmissionCatalog catalog;

    public IngestCoordinator(SourceStager stager, SubmissionCatalog catalog) {
        this(stager, catalog, snapshot -> {});
    }

    public IngestCoordinator(SourceStager stager, SubmissionCatalog catalog, Validator validator) {
        this.validator = validator;
        this.stager = stager;
        this.catalog = catalog;
    }

    /** requestKey is "discovery" for scanning, or a stable user request ID for re-encoding. */
    public PipelineInput accept(Path source, String profile, String requestKey, Path destination, int precedence)
            throws IOException, SQLException, InterruptedException {
        return accept(source, profile, requestKey, destination, precedence, null, null);
    }

    public PipelineInput accept(Path source, String profile, String requestKey, Path destination, int precedence,
                                String archivePolicy, Path archiveRoot) throws IOException, SQLException, InterruptedException {
        if (requestKey == null || requestKey.isBlank()) throw new IllegalArgumentException("requestKey is required");
        SourceStager.Snapshot snapshot = stager.stage(source);
        validator.validate(snapshot);
        return catalog.register(snapshot.original(), snapshot.fingerprint(), profile,
                snapshot.generation() + ":" + requestKey, destination, precedence, snapshot.staged(), snapshot.generation(), archivePolicy, archiveRoot);
    }
}
