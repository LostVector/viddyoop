package com.rkuo.viddyoop.core.ingest;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Detects "size-stable" files in an inbox directory — the old collector
 * heuristic: a file is only ready when its size has not changed between
 * scans and a minimum settle delay has elapsed since it was first seen
 * stable. This is what prevents grabbing files that are still downloading.
 *
 * Detection is pure (no Temporal dependency) so it is unit-testable; the
 * caller starts workflows for the returned candidates.
 */
public class IngestWatcher {

    private static class Sighting {
        long size;
        long firstSeenStableMs;
    }

    private final Map<Path, Sighting> seen = new HashMap<Path, Sighting>();

    /**
     * Scans the inbox once. Returns files that are complete: same size as the
     * previous scan and stable for at least minStableMs. Files with a ".tmp"
     * suffix or an extension outside allowedExtensions are ignored.
     */
    public List<Path> scanOnce(Path inbox, Set<String> allowedExtensions, long nowMs, long minStableMs) throws IOException {
        List<Path> ready = new ArrayList<Path>();
        Set<Path> present = new HashSet<Path>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inbox)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }

                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".tmp")) {
                    continue;
                }

                boolean extensionOk = false;
                for (String ext : allowedExtensions) {
                    if (name.endsWith(ext)) {
                        extensionOk = true;
                        break;
                    }
                }
                if (!extensionOk) {
                    continue;
                }

                present.add(path);
                long size = Files.size(path);
                Sighting sighting = seen.get(path);

                if (sighting == null || sighting.size != size) {
                    sighting = new Sighting();
                    sighting.size = size;
                    sighting.firstSeenStableMs = nowMs;
                    seen.put(path, sighting);
                    continue;
                }

                if (nowMs - sighting.firstSeenStableMs >= minStableMs) {
                    ready.add(path);
                }
            }
        }

        // files that vanished (moved away, deleted) stop being tracked
        seen.keySet().retainAll(present);
        return ready;
    }

    /** Stops tracking a file (call after a workflow has been started for it). */
    public void forget(Path path) {
        seen.remove(path);
    }
}
