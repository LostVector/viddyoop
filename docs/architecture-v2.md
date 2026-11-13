# viddyoop Architecture v2

Status: accepted (2026-10-03), revised 2026-11-13. Supersedes the 2014 Hadoop-based architecture.
Step7 is implemented. Step8 recovery gates pass with synthetic media on Linux
ARM64; step9 host integration and step10 retirement remain in progress.

The runnable pipeline registers durable submission identities and immutable source
snapshots, validates media, and uses separate Temporal activity queues for real
normalization, encoding and publication. MKVs are remuxed; HBXUtils repairs SRT,
and FFmpeg converts ASS/SSA. Missing/damaged intermediates regenerate explicitly;
unsupported subtitles fail for review. Process stdout/stderr stay separate so
native diagnostics cannot corrupt JSON scans.

Publication verifies bytes and commits artifact/stat records idempotently before
guarded cleanup. Cleanup isolates the original generation, preserves replacements,
and retains sources for histories without generation receipts. Archive/name policy
preserves sources longer than 75 minutes in 720p/1080p buckets, normalizes internal
suffixes and the `AAA-` sort prefix, and persists archive policy/root per submission.
Explicit `none` disables archival for new submissions. Durable scratch estimates
gate workflow starts and release atomically with publication; failures keep their
allocation. These are estimates, not quotas. Snapshots/intermediates remain retained.

`pipeline`, `status`, `reencode`, and explicit failed-stage `retry` are implemented.
Status is read-only and exposes the current run ID; retry retains completed
upstream results and rejects stale runs. Optional XML indexing and receipt-guarded
iTunes import use the same catalog, without editing the host export or removing
authoritative library files. Ambiguous handoffs are observed without resending.

The digest-pinned Debian image and Compose smoke pass on rootless Docker 29.9.3 /
Compose 5.7.1 in a disposable Linux ARM64 VM. All 126 core tests pass there on
Java 17 with coverage, including real media, worker replacement, failed-stage
repair, synthetic import, and cold filesystem restore. Separate crash cases also
pass with production heartbeat/backoff policies. The actual Compose worker
container can be killed, all six data roots backed up/removed/restored, and the
original workflow resumed with one verified artifact/stat and released scratch
allocation. The synthetic optional-profile trial confirms consumed media after
an importer restart and scheduled export refresh, and observes rejection without
redelivery. A controlled macOS VirtioFS mount also passes pipeline smoke,
synthetic import and guest-reboot reconciliation; native macOS reads private
container outputs successfully. The earlier 119-test baseline passes with a
disposable 32 MiB HFS+ volume shared from macOS for ENOSPC checks. See the recovery table below for
scope and remaining proof. A separate abrupt VM-loss trial during active encoding
also passes with PostgreSQL automatic WAL recovery and the same workflow run,
one verified artifact/stat, released scratch and preservation of the uncertain
shared original. The macOS host remained running during this trial.

