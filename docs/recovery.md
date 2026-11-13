# Cold backup and restore

This is the initial single-node recovery procedure. `ColdRestoreTest` exercises
the native version against Temporal CLI 1.10.0 / Server 1.32.2 with real media
tools. `scripts/container-restore.sh` also passes on Linux ARM64: it kills the
actual Compose worker during encoding, stops Temporal cleanly, archives all six
roots, removes/restores their contents, quarantines the restored original, and
resumes the same workflow/run under production retry policies with one verified
artifact/stat row. A separate macOS VirtioFS reboot trial verifies pending
registration recovery, guarded source preservation and intake reconciliation;
native macOS reads of private container outputs also pass. The six-root Compose
drill additionally passes with media on an actual macOS VirtioFS share. Real app
recovery still needs validation. This does not establish production suitability of Temporal's
development server.

The commands below default to `docker-compose.yml`'s SQLite development server.
The separate `docker-compose.server.yml` candidate stores history and visibility
in PostgreSQL. Its backup boundary and runner mode are described under
“PostgreSQL backend” below; copying the dev-server SQLite root does not back up
that backend.

## Backup boundary

Shared inboxes have an additional restart boundary. VirtioFS file keys changed
across an actual Linux guest reboot even though the host source bytes, birth time,
mtime and size were unchanged. The worker includes the guest boot ID in shared
source generations and preserves old originals during cleanup after reboot.
`/work/.ingest-epoch.json` records the last admitted epoch. If known source paths
remain after a changed epoch, new discovery pauses; durable pending registrations
and running workflows continue. Inspect `/work/.ingest-reconcile.json`, review the
listed originals and quarantine them outside `/inbox`. Discovery automatically
resumes once these paths are absent. Keep quarantined bytes until their saved
snapshots and final outputs have been verified. Include both identity metadata
files with the work tree; clearing them is not a reconciliation procedure.

Back up one stopped generation of all of these together:

| Data | Compose location | Why it belongs in the same backup |
|---|---|---|
| Temporal persistence | `temporal-data`, mounted at `/home/temporal` | Histories, activity attempts, timers, schedules |
| Catalog | `catalog-data`, mounted at `/db` | Submission IDs, manifests, stats, reservations, index, delivery receipts |
| Work | `work-data`, mounted at `/work` | Immutable snapshots and intermediates referenced by histories |
| Inbox | Host bind at `/inbox` | Arrivals, original sources, cleanup claims and receipts |
| Library | Host bind at `/library` | Authoritative media, including file/catalog commit gaps |
| Archive | Host bind at `/archive` | Source archives required by registered policies |
| Configuration | Compose file, local configuration, image digest | Paths, namespace, queues, toolchain and policies |

Never copy live SQLite database files in isolation. Stop writers first and copy
the complete directories, including any remaining WAL/journal sidecars. A copy
of the catalog at one time and Temporal persistence at another is not this
backup protocol. Do not remove retained snapshots/intermediates to save backup
space: histories may still depend on them.

The host app's library/export/media and delivery directories have a separate
lifecycle. Back up that library using its supported procedure. Do not restore
old watched-folder copies into a running app. A transfer can be consumed after
the Viddyoop backup; rolling back its receipt cannot undo that external effect.

## Take a cold backup

1. Stop new arrivals and manual CLI writes (`reencode`, index/import).
   Stop importer, indexer and pipeline; ensure their media subprocesses have
   exited. Then stop Temporal cleanly. Keep services stopped until copying and
   verification finish. In Compose, using the deployment's usual project,
   environment and Compose file options:

   ```sh
   docker compose --profile itunes-import stop itunes-import itunes-index pipeline
   docker compose stop temporal
   docker compose --profile itunes-import ps -a
   ```

   Check that all writers are exited. A timeout or forced server termination
   needs investigation and SQLite integrity verification before accepting a backup.

