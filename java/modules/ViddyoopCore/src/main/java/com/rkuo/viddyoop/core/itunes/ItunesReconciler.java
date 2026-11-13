package com.rkuo.viddyoop.core.itunes;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog;
import com.rkuo.viddyoop.core.media.MediaFiles;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.Objects;
import static com.rkuo.viddyoop.core.itunes.ItunesDeliveryCatalog.Outcome.*;

/** Positive content/file evidence only. Never moves, deletes, or edits iTunes media. */
public final class ItunesReconciler {
    private final ArtifactCatalog artifacts;
    private final ItunesDeliveryCatalog receipts;
    private final ItunesCatalog index;
    private final Path hostMediaRoot, mediaRoot, rejected;
    public ItunesReconciler(ArtifactCatalog artifacts, ItunesDeliveryCatalog receipts, ItunesCatalog index,
                            Path hostMediaRoot, Path mediaRoot, Path rejected) throws IOException {
        if ((hostMediaRoot == null) != (mediaRoot == null)) throw new IllegalArgumentException("Both host and mounted media roots are required");
        if (hostMediaRoot != null && !hostMediaRoot.isAbsolute()) throw new IllegalArgumentException("Host media root must be absolute");
        this.artifacts = artifacts; this.receipts = receipts; this.index = index;
        this.hostMediaRoot = hostMediaRoot == null ? null : hostMediaRoot.toAbsolutePath().normalize();
        this.mediaRoot = mediaRoot == null ? null : mediaRoot.toRealPath();
        this.rejected = rejected == null ? null : rejected.toRealPath();
        if ((this.mediaRoot != null && !Files.isDirectory(this.mediaRoot)) || (this.rejected != null && !Files.isDirectory(this.rejected))) {
            throw new IOException("Media and rejection roots must be directories");
        }
    }
    public ItunesDeliveryCatalog.Delivery reconcile(String id, Runnable heartbeat) throws IOException, SQLException {
        var receipt = receipts.find(id);
        var artifact = artifacts.expected(id);
        if (receipt == null || artifact == null || !artifact.committed() || !receipt.checksum().equals(artifact.checksum())
                || !receipt.artifactPath().equals(artifact.destination())) throw new IOException("No matching committed delivery receipt");
        if (receipt.outcome() == CONFIRMED || receipt.outcome() == REJECTED) return receipt;
        Path watched = Path.of(receipt.watchedPath());
        if (rejected != null) {
            try (var paths = Files.find(rejected, 3, (path, attrs) -> attrs.isRegularFile() && path.getFileName().equals(watched.getFileName()))) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path file = iterator.next();
                    if (stableMatch(file, rejected, artifact.checksum(), heartbeat)) {
                        receipts.observe(id, REJECTED, "Matching delivery copy observed in configured rejection folder");
                        return receipts.find(id);
                    }
                }
            }
        }
        if (Files.exists(watched, LinkOption.NOFOLLOW_LINKS)) {
            if (stableMatch(watched, watched.getParent().toRealPath(), artifact.checksum(), heartbeat)) {
                receipts.publishIntent(id); receipts.delivered(id);
            } else receipts.observe(id, AMBIGUOUS, "Watched entry changed; no outcome inferred");
            return receipts.find(id);
        }
        if (mediaRoot != null && index != null) {
            var lookup = index.lookupDuration(artifact.durationMs(), 1000);
            Long handoff = receipt.publishIntentAt() == null ? receipt.deliveredAt() : receipt.publishIntentAt();
            boolean freshForHandoff = lookup.index() != null && (handoff == null
                    || (lookup.index().indexedAt() >= handoff && lookup.index().sourceModifiedAt() != null
                    && lookup.index().sourceModifiedAt() >= handoff));
            if (freshForHandoff) {
                for (var item : lookup.candidates()) {
                    Path file = mapped(item.location());
                    if (file != null && !inOwnedTree(file, Path.of(receipt.stagedPath()).getParent())
                            && !inOwnedTree(file, watched.getParent())
                            && stableMatch(file, mediaRoot, artifact.checksum(), heartbeat)) {
                        receipts.confirmed(id, lookup.index(), item); return receipts.find(id);
                    }
                }
            }
        }
        if (receipt.publishIntentAt() != null || receipt.deliveredAt() != null) {
            receipts.observe(id, AMBIGUOUS, "Watched name absent; no verified index/rejection evidence, so no redelivery");
        }
        return receipts.find(id);
    }
    private boolean inOwnedTree(Path file, Path ownedRoot) throws IOException {
        for (Path parent = file.getParent(); parent != null && parent.startsWith(mediaRoot); parent = parent.getParent()) {
            if (Files.isDirectory(parent) && Files.exists(ownedRoot) && Files.isSameFile(parent, ownedRoot)) return true;
        }
        return false;
    }

    private Path mapped(String location) {
        if (location == null) return null;
        try {
            URI uri = URI.create(location);
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getHost() != null && !uri.getHost().equalsIgnoreCase("localhost"))) return null;
            Path host = Path.of(uri.getPath()).normalize();
            if (!host.isAbsolute() || !host.startsWith(hostMediaRoot)) return null;
            Path mapped = mediaRoot.resolve(hostMediaRoot.relativize(host)).normalize();
            return mapped.startsWith(mediaRoot) ? mapped : null;
        } catch (IllegalArgumentException e) { return null; }
    }
    private static boolean stableMatch(Path file, Path root, String checksum, Runnable heartbeat) throws IOException {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(root)) return false;
            var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String actual = MediaFiles.checksum(file, heartbeat);
            var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return checksum.equals(actual) && Objects.equals(before.fileKey(), after.fileKey())
                    && before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime());
        } catch (NoSuchFileException | AccessDeniedException e) { return false; }
    }
}