The container trials exposed and fixed SQLite JNI extraction onto a noexec tmpfs
and missing UID lookup for a numeric-only account. The image now has a named
UID/GID 1000 account and bounded executable temporary mounts. Actual host iTunes
behavior remains unverified and is deferred while automatic delivery is disabled.
Other host sharing/ACL configurations, representative media and operational
retention policy still need validation. Remote validation is tracked
in [draft PR #8](https://github.com/LostVector/viddyoop/pull/8/checks); publication
requires the default-branch gates. Legacy code
remains until those deployment gates pass. A persistent dev-server volume and
successful tests do not establish production suitability of that server.

## Decisions

| Area | Decision |
|---|---|
| Deployment | Single machine, Docker Compose; image published to GHCR via GitHub Actions |
| Scheduler | **Temporal** durable workflows. Pinned dev server/SQLite for local trials; operational deployment requires Temporal Server with supported database persistence (planned PostgreSQL fallback). Replaces Hadoop/YARN and a hand-rolled queue/state machine |
| Catalog store | SQLite for domain data only: ingest identities, published artifacts, import receipts, `itunes_items`, and encode stats history (Temporal holds workflow state, not the catalog) |
| Stack | Java 17, Maven multi-module, `io.temporal:temporal-sdk` |
| Host delivery | Intended recipient is the TV app on the same machine. Initial pilot writes only to a scratch library; Automatically Add to TV integration is deferred. Never edit the host library/export |
| iTunes visibility | A read-only indexer parses iTunes Library.xml into SQLite (`itunes_items`), enabling dedupe and status views |
| HandBrake | Prepared image currently pins Debian `handbrake-cli` 1.6.x. Revisit the toolchain pin against the observed HandBrake 1.11.2 HEVC/HDR/Dolby Vision outputs before claiming media compatibility |

### Observed output compatibility target (2026-11-13)

The operator now uses H.265 output. A read-only scan selected 59 library files
using recent filesystem modification/creation times, recent TV episodes and
older files from the other library volumes. The current-format cohort contains
43 HEVC files (31 movies and 12 episodes); the controls contain 14 older AVC
files and two protected entries. Filesystem and embedded creation dates can
reflect moves or inherited metadata, so neither alone establishes encode order.

All 43 HEVC files have an MP4 container with an `.m4v` extension, `hvc1` Main 10
video, 10-bit 4:2:0 pixels, x265 CRF 22 and VBV max-rate/buffer values of 60000
kbps/kbits. The x265 values come from embedded encoder SEI, rather than filename
or bitrate guesses. Forty-one identify HandBrake 1.11.2 and two identify 1.10.2.
Their resolution retains the source's 4K detail with cropping in some cases;
nominal rates are 23.976 or 24 fps. A filename containing "1080p" does not reliably
identify the actual output resolution.

- Preserve HDR/SDR characteristics: 36 carry BT.2020/PQ HDR10 mastering metadata,
  including 28 with Dolby Vision Profile 8 metadata; seven are BT.709 SDR.
- Every file has default AAC-LC stereo at 48 kHz plus a non-default 5.1 track:
  E-AC-3 in 42, AC-3 in one. Twenty-five E-AC-3 tracks report Dolby Atmos.
  The first 64 surround packet hashes match the NAS source in eight matched
  source/output pairs, supporting passthrough for those samples; this is not a
  whole-file identity check. Do not unconditionally convert E-AC-3/Atmos to AC-3.
- All 143 subtitle tracks are selectable `mov_text`, in English, Spanish or
  French. None is marked forced. This does not establish the selection or
  foreign-dialogue burn-in policy for every source.
- Nineteen files have chapter entries. All 43 place `moov` after `mdat` (no
  fast-start layout in this sample).

These are compatibility requirements for the pilot, not proof that the current
adapter implements them. It still emits the previous 1080p AVC/AAC+AC-3 profile;
its passing recovery suite does not validate this new media target. Implement a
versioned profile and validate dimensions, color/HDR/Dolby Vision metadata,
surround preservation, subtitles and representative playback before accepting
it. HEVC compatibility is part of migration, rather than a deferred new-codec
extension. Named preset, exact filter/crop rules, audio fallback rules and
handling of unsupported HDR variants still need to be made explicit; the output
files do not encode a complete HandBrake preset.

## Why move off Hadoop

Hadoop was used only as a single-reducer job launcher over a dummy input file;
the real data plane was SSH/SCP against a gateway host. Costs paid for that:
plaintext SSH credentials in configs and the YARN job UI, fire-and-forget job
submission that deletes the original input before completion, no retries or
dead-letter path, cluster state only in memory, and five daemons coordinating
through watched directories and filename conventions (`-p1`, `AAA-`, `.r.mkv`).
On a single machine all of this collapses.

## Target architecture

One image (`ghcr.io/lostvector/viddyoop`), one jar, picocli subcommands,
deployed as compose services sharing volumes, plus a Temporal server:

```
inbox ──▶ pipeline worker ──▶ library/ ──▶ itunes-import ──▶ "Automatically Add"
              │  (Temporal workflows/activities)                     │
              ▼                                                      ▼
        Temporal server  ──────── itunes-index ────────▶ SQLite catalog
        (workflow state, retries,  (reads iTunes Library.xml,  itunes_items +
         task queues, Web UI :8233) read-only)                     stats history
```

### Services

- **`temporal`** — Temporal dev server (`temporal server start-dev`) with
  `--db-filename` on a volume: single container, embedded SQLite persistence,
  built-in Web UI. Initial single-node deployment; production suitability is
  an explicit release gate (see Operations below).
- **`pipeline`** — Temporal worker + ingest watcher:
  1. *Ingest watcher*: prefers files delivered by temporary-name + atomic
     rename; size stability is a fallback for unmanaged drops. Validates
     candidates, registers a durable submission identity, and starts one
     `VideoPipelineWorkflow` per submission (see File and retry contracts).
  2. *Preprocess activity*: mkvmerge remux, HandBrake scan, text subtitle
     extraction/repair, archive policy (reuses `HBXUtils` SRT repair; the old
     `HBXJobPreprocessor.Execute` logic minus its directory-queue `Select()`).
  3. *Encode activity*: long-running; heartbeats from the HandBrake progress
     parser, with periodic liveness heartbeats even during quiet tool output.
     HandBrake directly produces AC3 surround tracks from DTS/other codecs,
     avoiding the legacy dcadec/aften intermediate conversion.
     Encode activities use a dedicated task queue and worker limit
     (`VIDYOOP_ENCODE_WORKERS`); preprocessing has a separate limit.
     Admission also checks scratch-space capacity; CPU limits alone are
     insufficient when remuxes and encodes overlap.
     Before staging, include the snapshot's bytes as well as the workflow
     reservation estimate. Attempt reservation/dispatch after each accepted
     input so later batch checks account for those allocations.
  4. *Publish activity*: validate and commit output into `library/`, record
     the artifact and stats idempotently, then perform retry-safe source
     cleanup. The library is authoritative; iTunes delivery is optional.
- **`itunes-import`** — finds committed library artifacts awaiting delivery,
  copies each to a temporary staging location on the iTunes-add filesystem,
  then exposes the completed copy atomically with a create-if-absent hard link
  into the watched folder. Never removes the
  authoritative library file. Records durable delivery attempts and receipts;
  on restart reconciles ambiguous outcomes against the index and observed
  import results without automatically resending. Folder disappearance alone is not proof of
  successful import. Validate actual host iTunes success/rejection behavior
  during integration; do not assume an `Imported/` directory exists.
- **`itunes-index`** — Temporal Schedule (implemented): parses `iTunes Library.xml`
  (read-only) and upserts into `itunes_items`. Name + duration is a candidate
  match, not sufficient evidence for automatic destructive dedupe. Ambiguous
  matches are surfaced for review; skipping never deletes the source. The
  streaming parser disables DTD/entity resolution and never writes the export.
  Refresh atomically replaces the current library snapshot, removing stale tracks;
  malformed, duplicate-ID, or changing exports preserve the previous snapshot.
  `itunes_index_meta` records library identity, source path, index read time, and
  export modification time; a recent read does not prove a recently refreshed
  host export. Schedule restarts
  preserve operator pause state; overlap is skipped. Automatic ingest skipping
  remains deferred; the import service consults this index for exact-content evidence.

### File and retry contracts

- **Submission identity:** register each accepted ingest durably in SQLite,
  then start a workflow using that submission ID. Repeated scans reuse the
  registration; a new file at an old pathname and an intentional re-encode
  receive new identities. Track a source fingerprint and encode profile
  separately from the path. Reconcile registrations without workflows after
  a crash; registration and Temporal start are not one transaction.
  Shared Linux guest filesystems scope synthetic file keys to the guest boot ID.
  After a boot change, known originals still in the inbox pause new discovery
  until reviewed/quarantined; already registered workflows continue from saved
  snapshots. `/work/.ingest-epoch.json` and `.ingest-reconcile.json` record this
  file-boundary evidence, rather than workflow execution state. Cleanup preserves
  originals whose generation belongs to a previous boot. Content and timestamps
  alone do not establish that a path still names the ingested generation.
- **Input readiness:** managed producers write a temporary name and rename
  only when complete. For unmanaged files, use the stability window plus
  media validation, and recheck source identity before processing and cleanup.
  Claim or stage an immutable input before destructive preprocessing; never
  modify an unclaimed inbox file in place.
- **Work ownership:** use `/work/<submission-id>/` with separate outputs for
  each activity attempt. Preserve completed intermediates until dependent
  stages finish. If an intermediate is missing or invalid after recovery,
  explicitly regenerate it; replaying a completed activity does not restore
  its files. Garbage collection must exclude active jobs and attempts.
- **Retry safety:** all activities tolerate repeated execution, including a
  crash after a side effect but before completion is acknowledged. Terminate
  subprocess trees on cancellation/shutdown; isolate attempts so an old
  process cannot overwrite a retry's output. Do not assume a heartbeat
  timeout has killed the original process.
- **Publication:** copy to a temporary file on the library filesystem,
  validate media properties and an artifact checksum, then atomically create
  the final name without replacing an existing file. The implementation uses
  a hard link from the temporary file on the same filesystem, then removes the
  temporary name; the library filesystem must support hard links and directory
  flushing. SQLite reserves each destination for one submission. An existing
  matching artifact completes the retry; a conflicting artifact fails for
  review and is never silently overwritten. Cross-volume moves are copies,
  not the commit boundary. Flush the file before publication and the library
  directory before acknowledging catalog completion.
- **Commit reconciliation:** filesystem publication and SQLite writes are
  separate commits. Persist the expected artifact identity before publication
  and reconcile the final file on retry. Artifact registration and encode
  stats use unique submission keys so retries cannot duplicate records.
- **Cleanup:** delete the claimed original only after verified publication
  and durable catalog registration. Move the source into a per-submission
  `.viddyoop-cleanup` holding directory on the inbox filesystem, then recheck
  inode/generation and content before deletion. Flush claim directories and
  cleanup receipts so interrupted attempts can reconcile. If a replacement
  was claimed in a race, restore it with create-if-absent; if a newer arrival
  blocks restoration, retain both files and fail for review. Preserve originals
  when filesystem identity or a generation receipt is unavailable. Cleanup
  retries must never delete a replacement at the same pathname.
  iTunes availability does not gate library completion. Retain failed inputs
  and diagnostics for explicit retry or operator cleanup.

### Delivery receipt contract

`itunes_deliveries` records the artifact/checksum, canonical watched/staging paths,
preparation time, publish-intent time, acknowledged delivery time, and observed
outcome/note. These are external delivery facts, not a replacement workflow queue.
Only catalog-committed artifacts may be copied. The library file is never moved
or hard-linked into iTunes; the staging copy has an independent inode.

The watched folder must already exist and be writable. Staging must be outside
both watched/library trees and share the watched folder's filesystem/mount. The
backend flushes a complete verified staging copy, records publish intent, then
atomically creates a unique final name (`viddyoop-<submission-id>.mp4`) with a hard
link from staging. Temporary data is never exposed under the watched tree. Use
one parent bind mount containing both watched and private staging directories;
separate bind mounts may prevent hard-link publication even on the same host disk.

A crash before publish intent can retry copying. A matching final name after
intent can be acknowledged without another copy. If that name is absent, the
outcome is ambiguous: iTunes may have consumed it, so the backend does not send
another copy. A terminal receipt also prevents redelivery. Staging names owned by
the importer can be removed after acknowledged delivery without touching the
application's moved/modified copy. `--reconcile-only` suppresses new copies while
allowing receipt reconciliation, including when a restored backup has no receipt
for a possible later transfer. Explicit additional delivery attempts remain
unimplemented; a consumed or rejected copy is never automatically resent.

`itunes-import --submission ID` performs one delivery/reconciliation action.
`--serve` discovers committed artifacts and starts a unique Temporal workflow per
artifact. Automatic operation requires an existing pipeline catalog, explicit
host/mounted media roots, and a completed index. A process lock limits ownership
to one import worker per catalog; separate catalogs need distinct task queues.
Pending/ambiguous outcomes use durable timers (60 seconds by default), continuing
as new every 100 observations to bound history. Confirmation or rejection ends
the workflow. Activity failures retry three times; exhausted workflows require
operator repair/retry and discovery does not start another execution. `status`
reads receipt outcomes/notes without invoking delivery; it does not query live
import workflow state or the host application.

Compose's `itunes` profile enables only indexing. The separate `itunes-import`
profile enables indexing plus automatic delivery, mounts the authoritative
library read-only, shares one writable delivery parent containing watched and
staging directories, and mounts app-managed media read-only for confirmation.
The host must refresh its XML export; indexing cannot force an export refresh.

The reconciliation backend now distinguishes these observations:

- A matching complete watched file remains delivered/pending, not imported.
- A copy with the unique delivery filename and expected checksum in the configured
  rejection tree (up to three levels deep) records rejection without deleting it.
- Confirmation requires an indexed file URI under an explicit host media root,
  mapped into a read-only mounted media root, with matching duration and exact
  stable file bytes. Remote URI authorities, traversal, symlink escapes, watched
  files, and private staging files cannot establish confirmation.
- After publish intent, both index read time and export modification time must
  be at least as recent as the handoff. Re-reading an older export is insufficient.
- Before handoff, exact content already present in the indexed media can satisfy
  the delivery without creating a watched copy. Confirmation describes verified
  indexed presence, not causal proof that this particular transfer created a track.

Confirmed receipts retain the library ID, track ID, persistent track ID, and
observation time. Missing/changed media or insufficient evidence leaves an
ambiguous receipt and never initiates a second transfer. These rules are covered
by synthetic export/media/rejection fixtures; the host's actual export/import
behavior still requires validation before service rollout.

### Orchestration (Temporal)

| Concern | Mechanism |
|---|---|
| Job state machine | `VideoPipelineWorkflow`: preprocess → encode → publish, durable and replayable |
| Queue / leases | Activity task queue + worker polling; dead workers detected via heartbeat timeout |
| Retry / backoff / dead-letter | `RetryOptions` (maxAttempts, backoffCoefficient); failed workflows visible and triageable in the UI |
| Priority (old `-p1`) | Deferred; verify SDK/server semantics before enabling |
| Ingest identity | Durable submission ID; repeated scans reuse it; content/profile dedupe is a separate catalog decision |
| Status view | `viddyoop status` combines Temporal stage/failure with catalog source/artifact identity |
| Chunked encoding (mrx264 idea) | child workflows: split → parallel chunk encodes → join |

### Catalog store (SQLite — domain data only)

Temporal holds workflow state, not the domain catalog. A small SQLite
database keeps durable domain identities and receipts as well as query data.
It does not mirror the workflow state machine. The following schema is a
starting point; step8 adds submission registrations, artifact manifests, and
unique submission keys, and step9 adds import delivery attempts/receipts:

```sql
CREATE TABLE itunes_items(        -- populated by itunes-index
  id INTEGER PRIMARY KEY,         -- iTunes track id
  location TEXT, name TEXT, kind TEXT, duration_ms INTEGER, date_added TEXT
);
CREATE TABLE encode_stats(        -- written by the publish activity
  submission_id TEXT PRIMARY KEY,
  source_path TEXT, final_path TEXT, duration_ms INTEGER,
  width INTEGER, height INTEGER, encode_duration_ms INTEGER,
  machine TEXT, finished_at INTEGER
);
```

### Volumes & config

```
/inbox     host dir users drop files into
/work      job/attempt intermediates; retained while dependent jobs are active
/library   finished, organized output (also what itunes-import watches)
/archive   source movie archives, under 720p/ and 1080p/
/db        SQLite volume
/itunes-delivery   one host-mounted parent containing watched and private staging folders
/itunes-lib   iTunes Library.xml export directory (read-only, indexer)
/itunes-media   app-managed media (read-only, import reconciliation)
```

All tool binaries (handbrake-cli, mkvtoolnix, ffmpeg) are installed in
the image at fixed paths — path configuration disappears from config files.
Remaining knobs are env vars (`VIDYOOP_ENCODE_WORKERS`, archive policy, etc.).

### Operations

Temporal avoids implementing a durable scheduler but adds a service to operate.
Pin server, SDK, image, and media-tool versions; do not deploy `latest`.
The upstream [embedding guide](https://docs.temporal.io/self-hosted-guide/embedded-server)
restricts SQLite embedding to development/testing, and the
[deployment guide](https://docs.temporal.io/self-hosted-guide/deployment) uses
Temporal Server with an external persistence database for sustained workloads.
The support gate therefore fails for the current dev-server Compose service;
successful recovery drills do not change that scope.

The operational fallback candidate is `docker-compose.server.yml`: digest-pinned
Temporal Server 1.32.2 and PostgreSQL 16 for history and visibility, explicit schema
setup and namespace creation. Keep the existing dev Compose stack for isolated
development trials. Workload sizing and upgrade validation remain open.
Its ARM64 smoke passes a real encode, restarts PostgreSQL/Temporal/the worker,
reruns schema and namespace setup against populated persistence and verifies
unchanged submission, workflow-run and artifact identities. CI includes this
candidate smoke alongside the development stack checks.
All 126 core tests pass without skips with PostgreSQL as the external
workflow endpoint, including worker interruption, publication reconciliation,
failed-stage repair and packaged CLI checks. The self-contained SQLite restore
test is not used as PostgreSQL backup evidence. The matching cluster-restore
runner has a separate explicit `server` mode and checks the archived cluster's
clean shutdown state before replaying the original workflow.
That mode passes on ARM64 with the guarded runtime: the matching cluster/catalog
restore retains the namespace UUID, resumes the same submission/run, verifies
one artifact/stat and retained snapshot, and releases scratch.
Its backup boundary must include the database and matching schemas/configuration,
alongside the existing catalog and media roots. Do not point a new backend at the
old SQLite file or silently start an empty namespace for existing submissions.
The catalog now pins the namespace UUID in `temporal_binding`. Pipeline and both
optional scheduled workers verify it before starting. Namespace name reuse is
insufficient. Concurrent initial binding is serialized; existing unbound
registrations require reconciliation rather than automatic adoption. This is
ownership metadata, not a workflow-state mirror. The startup guard complements
the requirement to stop workers before replacing persistence.
Migration must drain the old workflows or use a separately verified transfer
procedure. Single-host deployment also retains a host failure outage; database
support alone does not provide high availability.

The [cold recovery runbook](recovery.md) backs up stopped Temporal persistence,
catalog, work, inbox, library and archive together. `ColdRestoreTest` hashes,
copies, removes and restores its disposable state, then resumes the existing
workflow and reconciles one artifact/stat row. Restored originals have new
filesystem identities and are preserved for review. Import remains in
observation-only mode after restore until possible post-backup transfers are
reconciled; external app effects cannot be rolled back with the catalog.
The six-root Compose volume restore drill passes on Linux ARM64. A separate macOS
VirtioFS reboot trial recovers one pending registration, preserves the uncertain
original, pauses new intake and resumes after quarantine. Native macOS can read
private container outputs on that mount. The six-root drill also passes with
media on the macOS share. PostgreSQL full-cluster restore also passes with the
namespace-guarded runtime. Native app recovery remains open.
Keep Temporal API/UI access restricted to the host or trusted network.

Separate service health from job status: health checks verify service readiness;
`viddyoop status` reports submission, source, current stage, failure, and final
artifact, plus the current Temporal run ID. After repair, `retry --submission ID
--run-id FAILED_RUN_ID --reason "repair details" --dry-run` previews the task
which scheduled the failed activity. Applying resets only that task, preserving
completed upstream activity results. A committed artifact is rehashed before a
publish/cleanup retry; resetting it to an encode stage is refused. Only failed
video pipeline histories with the expected linear task boundary are accepted.
The command preserves domain identity and allocation, uses a stable reset request
ID, and serializes its own resets per canonical catalog path. External Temporal
resets must be serialized by the operator: the API has no current-run compare-and-set
guard and can terminate a newer run. A transport timeout needs status inspection.
This is distinct from intentional `reencode`, which registers a new submission.
See Temporal's [reset API contract](https://github.com/temporalio/api/blob/master/temporal/api/workflowservice/v1/request_response.proto)
for reset-point and current-run semantics.

## What is kept vs deleted

**Kept (the durable core):** `HBXUtils` tool wrappers and parsers (now
unit-tested), preprocessing normalization logic, HandBrake encode presets,
the size-stability ingest heuristic, subtitle SRT repair.

**Deleted:** `hbx-mapreduce`, `rk-hadoop-utils`, `hbx-jobsubmitter-exe`,
`hbx-filebroker-exe`, `hbx-filecollector-exe`, `rk-ssh-utils` (no SSH on a
single machine), `hadoop-client` dependency, plugin-jar `URLClassLoader`
indirection, `ClusterManager`/`RkTracker`/WOL, `com.rkuo.mysql.*`, RMI
leftovers (`IHBXWrapperService`, `HBXWrapperConfig`), empty stubs
(`HBXJobPostprocessor`, `X264ExeHelper`, `HBXJobProcessor`).

## New capabilities unlocked

- Concurrency control: N parallel encodes sized to the machine
- Chunked parallel encoding of one file (the unfinished mrx264 idea) as
  parent/child jobs: `MKVExeHelper.Split` exists; the join becomes a state
- `viddyoop status` CLI combining workflow execution with catalog records;
  a later web view can use the same sources
- Dedupe against the iTunes index
- Modern encoders (SVT-AV1/VP9) available via HandBrake 1.6 in the image

## Migration plan

1. **step7 — `viddyoop-core` (Temporal skeleton)**: `VideoPipelineWorkflow`
   + activity interfaces with retry/heartbeat policies, ingest watcher
   (size-stability heuristic), idempotent workflow starter, workflow tests
   on Temporal's test environment, dev-server docker-compose. No behavior
   change to the existing system.
2. **step8 — contracts, real activities, and recovery proof**: implement the
   contracts above, submission/artifact catalog records, HandBrake 1.x adapter,
   worker bootstrap, separate stage limits, subprocess lifecycle management,
   and basic `status`. Package a minimal local pipeline for end-to-end testing.
   Before proceeding, demonstrate:
   - worker termination during encoding, followed by safe retry;
   - interruption after final-file publication but before catalog/Temporal
     acknowledgement, followed by reconciliation without duplicate output/stats;
   - disk exhaustion during preprocessing and publication without input loss;
   - repeated scans, a different file at the same path, and explicit re-encode;
   - missing intermediates, destination conflicts, and source replacement
     before cleanup handled without deleting unrelated data.
   Acceptance: one submission survives interruption and produces exactly one
   verified library artifact; failed submissions retain recoverable input.
3. **step9 — catalog integration and optional iTunes delivery**: add
   `itunes-import`, `itunes-index`, and delivery reconciliation. Finish the
   Dockerfile, full Compose stack, and GHCR build. Verify pinned versions,
   resource limits, health checks, and backup/restore before operational deployment.
   The initial pilot writes to a scratch library; TV watched-folder delivery is
   deferred at the operator's request. Verify actual TV import behavior before
   enabling that optional integration.
4. **step10 — retire the old system**: after representative files complete and
   recovery checks pass on the replacement, remove Hadoop/SSH/broker/collector
   modules and vestigial code; README quickstart becomes `docker compose up`.
5. **step11+ — extensions**: chunked encoding via child workflows, priority,
   and new codecs. These do not gate a reliable single-file pipeline.

### Prepared deployment pins

The Dockerfile pins Maven 3.9.9/Java 17 and Debian Bookworm base manifests by
registry digest. Media package versions are HandBrake `1.6.1+ds1-1`, MKVToolNix
`74.0.0-1+deb12u1`, and FFmpeg `7:5.1.9-0+deb12u1`, checked against the official
[HandBrake](https://packages.debian.org/bookworm/handbrake-cli),
[MKVToolNix](https://packages.debian.org/bookworm/mkvtoolnix), and
[FFmpeg](https://packages.debian.org/bookworm/ffmpeg) package pages. Java 17 and
transitive libraries follow maintained Bookworm repositories; this is not a full
APT snapshot lock. Pin refreshes must pass the container smoke test.

Temporal uses CLI image `1.10.0` by digest and mounts its existing user-owned
`/home/temporal` directory for SQLite persistence. API/UI host ports bind only to
loopback. The pipeline image runs as UID/GID 1000 with persistent work/catalog
volumes, host-mounted media, and a writable temporary filesystem. `health` checks
a 30-second liveness signal refreshed independently of long ingest scans, plus
Temporal namespace readiness; it does not derive readiness from job outcomes.
Configurable service limits default to 2 CPUs/4 GiB for the pipeline, 1 CPU/1 GiB
for Temporal, 0.5 CPU/512 MiB for indexing and 0.5 CPU/1 GiB for import. Java heap
caps are 512/128/256 MiB respectively, leaving room for native tools/allocations.
Each Java service has a 64 MiB executable tmpfs for SQLite JNI extraction. These
are initial ceilings, not reservations or representative workload measurements;
size concurrency, limits, Docker VM memory and host headroom together. The
[Compose resource settings](https://docs.docker.com/reference/compose-file/services/)
are checked by the smoke script against Docker inspection and pipeline cgroups.
Rootless cleanup uses the engine's namespace to remove test-owned subordinate-UID
files. The verification build target adds test tools to the same runtime while
the default release target excludes them. `container-recovery.sh` verifies matching
application jars and runs all core tests without skips; an opt-in mode checks
worker crashes with unchanged production timeout policies. `container-restore.sh`
exercises an actual worker-container kill and six-root volume backup/restore.
`container-itunes.sh` checks the optional profile against synthetic media/exports,
including importer restart and observed rejection. CI runs the full suite and
profile on pull requests, adding production-timeout and volume-restore checks on
default-branch pushes before GHCR publication. These checks pass locally on ARM64;
remote CI is tracked in [PR #8 checks](https://github.com/LostVector/viddyoop/pull/8/checks).
The 125-test baseline passes native and container AMD64 CI, including both
backends and generated media/import cases. The subsequent intake correction
passes 126 tests on ARM64 with either backend; its remote checks are tracked on
the draft. Default-branch production-timeout/restore and publication gates remain
separate from these PR checks.

## Recovery verification status

| Gate | Current evidence | Remaining proof |
|---|---|---|
| Intake headroom | Packaged regression on a 32 MiB filesystem reproduces the old copy-before-reservation failure. Corrected intake defers before copying when only the workflow estimate fits; all 126 tests pass with either backend on ARM64 | Representative workload/storage sizing |
| Disk exhaustion in preprocessing/publication | `DiskFullRecoveryTest`: real ENOSPC on native HFS+, pinned-container tmpfs/ext4 and macOS-shared HFS+ via VirtioFS; inputs survive and retry succeeds. The fixture consumes residual blocks and truncates its filler before unlinking because sharing can retain open host handles | Representative workload/storage sizing |
| Publication/catalog commit gap | `WorkerCrashRecoveryTest`: worker JVM dies after publication, before catalog commit; replacement reconciles one artifact/stat and cleanup. Passes in pinned ARM64 image with production heartbeat/backoff | Representative inputs and actual host mounts |
| Worker termination during encoding | Pinned ARM64 image: real HandBrake and worker JVM killed after progress; same workflow completes under production policies. Compose restore kills the entire worker container and also passes with media on a macOS VirtioFS share | Representative inputs |
| Missing intermediates | Real activity test deletes normalized media, then encoding regenerates it; passes in pinned ARM64 suite | Representative host inputs |
| Source replacements and destination conflicts | Cleanup tests preserve replacements, including a claimed race; publication rejects conflicts | Include representative real inputs in operational trial |
| Shared-source identity across guest reboot | Real VirtioFS boot changes synthetic file keys. Guarded runtime retains namespace UUID, recovers one pending submission without duplicate registration and preserves the original; intake resumes after quarantine | Other sharing backends |
| Repeated ingest and explicit re-encode | `RunnablePipelineTest`: actual jar discovers once, preserves replacement on conflict, re-encodes under a stable request key, reports status and restarts without duplicate registration; passes in pinned ARM64 suite | Actual host mounts and representative inputs |
| Target toolchain | All 126 core tests pass with either backend on Debian ARM64 HandBrake 1.6.1/MKVToolNix 74/FFmpeg 5.1.9/Java 17. All 126 also pass AMD64 container CI against both backends | Representative inputs |
| Broader codec paths | Packaged pipeline on macOS VirtioFS passes generated 1080p DTS→AAC/AC3, interlaced MPEG-2 TS→progressive H.264, 10-bit HEVC/ASS→H.264/mov_text and silent-video cases. Every output decodes; dimensions/channels/subtitle text and language/duration/cleanup are checked | Actual collection, long duration and sustained resource measurements |
| Optional import recovery | Native/container daemon tests pass. Compose synthetic profile also passes on a macOS VirtioFS share: consumed media is confirmed after importer restart and scheduled export refresh, and rejection is observed without redelivery; library unchanged. Native macOS reads private container outputs | Actual recipient app import/rejection/export; other sharing/ACL configurations |
| Abrupt VM loss during encoding | Forced dedicated Lima VZ guest termination while real HandBrake was active, without a post-acceptance sync or clean service stop. PostgreSQL WAL recovery and Temporal retry complete the same namespace/submission/run with one verified artifact/stat and released scratch; uncertain shared original survives and intake pauses | Representative inputs and deployment storage; physical host power loss is outside this trial |
| Cold backup/restore | SQLite and full-cluster PostgreSQL drills pass: worker kill, clean persistence shutdown, six-root copy/erase/restore, same submission/run, verified source/output and one stat, released scratch. Guarded PostgreSQL restore retains namespace UUID. SQLite drill also passes with media on macOS VirtioFS | Host app recovery, operational backup retention and upgrade validation |
| Exhausted-stage repair | Packaged CLI on real server resumes publication without another encode and cleanup without encoded intermediate, retains one stat and rejects stale run IDs; passes in pinned ARM64 suite | Actual host mounts and representative inputs |
| Operational Temporal persistence | PostgreSQL candidate passes encode, restart, repeat setup, all 126 core tests and guarded full-cluster cold restore. Packaged workers reject mismatched namespace UUIDs. The same core suite passes against the development backend | Upgrade lifecycle, workload sizing, representative collection and host-app trial |

The crash tests use a real Temporal dev server. The socket-bound Java test server
retried activities but left a follow-up workflow task undelivered after abrupt
client loss in the initial harness; it is not used as evidence of full worker
restart recovery. The verified CLI archive SHA-256 is
`40a76ef7d525e6195b083e4cd50b184cc3bb242fea7f8dda2b9a8698ecbb7379`
([official CLI v1.10.0 release](https://github.com/temporalio/cli/releases/tag/v1.10.0)).

These are scoped synthetic-media results, not authorization to remove legacy
modules before host integration and representative trials. The ENOSPC suite is
opt-in; see the README for disposable filesystem setup and its capacity guard.

## Open questions

- Archive/name defaults now preserve the legacy duration/resolution thresholds
  and Apple TV sort prefix; revisit these product policies after migration.
- Content-preserving confirmation now uses duration candidates plus exact bytes;
  handling app-modified media needs a separately validated identity policy.
- Source generation, isolated cleanup claims, and collision handling are
  implemented; snapshot/intermediate retention and garbage collection remain
  to be defined before operational rollout.
- The intended recipient is Apple TV. The operator has deferred its Automatically
  Add to TV integration; pilot outputs go to a separate scratch library with
  automatic import disabled. Validate watched-folder behavior and reliable import
  evidence before enabling delivery. Synthetic iTunes-compatible fixtures do not
  establish TV support.
- Private container files are readable by native macOS UID 501 on the tested
  VirtioFS mount. Validate other sharing backends/ACLs and the recipient app's
  actual access before enabling delivery.
- Priority is deferred; verify server/SDK support and ordering semantics
  against pinned versions before choosing native priority or separate queues.