2. Copy the six data roots and configuration to a new backup outside those roots.
   Keep sources stopped and read-only throughout. On a Docker host, this helper
   uses the stopped pipeline's mounts and exact image. Set `backup_dir` to a new
   absolute directory, then:

   ```sh
   mkdir -m 0700 "$backup_dir"
   pipeline_id="$(docker compose ps -aq pipeline)"
   temporal_id="$(docker compose ps -aq temporal)"
   backup_image="$(docker inspect --format '{{.Image}}' "$pipeline_id")"
   temporal_volume="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/home/temporal"}}{{.Name}}{{end}}{{end}}' "$temporal_id")"
   test -n "$temporal_volume"
   docker run --rm --network none --read-only --user 0 \
     --volumes-from "${pipeline_id}:ro" \
     --mount "type=volume,src=$temporal_volume,dst=/temporal,readonly" \
     --mount "type=bind,src=$backup_dir,dst=/backup" \
     --entrypoint tar "$backup_image" \
     -C / -cpf /backup/state.tar db work inbox library archive temporal
   docker compose --profile itunes-import config > "$backup_dir/compose.resolved.yaml"
   docker inspect "$pipeline_id" "$temporal_id" > "$backup_dir/containers.json"
   shasum -a 256 "$backup_dir/state.tar" > "$backup_dir/state.tar.sha256"
   ```

   Stopped containers must still exist. Preserve source Compose/configuration
   files as well. Store the image or retain its immutable registry digest.
   A failed copy is not a backup.

3. Check the archive checksum, extract into an isolated staging area, and verify
   both SQLite databases with `PRAGMA integrity_check`. Verify committed artifact
   checksums and retained source snapshot fingerprints against catalog records.
   Record checks, versions, paths and stop time alongside the backup. Resume
   only services that were intentionally enabled. Maintain an independently
   stored copy and periodically perform the restore drill.

## Restore and reconcile

1. Keep all workers, file producers and manual writes stopped. Verify backup
   checksums before extraction. Retain surviving current data separately for
   comparison; never overlay a backup onto a live tree.
2. Restore all six roots into empty destinations with original ownership and
   permissions. Recreate the same absolute container/native paths, namespace,
   task queues and pinned code/tool versions. Histories and catalog rows contain
   paths; changing mounts needs a separately reviewed migration. Restore full
   SQLite directories, not just their main files.
3. Check SQLite integrity and source/artifact bytes before starting workers.
   Compare surviving newer files with restored manifests. Retain conflicts for
   review; never overwrite newer library/archive media to fit an old catalog.
   A missing/corrupt committed artifact needs repair even if `status` says committed.
4. Start only Temporal; inspect offline and live `status`. Keep new inbox ingestion
   paused while reviewing restored files. Copies have new inode/creation identities:
   guarded cleanup preserves restored originals instead of authorizing deletion
   from their checksums alone. Those originals would be new ingest generations if
   scanned again. Quarantine/review them outside the inbox before discovery resumes;
   retain immutable work snapshots at their registered paths. Do not rewrite source
   generation receipts.
5. Resume processing workers after inbox review. Restored Temporal history resumes
   its activity/retry state. A file published before catalog commit is reconciled
   against its checksum; conflicts are never overwritten. Existing executions do
   not become fresh submissions on restart. After fixing an exhausted stage, use
   `retry --submission ID --run-id FAILED_RUN_ID --reason "repair details" --dry-run`
   to inspect its reset point, then apply without `--dry-run`. This retains completed
   upstream results; a failed cleanup can resume without re-encoding the committed
   file. A committed artifact must still match its checksum. Keep external Temporal
   resets serialized with this operation. An intentional re-encode uses a new
   request identity and output name instead.
6. Refresh the host XML export and re-index it. Keep import in observation-only
   mode: native `itunes-import --reconcile-only --submission ID` or `--serve`, or
   Compose `VIDYOOP_ITUNES_RECONCILE_ONLY=true`. This permits receipt preparation
   and positive confirmation/rejection checks but never publishes a new watched
   copy, even when the backup lacks its transfer receipt. This still writes catalog
   observations; `status` remains the read-only inspection command.
7. Reconcile every artifact that might have been delivered since the backup.
   Exact indexed presence can confirm a transfer absent from restored receipts.
   An old confirmed receipt is a historical observation, not proof the current
   host library survived; verify its media too. Missing evidence stays unresolved.
   Keep observation-only mode enabled while any possible post-backup transfer is
   unresolved. Return to delivery mode only after review establishes which artifacts
   can safely receive a first copy. Explicit repeat-delivery tooling is unimplemented.
   Disabling observation-only is a deliberate decision, not an automatic restore step.

## PostgreSQL backend

Use the deployment's `-f docker-compose.server.yml`, project and environment for
every Compose command. Stop import/index/pipeline writers, then Temporal, then
PostgreSQL. Check that PostgreSQL exited cleanly before copying. The persistence
root is the **entire** `postgres-data` volume at `/var/lib/postgresql/data`,
including both Temporal databases, cluster metadata and WAL. The remaining five
media/catalog/work roots must come from the same stopped generation.

