package com.rkuo.viddyoop.core.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkuo.viddyoop.core.catalog.SubmissionCatalog;
import com.rkuo.viddyoop.core.media.MediaFiles;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.*;

/** File-boundary evidence only: uncertain remounted inputs never become new submissions automatically. */
public final class SharedInboxGuard {
    public record State(String inbox, String filesystem, String epoch) {}
    public record Status(boolean paused, String reason, List<String> sources) {}
    private final Path inbox, realInbox, work, receipt, report;
    private final SubmissionCatalog catalog;
    private final SourceFilesystem.Reader filesystem;
    private final ObjectMapper json = new ObjectMapper();
    private State baseline;
    private Status last;

    public SharedInboxGuard(Path inbox, Path work, SubmissionCatalog catalog) throws IOException {
        this(inbox, work, catalog, SourceFilesystem::inspect);
    }
    SharedInboxGuard(Path inbox, Path work, SubmissionCatalog catalog, SourceFilesystem.Reader filesystem) throws IOException {
        this.inbox = inbox.toAbsolutePath().normalize(); this.realInbox = inbox.toRealPath();
        this.work = work.toRealPath(); this.catalog = catalog; this.filesystem = filesystem;
        receipt = this.work.resolve(".ingest-epoch.json"); report = this.work.resolve(".ingest-reconcile.json");
        if (Files.isSymbolicLink(receipt) || Files.isSymbolicLink(report)) throw new IOException("Ingest identity metadata cannot be a symlink");
        if (Files.exists(receipt)) {
            baseline = json.readValue(receipt.toFile(), State.class);
            if (baseline.inbox() == null || baseline.filesystem() == null || baseline.epoch() == null) throw new IOException("Incomplete ingest identity metadata");
        }
        validate(filesystem.read(this.inbox));
    }
    private void validate(SourceFilesystem.Stamp current) throws IOException {
        if (baseline != null && (!baseline.inbox().equals(inbox.toString()) || !baseline.filesystem().equals(current.type()))) {
            throw new IOException("Inbox binding changed; restore the configured mount before starting this work tree");
        }
    }
    public Status check() throws IOException, SQLException {
        var current = filesystem.read(inbox); validate(current);
        boolean changed = baseline == null || !baseline.epoch().equals(current.epoch());
        if (!current.epoch().isEmpty() && changed) {
            List<String> remaining = new ArrayList<>();
            for (Path source : catalog.registeredSourcePaths()) {
                try {
                    if (source.getParent().toRealPath().equals(realInbox)) {
                        Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        remaining.add(source.toString());
                    }
                } catch (NoSuchFileException absent) { /* Absence is safe; permission/IO failures are not. */ }
            }
            if (!remaining.isEmpty()) {
                var status = new Status(true, "Shared inbox identity changed; review/quarantine registered sources before new ingestion. Existing workflows continue.", List.copyOf(remaining));
                if (!status.equals(last)) write(report, status);
                last = status; return status;
            }
        }
        if (!current.epoch().isEmpty() && changed) {
            var adopted = new State(inbox.toString(), current.type(), current.epoch());
            write(receipt, adopted); baseline = adopted;
        }
        if (Files.deleteIfExists(report)) MediaFiles.sync(work, true);
        last = new Status(false, "", List.of()); return last;
    }
    private void write(Path target, Object value) throws IOException {
        Path temporary = Files.createTempFile(work, ".ingest-state-", ".tmp");
        try {
            json.writeValue(temporary.toFile(), value); MediaFiles.sync(temporary, false);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            MediaFiles.sync(work, true);
        } finally { Files.deleteIfExists(temporary); }
    }
}
