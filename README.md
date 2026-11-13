viddyoop
======

Distributed video analysis and transcoding system leveraging Hadoop

======

The MIT License (MIT)

Copyright (c) 2014 Richard Kuo

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

======

Developer/Build requirements
- JDK 17 or later
- Maven 3.9 or later

To build:

- Run `mvn package` from the repository root.
- The runnable fat jars are written to each module's `target/` directory:
  - `java/modules/HBXFileBrokerExe/target/HBXFileBroker.jar`
  - `java/modules/HBXFileCollectorExe/target/HBXFileCollector.jar`
  - `java/modules/HBXJobPreprocessor/target/HBXJobPreprocessor.jar`
  - `java/modules/HBXMapReduce/target/HBXMapReduce.jar`
  - `java/modules/HBXJobSubmitterExe/target/HBXJobSubmitter.jar`
- Sample launch scripts and configuration files live under
  `java/modules/HBXJobSubmitterExe/resources/`.


Container deployment (ARM64 smoke verified)
------------------------------------------

The Dockerfile targets Debian Bookworm with HandBrake 1.6.1, MKVToolNix 74,
FFmpeg 5.1.9, and Java 17. Base images and Temporal CLI 1.10.0 are pinned by
registry digest; media package versions are explicit build arguments. Java and
transitive system packages use Bookworm's maintained repositories. The image
builds only the new core jar and runs as UID/GID 1000. The image build and Compose
smoke test pass in a disposable Linux ARM64 VM using rootless Docker 29.9.3 and
Compose 5.7.1. Container recovery and synthetic-profile checks are described below;
actual host integration and representative movies remain release gates.

With Docker Compose available and the host directories writable by UID 1000:

```sh
mkdir -p local/inbox local/library local/archive
docker compose up -d --build
docker compose exec pipeline java -jar /app/viddyoop.jar status --json
```

Host paths can be set with `VIDYOOP_INBOX_DIR`, `VIDYOOP_LIBRARY_DIR`, and
`VIDYOOP_ARCHIVE_DIR`; `VIDYOOP_IMAGE` selects an image tag. Work, catalog, and
Temporal data use separate named volumes. Temporal API/UI bind to loopback on
ports 7233/8233; override `VIDYOOP_TEMPORAL_PORT`/`VIDYOOP_TEMPORAL_UI_PORT` when
needed. `docker compose down` preserves named volumes. The image's `health`
command checks a fresh worker liveness signal plus Temporal namespace readiness,
independently of job success or failure.

Compose sets configurable service budgets:

| Service | CPU limit | Memory limit | JVM maximum heap |
|---|---|---|---|
| Temporal | 1 | 1 GiB | — |
| Pipeline and media subprocesses | 2 | 4 GiB | 512 MiB |
| Optional indexer | 0.5 | 512 MiB | 128 MiB |
| Optional importer | 0.5 | 1 GiB | 256 MiB |

Override `VIDYOOP_TEMPORAL_CPUS`/`MEMORY`, `VIDYOOP_PIPELINE_CPUS`/`MEMORY`,
`VIDYOOP_ITUNES_INDEX_CPUS`/`MEMORY`, and `VIDYOOP_ITUNES_IMPORT_CPUS`/`MEMORY`
(each pair shares its full prefix). JVM options use the corresponding
`VIDYOOP_PIPELINE_JAVA_OPTIONS`, `VIDYOOP_ITUNES_INDEX_JAVA_OPTIONS`, and
`VIDYOOP_ITUNES_IMPORT_JAVA_OPTIONS`; keep heap below the service memory limit to
leave room for native allocations, threads, and media tools. Each Java service
has a 64 MiB `/tmp` tmpfs with executable mappings for SQLite's extracted native
library, also charged to its memory budget. These are initial
trial limits, not measured capacity for representative movies. Size worker counts,
limits, Docker VM memory, and host headroom together; limits are ceilings rather
than reservations. Scratch-space admission remains a separate filesystem check.