The archive helper above can mount that PostgreSQL volume at its `/temporal`
alias instead of the SQLite server volume. Obtain it from the stopped PostgreSQL
container:

```sh
postgres_id="$(docker compose -f docker-compose.server.yml ps -aq postgres)"
temporal_volume="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$postgres_id")"
test -n "$temporal_volume"
```

Keep the exact PostgreSQL/server/admin image pins, schema/configuration files,
namespace retention and protected password file with the backup. Include the
PostgreSQL container in the recorded container metadata. A physical restore
requires compatible binaries and the original ownership; this drill uses the
same immutable images. Do not treat a partial database-directory copy as a
cluster backup. See [PostgreSQL's filesystem backup contract](https://www.postgresql.org/docs/16/backup-file.html).

Restore into empty destinations with every writer stopped. Inspect the archived
cluster with the matching `pg_controldata`; its state must be `shut down`. Restore
all six roots at their original container paths. Start PostgreSQL and Temporal
before processing workers, check database/schema and namespace identity, and
review/quarantine restored originals as above. An empty database or namespace is
not recovery of cataloged workflows. Rerunning the pinned setup jobs must preserve
existing schema/history and namespace retention. Schema upgrades and transfers
from the SQLite backend require separate validation.

Worker startup checks the catalog's `temporal_binding` namespace UUID before
dispatch, schedules or import workers begin. A same-name replacement namespace
is not a match. Keep that table in the restored catalog; never clear it to make
a new backend appear compatible. Older unbound catalogs with registrations fail
startup and need explicit reconciliation. This includes pending registrations,
whose Temporal start may have succeeded before local acknowledgement. The check
does not replace stopping workers before a backend change.

The isolated runner accepts an explicit fourth argument for this backend:

```sh
scripts/container-restore.sh viddyoop:test viddyoop:verification /new/restore-results server
scripts/container-recovery.sh viddyoop:test viddyoop:verification /new/recovery-results server
```

The restore trial kills its worker container mid-encode, stops Temporal and
PostgreSQL, archives the full cluster plus five application roots, verifies the
archive and clean-shutdown control state, erases/restores only that trial's data,
and waits through production timeout policies. It requires the same submission
and run, one verified artifact/stat row and released scratch. The full suite's
external endpoint uses PostgreSQL; its self-contained `ColdRestoreTest` still
launches its own SQLite dev server. That individual test is not PostgreSQL
backup evidence.
The PostgreSQL mode passes on ARM64 with the guarded runtime, including matching
namespace UUID, original submission/run, verified source/output, one stats row
and released scratch reservation.

## Abrupt VM loss

The VM loss trial is distinct from a cold restore. In a dedicated
disposable Docker VM with a controlled writable host share, run
`scripts/container-powerloss.sh prepare /absolute/shared/trial`, force-stop the
VM from its host, restart it, then run the same script with `verify` and finally
`cleanup`. The harness gates HandBrake only on the initial guest boot, so retry
after a new boot uses the real encoder. It requires PostgreSQL's automatic
crash-recovery log message, unchanged namespace/submission/run identity, verified
source/output and one stats row, released allocation and preservation of the
uncertain original. No writer is stopped cleanly and no sync after acceptance is
introduced. This trial passed on the pinned ARM64 runtime with a macOS VirtioFS
share: PostgreSQL replayed WAL and the original workflow run completed after its
production heartbeat timeout and retry backoff. It does not establish durability
through physical host power loss.

## Reproduce the native drill

Use a short disposable fixture and installed tool paths:

```sh
mvn -pl java/modules/ViddyoopCore -am test \
  -Dtest=ColdRestoreTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dviddyoop.temporal.cli=/path/to/temporal \
  -Dviddyoop.handbrake=/path/to/HandBrakeCLI -Dviddyoop.fixture=/path/to/fixture.mkv \
  -Dviddyoop.mkvmerge=/path/to/mkvmerge -Dviddyoop.mkvextract=/path/to/mkvextract \
  -Dviddyoop.ffmpeg=/path/to/ffmpeg
```

The test owns its temporary server/database and media trees. It stops at the
file-publication/catalog-commit gap, stops every writer, hashes and copies the
complete state, removes only its disposable live tree, restores it at the same
paths, and restarts. It checks database integrity, retained workflow identity,
one verified artifact/stat row, retained snapshot, and safe preservation of the
restored original. This covers a cold native restore of one in-flight submission;
it does not cover container volumes, host iTunes recovery, retention duration,
or loss since the most recent backup.
