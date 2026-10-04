# viddyoop Architecture v2

Status: accepted (2026-10-03). Supersedes the 2014 Hadoop-based architecture.

## Decisions

| Area | Decision |
|---|---|
| Deployment | Single machine, Docker Compose; image published to GHCR via GitHub Actions |
| Scheduler | **Temporal** durable workflows (self-hosted dev-server container, embedded SQLite persistence). Replaces Hadoop/YARN and any hand-rolled queue/state machine |
| Catalog store | SQLite for domain data only: `itunes_items` index and encode stats history (Temporal holds workflow state, not the catalog) |
| Stack | Java 17, Maven multi-module, `io.temporal:temporal-sdk` |
| iTunes | iTunes runs on the same machine as Docker; import via the "Automatically Add to iTunes.localized" folder (never by editing iTunes Library.xml) |
| iTunes visibility | A read-only indexer parses iTunes Library.xml into SQLite (`itunes_items`), enabling dedupe and status views |
| HandBrake | Target the Debian `handbrake-cli` 1.6.x shipped in the image (requires an adapter for 1.x scan/CLI surface) |

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
  built-in Web UI. Legitimate for single-node; not an HA setup.
- **`pipeline`** — Temporal worker + ingest watcher:
  1. *Ingest watcher*: scans the inbox volume; for size-stable files (the old
     collector heuristic) starts a `VideoPipelineWorkflow` whose workflow id
     is derived from the source path (idempotent ingest — no rename games).
  2. *Preprocess activity*: mkvmerge remux, HandBrake scan, DTS→AC3
     conversion, archive policy (reuses `HBXUtils` wrappers; the old
     `HBXJobPreprocessor.Execute` logic minus its directory-queue `Select()`).
  3. *Encode activity*: long-running; heartbeats from the HandBrake progress
     parser (replaces the "ping YARN every 10 min" hack). Worker
     `maxConcurrentActivityExecutionSize` = encode pool size
     (`VIDYOOP_ENCODE_WORKERS`; Java 17 is cgroup-aware).
  4. *Publish activity*: verify output, write stats, move into `library/`,
     and only then delete the original input.
- **`itunes-import`** — watches `library/`, drops finished files into the
  mounted "Automatically Add to iTunes.localized" folder, and observes the
  outcome (iTunes moves files to `Imported/` or `Not Added/`), recording the
  result into the catalog.
- **`itunes-index`** — Temporal Schedule: parses `iTunes Library.xml`
  (read-only) and upserts into `itunes_items`. The pipeline consults the
  index at ingest to skip files already present in the library (name +
  duration match).

### Orchestration (Temporal)

| Concern | Mechanism |
|---|---|
| Job state machine | `VideoPipelineWorkflow`: preprocess → encode → publish, durable and replayable |
| Queue / leases | Activity task queue + worker polling; dead workers detected via heartbeat timeout |
| Retry / backoff / dead-letter | `RetryOptions` (maxAttempts, backoffCoefficient); failed workflows visible and triageable in the UI |
| Priority (old `-p1`) | Task Queue Priority & Fairness (GA server-side 2026; `Priority.setPriorityKey` in SDK) |
| Dedupe | workflow id = source path; `ALLOW_DUPLICATE_FAILED_ONLY` reuse policy |
| Status view | Temporal Web UI + CLI for free; `viddyoop status` layers on top |
| Chunked encoding (mrx264 idea) | child workflows: split → parallel chunk encodes → join |

### Catalog store (SQLite — domain data only)

Temporal holds workflow state, not the domain catalog. A small SQLite
database keeps what the pipeline needs to query directly:

```sql
CREATE TABLE itunes_items(        -- populated by itunes-index
  id INTEGER PRIMARY KEY,         -- iTunes track id
  location TEXT, name TEXT, kind TEXT, duration_ms INTEGER, date_added TEXT
);
CREATE TABLE encode_stats(        -- written by the publish activity
  source_path TEXT, final_path TEXT, duration_ms INTEGER,
  width INTEGER, height INTEGER, encode_duration_ms INTEGER,
  machine TEXT, finished_at INTEGER
);
```

### Volumes & config

```
/inbox     host dir users drop files into
/work      scratch (remux, DTS→AC3) — throwaway
/library   finished, organized output (also what itunes-import watches)
/db        SQLite volume
/itunes-add   "Automatically Add to iTunes.localized" (host-mounted)
/itunes-lib   iTunes Library.xml + Media (read-only mount, for indexer)
```

All tool binaries (handbrake-cli, mkvtoolnix, aften, dcadec) are installed in
the image at fixed paths — path configuration disappears from config files.
Remaining knobs are env vars (`VIDYOOP_ENCODE_WORKERS`, archive policy, etc.).

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
- `viddyoop status` CLI (also the container HEALTHCHECK) and later a web view
  straight off the jobs table
- Dedupe against the iTunes index
- Modern encoders (SVT-AV1/VP9) available via HandBrake 1.6 in the image

## Migration plan

1. **step7 — `viddyoop-core` (Temporal skeleton)**: `VideoPipelineWorkflow`
   + activity interfaces with retry/heartbeat policies, ingest watcher
   (size-stability heuristic), idempotent workflow starter, workflow tests
   on Temporal's test environment, dev-server docker-compose. No behavior
   change to the existing system.
2. **step8 — real activities + worker main**: preprocess/encode/publish
   activity implementations reusing `HBXUtils` (including the HandBrake 1.x
   adapter: scan-output parsing + arg building), worker bootstrap, encode
   heartbeats from the progress parser. End-to-end on one machine.
3. **step9 — `itunes-import` + `itunes-index`** + SQLite catalog, Dockerfile,
   full compose stack, GHCR image build in CI.
4. **step10 — delete the old world**: remove Hadoop/SSH/broker/collector
   modules and vestigial code; README quickstart becomes `docker compose up`.
5. **step11+ — polish**: `status`, chunked encoding via child workflows, AV1.

## Open questions

- Archive policy (720p vs 1080p folders, `AAA-` Apple TV sort prefix) — keep
  as-is initially, revisit once the status view exists.
- Exact dedupe key for the iTunes index (name+duration vs filename tokens).
- Task Queue Priority requires server support (GA May 2026); verify the dev
  image version honors `precedence`, else fall back to a separate
  high-priority task queue consumed first.