The Container CI workflow builds and runs `scripts/container-smoke.sh`, checking
HandBrake 1.6, real ingest/remux/encode/publication/cleanup, AAC/AC3 surround and
text subtitle output, status, readiness, and the configured resource limits
(including the pipeline's actual cgroup/tmpfs settings). Only the verified image is published
to GHCR on a push to the default branch, tagged `sha-<commit>` and `main`.
Locally, after `docker build -t viddyoop:test .`, run:

```sh
scripts/container-smoke.sh viddyoop:test
```

The broader generated media check runs through the same packaged pipeline:

```sh
scripts/container-media-matrix.sh viddyoop:test
```

It passes on ARM64 with media on a macOS VirtioFS share. Cases cover 1080p DTS
surround, interlaced MPEG-2 transport streams, 10-bit HEVC with ASS subtitles, and
video without audio. Checks decode every output and verify dimensions,
progressive video, AAC/AC3 channels, subtitle text/language, duration, completed
submissions and source cleanup. Reports retain input/output probes, status,
image/config metadata and service logs. An optional second argument selects a new
results directory. CI runs this matrix before publication. These short generated
fixtures do not replace a representative trial with the actual collection.

The database-backed deployment candidate is in `docker-compose.server.yml`.
Its isolated smoke uses an automatically generated fixture password file:

```sh
scripts/container-smoke.sh viddyoop:test server
```

The check passes on ARM64. It uses Temporal Server 1.32.2 and PostgreSQL 16,
pinned by registry digest, with explicit schema/namespace jobs and the same
pipeline image. The check also
restarts PostgreSQL, Temporal and the worker, then reruns setup jobs against the
populated database and compares submission, run and artifact identities. This
candidate also passes the full 125-test core suite with PostgreSQL as its external
workflow endpoint, including worker interruption and failed-stage repair. Use
the explicit `server` mode for its database-backup/restore drill. A direct launch requires
`VIDYOOP_POSTGRES_PASSWORD_FILE`; no production password is provided in the repo.
New namespaces retain closed histories for 30 days by default
(`VIDYOOP_HISTORY_RETENTION`); existing namespace retention is preserved. Catalog
and media retention are separate. Once a closed history expires it cannot be
reset by `retry`; retained inputs can instead support an explicit new `reencode`.
Use separate project/data paths from the dev stack; its SQLite histories cannot
be reused by a new PostgreSQL namespace.

Pipeline, scheduled index and import workers bind a catalog to the namespace's
UUID at startup. Recreating a backend with the same namespace name produces a
different UUID; the workers reject it before starting work. `temporal_binding`
is catalog ownership metadata and belongs in the same backup as workflow history.
A catalog with existing registrations and no binding also fails startup, since a
registration may have reached Temporal before its local acknowledgement. Recover
the original backend and reconcile such a catalog before adoption; there is no
automatic binding bypass. Stop all workers before replacing a backend. This
startup check is not an atomic barrier against changing a live server mid-request.

Run the complete core recovery suite on the same pinned runtime with:

```sh
docker build --target verification -t viddyoop:verification .
scripts/container-recovery.sh viddyoop:test viddyoop:verification
```

To select the PostgreSQL endpoint, supply a new results directory and `server`
as the fourth argument:

```sh
scripts/container-recovery.sh viddyoop:test viddyoop:verification ./local/postgres-recovery-results server
scripts/container-restore.sh viddyoop:test viddyoop:verification ./local/postgres-restore-results server
```

The suite's `ColdRestoreTest` owns a separate SQLite dev server; the second
command supplies the distinct PostgreSQL-cluster backup/restore proof. The
PostgreSQL drill stops the whole cluster and checks its archived control state.
It passes on ARM64 with the guarded runtime, preserving the namespace UUID and
original submission/run, verifying one artifact/stat and source/output bytes, and
releasing scratch. Operational workload sizing, upgrades, retention and host-app
validation remain open.

The opt-in verification image adds Maven/compiler/test inputs to the runtime;
the default image remains free of those tools. The runner checks that both images
contain the same application jar, uses the exact pinned Temporal server binary,
and supplies a separate 32 MiB filesystem for guarded ENOSPC tests. It retains
JUnit reports, coverage, image/config metadata, and logs in a newly created
results directory (printed on exit). An optional third argument selects a new
results directory; it must not already exist. No core tests may be skipped.
The default ENOSPC target is a 32 MiB tmpfs. To repeat on another disposable
filesystem, set `VIDYOOP_RECOVERY_FULLFS_DIR` to its existing mount directory.
The tests refuse to fill a filesystem larger than 64 MiB; never use a mount
holding other work. The recovery fixture explicitly truncates its filler before
unlinking it: VirtioFS can retain host descriptors after guest deletion, delaying
block reclamation. Operational recovery must verify actual free space too.

For crash checks with the normal heartbeat/backoff policies, run:

```sh
VIDYOOP_RECOVERY_PRODUCTION_TIMEOUTS=true \
  scripts/container-recovery.sh viddyoop:test viddyoop:verification
```

This mode runs only the two worker-crash cases and checks their recorded timeout
settings. Expect roughly 15 minutes for the ten-minute encode heartbeat and
two-minute publish heartbeat plus retries. The full suite normally shortens
those policies for test speed. The Container CI workflow runs the full suite on
pull requests; default-branch pushes also run production-timeout crash checks
and the Compose restore drill before publishing. A separate default-branch job
loads the same runtime/verification images and checks PostgreSQL cold restore
before GHCR publication. Both images travel through the short-lived CI artifact;
only the runtime image is pushed to GHCR.

To exercise actual Compose volumes rather than only a test-process filesystem:

```sh
scripts/container-restore.sh viddyoop:test viddyoop:verification
```

This separate disposable project kills the worker container during encoding,
stops Temporal, archives all six persistence/media roots, removes their contents,
and restores them at the same container paths. It quarantines the restored
original before discovery resumes and waits through the production retry policy.
It checks the original submission/run identity, one artifact/stat row, source
snapshot and library checksums, released scratch allocation, and service health.
Allow roughly 15 minutes. Backup/status evidence is retained in a fresh results
directory; an optional third argument selects that directory. See the
[recovery runbook](docs/recovery.md) for the operational procedure.
This drill also passes with inbox/library/archive on a macOS VirtioFS share;
set `TMPDIR` to a disposable shared directory visible at the same path inside
the Docker VM to reproduce that storage boundary.

The smoke script uses disposable directories, volumes, a unique Compose project,
and random loopback host ports, then removes those test resources. The optional
iTunes index and import services are prepared behind separate opt-in profiles.
The base smoke, full 125-test core suite, production-timeout crash cases, six-root
Compose restore, and synthetic optional profile pass on Linux ARM64. The latest
125-test suite passes with either backend; the earlier 119-test baseline also passes with
its ENOSPC filesystem shared from macOS. Check the
optional profile separately with:

```sh
scripts/container-itunes.sh viddyoop:test
```

It uses only generated media and a synthetic XML/app directory tree, verifies
importer restart plus scheduled refresh and rejection, and retains status/mount
evidence. It never accesses a real host library. On a controlled macOS VirtioFS
mount, both this synthetic profile and the pipeline smoke pass; native macOS
`ffprobe` can read the private UID-1000 container output, mapped to host UID 501.
Actual app consumption/rejection/export remains unverified. Other Docker sharing
backends and ACL configurations require their own check; directory writability
alone does not prove a native app can read a delivered copy.

`scripts/container-remount.sh` provides a two-phase restart trial for an isolated
Docker VM with a disposable writable host share. Run it inside that VM, using a
new fixture directory on the share:

```sh
scripts/container-remount.sh prepare /path/on/disposable-share/restart-trial
# Reboot the isolated Docker VM, then use the same directory and images:
scripts/container-remount.sh verify /path/on/disposable-share/restart-trial
scripts/container-remount.sh cleanup /path/on/disposable-share/restart-trial
```

It registers one generated source before workflow start, verifies the same
submission completes after reboot without deleting the original or admitting it
twice, then checks quarantine and automatic intake resumption. Cleanup removes
only the trial's containers, volumes and media; JSON/log evidence remains. It
does not test a real host app or an active workflow during VM power loss.

V2 pipeline (in development)
----------------------------

The migration target and remaining recovery gates are in
[docs/architecture-v2.md](docs/architecture-v2.md). The legacy executables remain
available until those gates pass.

Build the executable pipeline jar with:

```sh
mvn -pl java/modules/ViddyoopCore -am package
```

With Temporal running on `localhost:7233` and HandBrakeCLI installed, start a
local worker (choose separate inbox, work, and library directory trees):

```sh
java -jar java/modules/ViddyoopCore/target/viddyoop-core-1.0.0-SNAPSHOT.jar pipeline \
  --inbox ./local/inbox --work ./local/work --library ./local/library --archive ./local/archive \
  --database ./local/db/catalog.db --handbrake /path/to/HandBrakeCLI \
  --mkvmerge /path/to/mkvmerge --mkvextract /path/to/mkvextract --ffmpeg /path/to/ffmpeg
```

`pipeline --help` lists options. Each option also has a `VIDYOOP_` environment
variable: `INBOX`, `WORK`, `LIBRARY`, `ARCHIVE`, `ARCHIVE_POLICY`, `DATABASE`, `TEMPORAL`, `NAMESPACE`, `QUEUE`,
`HANDBRAKE`, `MKVMERGE`, `MKVEXTRACT`, `FFMPEG`, `ENCODE_WORKERS`, `PREPROCESS_WORKERS`, `PUBLISH_WORKERS`, `SETTLE_MS`,
`POLL_MS`, `SCRATCH_MULTIPLIER`, and `MIN_FREE_BYTES`. Defaults include one worker per stage, a 60-second
settle interval, five-second polling, and a 10 GiB scratch-space reserve.
Each submission also reserves an estimated four times its source size before
workflow start. Reservations survive restarts and are released atomically with
successful publication; failed jobs keep their allocation for retry or review.
New estimates use `--scratch-multiplier`; existing allocations retain their
original size. Available-space checks subtract outstanding allocations from
filesystem free space, so estimates are conservative while files are being
written. This bounds admission by an estimate, not a filesystem quota. Retained
intermediates still consume actual free space after an allocation is released.

The worker validates an owned source snapshot before workflow registration and
publishes a verified MP4 into the library. Existing conflicting output is never
overwritten. After verified publication, guarded cleanup deletes only the ingested
original generation; snapshots are retained for recovery. Cleanup uses the inbox's
`.viddyoop-cleanup` directory for isolated claims and durable receipts. Interrupted
claims resume on retry; replacements are preserved/restored. If restoration
conflicts with another arrival, both files remain and the publish activity fails
for review. Older workflows without generation receipts preserve their originals. MKVs are remuxed and text subtitles are repaired (ASS/SSA is
converted with FFmpeg). Unsupported subtitle codecs require review. Optional
iTunes delivery is described below; the remaining recovery gates still apply.
Only one pipeline process may own a work tree.

Linux guest sharing filesystems (VirtioFS, 9p and FUSE) can change or recycle
synthetic file keys after a VM restart. Source generations on these mounts include
the Linux boot ID, so an old cleanup receipt cannot authorize deletion in a later
boot. If registered originals are still present after a boot change, the worker
pauses new discovery while existing workflows and pending registrations continue
from owned snapshots. It logs the pause and writes `/work/.ingest-reconcile.json`
with the affected source paths. Review those files and move them outside the inbox
for safekeeping; when none remain, discovery resumes automatically. This also
applies when upgrading a populated shared inbox without an epoch receipt. Preserve
`/work/.ingest-epoch.json` in backups. Do not delete identity metadata to bypass a
pause; byte hashes alone cannot distinguish a replacement at the same path.

The default `--archive-policy movies` preserves source snapshots longer than
75 minutes under `--archive` (default `/archive`), using `720p/` or `1080p/` when
the source exceeds 1280 pixels wide or 720 pixels high. Archive copies retain the
original filename and must pass checksum verification before publication/cleanup.
Conflicts stop the job without overwriting the archive. `--archive-policy none`
is an explicit opt-out for new submissions; registered policies are durable.
Use separate inbox, work, library, and archive directory trees. Source snapshots
remain retained even with archival enabled; automatic garbage collection is pending.

Library output strips internal `.r` suffixes, orders `-AC3-p1` consistently, and
adds `AAA-` to priority-marked filenames for Apple TV sorting without renaming
inbox media. Temporal scheduling priority remains deferred.

Inspect recent submissions with:

```sh
java -jar java/modules/ViddyoopCore/target/viddyoop-core-1.0.0-SNAPSHOT.jar status \
  --database ./local/db/catalog.db
```

Add `--json` for structured output, `--submission ID` for one submission, or
`--limit N` (default 100). `--offline` reads only the catalog. Live output combines
Temporal state, the pending activity and failure details with catalog source,
destination, artifact registration, and outstanding scratch bytes. `committed` describes the catalog receipt;
status does not rehash the media file. Missing history is reported separately from
failure. Exit codes are 0 for a successful inspection (even when a job failed), 1
for an invalid request/catalog error, and 2 when Temporal is unavailable and only
partial status can be returned. Live output also includes the current Temporal
run ID (`execution.runId` in JSON), used when retrying a failed stage.
Import receipt outcomes/notes appear in JSON;
text output includes an `IMPORT` column. These are catalog observations, not live
queries of iTunes or import workflow state. Job status is separate from service readiness.

The read-only iTunes XML index can be refreshed once:

```sh
java -jar java/modules/ViddyoopCore/target/viddyoop-core-1.0.0-SNAPSHOT.jar itunes-index \
  --database ./local/db/catalog.db --xml "/path/to/iTunes Library.xml"
```

`--serve` registers a Temporal worker and a recurring schedule (15 minutes by
default, `--interval-minutes` to change it). Initial schedule creation triggers
an immediate index. Restarting updates the configured interval/action while
preserving an operator pause and its note; overlapping index runs are skipped.
Choose distinct `--schedule-id`/`--queue` values if operating separate catalogs
in one Temporal namespace. One scheduled index worker may own a catalog at a time.

For Compose, set `VIDYOOP_ITUNES_LIBRARY_DIR` to an existing export directory and
start `docker compose --profile itunes up -d --build`. The directory is mounted
read-only. `VIDYOOP_ITUNES_XML_NAME` defaults to `iTunes Library.xml` and
`VIDYOOP_ITUNES_INDEX_INTERVAL_MINUTES` to 15. The base stack does not require an
iTunes export. This optional service's container healthcheck is disabled because
the pipeline liveness signal belongs to a different process; index freshness is
stored in `itunes_index_meta` with the library identity, source path, and export
modification time. Index read time does not prove that the host has refreshed its
XML export.

Indexing streams tracks, disables external XML resolution, and commits a full
replacement only after a complete stable export. A malformed or changing export
preserves the previous catalog; a valid empty export clears old tracks. Name and
duration matches are candidates for review, not automatic skip/delete decisions.
The index never edits the export. Import confirmation verifies exact indexed
media content via explicit host-to-container path mapping. Post-handoff
confirmation requires an export modified since the handoff; arrange for the host
application to refresh its export. Re-reading an older export cannot confirm a transfer.

To deliver or reconcile one committed artifact, use:

```sh
java -jar java/modules/ViddyoopCore/target/viddyoop-core-1.0.0-SNAPSHOT.jar itunes-import \
  --database ./local/db/catalog.db --library ./local/library --submission SUBMISSION_ID \
  --add-folder "/path/to/Automatically Add to iTunes.localized" \
  --staging /path/to/.viddyoop-staging \
  --host-media-root "/host/path/to/iTunes Media" --media-root "/mounted/path/to/iTunes Media"
```

This command performs delivery work; use `status` for read-only inspection.
The watched directory must already exist. Private staging must be outside the
watched and library trees, on the same filesystem/mount as the watched directory.
The authoritative library file remains independent of the app's copy. The host
media root must match file locations in the export; the mounted root exposes the
same files to the importer. Native operation can use the same absolute path for both.
An optional `--rejected-folder` observes matching rejected copies without deleting them.

`itunes-import --serve` discovers committed artifacts and runs one durable Temporal
workflow per artifact. It requires both media roots and waits for a completed
index before starting automatic delivery. `--check-seconds` defaults to 60 and
`--scan-seconds` to 10. Pending observations use durable timers; workflow history
continues into a new run every 100 checks. One import worker may own a catalog.
Use a distinct `--queue` for each catalog in a shared Temporal namespace.

`DELIVERED` means a complete copy reached the watched folder. `CONFIRMED` means
matching content is present in indexed media, and `REJECTED` means a matching
copy was observed in the rejection tree. Disappearance without sufficient
evidence records `AMBIGUOUS` and continues observation without sending another
copy. Confirmed/rejected receipts stop observation. Activity errors retry up to
three times; exhausted workflows require operator repair/retry and are not
automatically restarted by discovery. An explicit second-delivery mechanism is
not implemented. `--reconcile-only` observes/repairs receipts without publishing
a new watched copy, including for artifacts with no restored receipt. Use this
mode after restoring a backup, since a transfer may have happened after its cutoff.

Automatic delivery has a separate Compose opt-in. Configure these existing host paths:

- `VIDYOOP_ITUNES_LIBRARY_DIR`: directory containing the XML export.
- `VIDYOOP_ITUNES_DELIVERY_DIR`: one parent containing the watched folder and private staging.
- `VIDYOOP_ITUNES_MEDIA_DIR`: app-managed media directory, mounted read-only.
- `VIDYOOP_ITUNES_HOST_MEDIA_ROOT`: absolute media root appearing in the export's file URIs.

Then run `docker compose --profile itunes-import up -d --build`; this also enables
the indexer. `VIDYOOP_ITUNES_ADD_NAME` defaults to `Automatically Add to iTunes.localized`.
`VIDYOOP_ITUNES_REJECTED`, when set, is a path visible inside the import container.
The index-only `itunes` profile never enables delivery. Import worker healthchecking
is disabled rather than borrowing the pipeline's liveness signal; inspect receipts
and Temporal workflows. Synthetic app/export tests and a packaged CLI restart test
against a real Temporal server pass. Actual host behavior and import-container execution
still require validation before rollout.

Cold backup and restore procedures, including the native recovery drill, are in
[docs/recovery.md](docs/recovery.md). Keep import in observation-only mode after
restore with `VIDYOOP_ITUNES_RECONCILE_ONLY=true` until possible post-backup
transfers are reconciled. Restore all persistence and media roots together;
`status` alone does not verify the restored files.

After repairing an exhausted pipeline activity, preview a retry of that stage:

```sh
java -jar java/modules/ViddyoopCore/target/viddyoop-core-1.0.0-SNAPSHOT.jar retry \
  --database ./local/db/catalog.db --submission SUBMISSION_ID --run-id FAILED_RUN_ID \
  --reason "Repaired destination conflict" --dry-run
```

Use the failed current run ID from `status`, and omit `--dry-run` to apply the
previewed reset. The JSON result includes the failed activity, workflow-task
reset point, and new run ID (null for a preview). Temporal retains completed
upstream results: retrying `Publish` does not schedule another encode. Publication
still needs its encoded output or a matching published file. A catalog-committed
artifact must pass checksum verification before cleanup can be retried. Missing
intermediates do not authorize discarding completed history or overwriting media.

`retry` accepts failed video pipeline runs with a recognizable failed activity;
it rejects running/completed/canceled/terminated runs, stale run IDs, and catalog
mismatches. It preserves the submission ID and scratch allocation and never
migrates the catalog. A preview changes no state. Applying uses Temporal's reset
API with a stable request ID; this is a new run of the same workflow, not a new
ingest or re-encode request. Inspect `status` after a connection timeout because
the reset may already have happened. Reissuing an old run ID cannot rewind a
new current run through this CLI. Retry commands for one catalog are serialized;
do not concurrently reset the same workflow through another catalog copy or an
external Temporal client/UI, since the server reset API can terminate a newer run.
This command does not reset optional iTunes workflows.

Register an intentional re-encode from a retained source snapshot:

```sh
java -jar java/modules/ViddyoopCore/target/viddyoop-core-1.0.0-SNAPSHOT.jar reencode \
  --database ./local/db/catalog.db --library ./local/library \
  --submission ORIGINAL_SUBMISSION_ID --request-key movie-v2-request \
  --output movie-v2.mp4
```

Keep the request key when retrying this command; choose a new key for another
intentional encode. Output must be a new `.mp4` filename directly in the same
library configured for the worker. The command checks the snapshot fingerprint
and registers work durably; the running `pipeline` dispatches it. It does not
claim an inbox generation for cleanup, so a later arrival at the old pathname
is preserved. Existing output conflicts fail safely through publication.

Core tests normally skip real media tests. To run those as well, supply an
existing short video fixture and the executable path:

```sh
mvn -pl java/modules/ViddyoopCore -am test \
  -Dviddyoop.handbrake=/path/to/HandBrakeCLI -Dviddyoop.fixture=/path/to/fixture.mkv \
  -Dviddyoop.mkvmerge=/path/to/mkvmerge -Dviddyoop.mkvextract=/path/to/mkvextract \
  -Dviddyoop.ffmpeg=/path/to/ffmpeg
```

`DiskFullRecoveryTest` is separately opt-in. It fills only a caller-supplied
disposable filesystem and refuses filesystems larger than 64 MiB. A verified
macOS setup is:

```sh
hdiutil create -size 32m -fs HFS+ -volname viddyoop-recovery /tmp/viddyoop-recovery.dmg
mkdir -p /tmp/viddyoop-recovery-mount
hdiutil attach -nobrowse -mountpoint /tmp/viddyoop-recovery-mount /tmp/viddyoop-recovery.dmg
```

Run the media test command above with
`-Dviddyoop.fullfs=/tmp/viddyoop-recovery-mount` added. The tests verify actual
ENOSPC failures and recovery after freeing space. Detach the test image afterward:

```sh
hdiutil detach /tmp/viddyoop-recovery-mount
```

Do not point this option at a filesystem holding other work. Pinned ARM64 container
recovery passes; current evidence and remaining host integration gates are tracked
in the architecture document.

`WorkerCrashRecoveryTest` additionally requires a real, disposable Temporal dev
server. For example, with Temporal CLI 1.10.0:

```sh
temporal server start-dev --ip 127.0.0.1 --port 17233 --ui-port 18233 \
  --db-filename /tmp/viddyoop-recovery.db
```

Add `-Dviddyoop.temporal=127.0.0.1:17233` to the media test command. The tests
start and forcibly terminate their own child worker JVMs, using temporary media
and random workflow IDs. They verify encoding recovery and interruption between
filesystem publication and catalog commit. Test heartbeat/backoff intervals are
shortened; production defaults remain unchanged. Stop the disposable server when
finished. The Java test server is not used for this abrupt-client-loss gate.

After packaging, `RunnablePipelineTest` can also exercise the actual jar. Add
`-Dviddyoop.jar=/absolute/path/to/java/modules/ViddyoopCore/target/viddyoop-core-1.0.0-SNAPSHOT.jar`
to a separate `mvn test` invocation with the media and Temporal properties above.
It verifies discovery, an identical-byte replacement at the same path, explicit
re-encode/request retry, live status, and worker restart without duplicate jobs.
The replacement's conflicting output fails without altering the first artifact
or deleting the replacement input. Package before this test so the jar matches
the current source.

The project targets Java 17. Full-repository verification passes on Temurin
17.0.21+9 with coverage enabled (210 tests, 15 opt-in skips). The pinned Java 17
ARM64 image passes all 125 core tests without skips using a 512 MiB JVM heap and
PostgreSQL as the external workflow endpoint. The earlier 119-test baseline also
passes with a disposable macOS-shared ENOSPC filesystem.
On JDK 27, add `-Djacoco.skip=true`: the pinned coverage agent cannot instrument
that JDK.

The iTunes preprocessor plugin relies on the following binaries
- HandBrakeCLI (for scanning the input file)
- mkvinfo (for scanning the input file)
- aften (for AC3 encoding)
- libdca (for DTS decoding)
- mkvmerge (for repairing MKV files)
- mkvextract (for extracting track information)

The iTunes mapreduce plugin relies on the following binaries:
- HandBrakeCLI (for scanning the input file and encoding it)
- mkvinfo (for scanning the input file)
- mkvextract (for extracting subtitles and other track information)
