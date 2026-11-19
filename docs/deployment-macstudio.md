# Mac Studio deployment

The operational stack runs on `rk-macstudio` using Docker Desktop, Temporal Server
with PostgreSQL persistence, and the SQLite application catalog. The operator
waived further manual TV/playback testing in favor of production iteration.
Automatic TV import remains disabled.

## Layout

`deploy/compose.nas.yml` overlays `docker-compose.server.yml`. It replaces the
worker's host-media bind mounts with an external Docker volume, mounts only the
configured subdirectory at `/data`, and keeps databases in local Docker volumes.
The worker runs as UID/GID 1000. A startup job checks the isolation marker,
required directories and a real write/fsync/remove before the worker starts.

| Item | Current deployment |
|---|---|
| Host | M1 Ultra, 20 cores, 64 GiB RAM |
| Docker Desktop VM | 20 CPUs, 16 GiB RAM, shared with other containers |
| Compose project | `viddyoop-macstudio` |
| External volume | `nas-public-temp` |
| Volume subdirectory | `temp/viddyoop-macstudio-20261117` |
| Host-visible media | `/Volumes/Public/temp/viddyoop-macstudio-20261117` |
| Worker limits | One encoder, CPU affinity 0–11, 12 CPU quota, 10 GiB RAM |
| Local state volumes | `viddyoop-macstudio_catalog-data`, `viddyoop-macstudio_postgres-data` |
| Delivery | Scratch `library`; automatic TV import disabled |

The volume's observed root exposes Public despite its `prefixpath=temp` option.
Configure the subdirectory from what a container actually sees. The application
receives `/data/inbox`, `/data/work`, `/data/library` and `/data/archive`; this
mount does not expose the rest of the NAS. Scratch admission retains the 4×
estimate and 10 GiB free-space floor. Snapshots/intermediates remain retained.

Full-length Verity completed and committed one 10.3 GB output. Encoding took
about 12 hours 15 minutes at 3.95 fps. Dolby Vision RPU and HDR10+ metadata were
confirmed by a modern reader, and the checksum matches the catalog. The full
picture/audio decode completed with pinned FFmpeg 5.1, which logged legacy
RPU-parser warnings; FFprobe 9 parsed those RPUs and an FFmpeg 9 five-second decode
was clean. This does not claim a complete FFmpeg 9 decode or manual TV playback.
The NAS original remains unchanged.

## First installation

Requirements: Python 3, Docker Engine/Desktop with Compose supporting `!override`
and volume subpaths, an external NAS volume, and an isolated media directory.
The Studio uses Docker 29.9.3 and Compose 5.7.0.

1. Create `inbox`, `work`, `library` and `archive` inside the isolated NAS folder.
   Add an empty `.viddyoop-media-root` file alongside them. Do not put this marker
   at a collection/share root.
2. Configure the external volume so UID/GID 1000 can write. The Studio uses
   `uid=1000,gid=1000,forceuid,forcegid,file_mode=0660,dir_mode=0770` on CIFS.
   These are client-side settings. Keep NAS credentials in private administrator
   configuration, outside Git and media reports. The deployment helper does not
   create, remove or recreate external NAS volumes.
3. Check out a reviewed release and initialize local configuration:

   ```sh
   python3 scripts/deploy.py init
   ```

   Edit `local/macstudio.env`. Set `VIDYOOP_IMAGE` to an immutable registry digest
   or CI `sha-<commit>` tag, and verify the NAS volume/subpath. For an existing
   deployment, preserve its original PostgreSQL password, namespace and Compose
   project. Do not initialize a replacement password for an existing database.

4. Check and start:

   ```sh
   python3 scripts/deploy.py check
   python3 scripts/deploy.py up
   python3 scripts/deploy.py status
   ```

The helper uses a separate Docker CLI configuration for public image pulls over
SSH without unlocking the login Keychain or changing the user's Docker settings.
On macOS it finds Docker Desktop's CLI/socket; `VIDYOOP_DOCKER_BIN` and
`DOCKER_HOST` can select another engine. For a private registry, set
`VIDYOOP_DOCKER_CONFIG` to a separate configuration already authenticated for
unattended pulls. Secrets are generated inside a private
local directory and never printed. `init` refuses to overwrite configuration or
rotate an existing password.

Native AMD64 and ARM64 CI verify the same runtime. After both platforms' recovery
and PostgreSQL restore gates pass on the default branch, publication creates a
shared `sha-<commit>`/`main` manifest and platform-specific
`sha-<commit>-amd64`/`-arm64` tags. New releases can then be pulled directly on the
Studio. Keep the existing verified local image until a new release has passed
publication and deployment checks.

## Operating commands

```sh
python3 scripts/deploy.py --env-file /absolute/deployment.env config
python3 scripts/deploy.py --env-file /absolute/deployment.env status
python3 scripts/deploy.py --env-file /absolute/deployment.env logs
python3 scripts/deploy.py --env-file /absolute/deployment.env stop
python3 scripts/deploy.py --env-file /absolute/deployment.env up
```

`compose` forwards explicit Compose arguments for operations such as inspecting
the project or performing the recovery guide's volume backup commands.

Use `--project` only to create another deployment deliberately. Reuse the same
project/config to retain named volumes. `up` never rebuilds application images.
Optional index/import services remain disabled. The helper does not restart
Docker Desktop or unrelated containers. Docker Desktop and the external NAS
volume must be available; container restart policies do not establish unattended
macOS startup before user login.

Copy incoming files under `.part` names, then rename to `.mp4`/`.mkv` after copying.
The worker waits 60 seconds for settled inputs. Enqueue copies: successful
publication removes the inbox copy. Keep authoritative originals elsewhere.
Outputs appear in `library`; work snapshots remain retained.

## Backup, upgrade and rollback

Use the [cold-backup boundary](recovery.md#backup-boundary) with this layout:

| Component | Location |
|---|---|
| Histories, visibility, namespace UUID | Local `postgres-data` volume |
| Catalog, stats, reservations, namespace binding | Local `catalog-data` volume |
| Inputs, cleanup receipts, identity epochs | NAS `inbox` and `work` |
| Published outputs and archives | NAS `library` and `archive` |
| Configuration | Checkout/revision, image pin, env file, project and subpath |
| Private credentials | Existing PostgreSQL password and NAS connection configuration |

Pause arrivals and run `stop`. It stops only this project, including PostgreSQL
and Temporal. While stopped, archive both local volumes in full and snapshot or
archive all four NAS directories as one backup generation. Keep configuration
and credentials in a private backup, outside Public media reports. Do not copy a
live SQLite file alone or omit work referenced by histories. Verify archives
before `up`. The recovery guide gives the volume-copy commands and PostgreSQL
clean-shutdown requirements.

Before upgrading, record the current image and complete a cold backup. Change
only the reviewed image pin, run `check`, then `up` and `status`. Preserve the
project, NAS volume/subpath, secret and namespace. Confirm previous submission IDs
and artifacts remain before accepting arrivals. Keep the old image for rollback.

For a compatible application-only rollback, restore its previous image pin and
restart this project. For incompatible database/history changes, restore the
matching complete backup generation using the recovery procedure. A new namespace
with the same name does not restore its identity; the catalog rejects that
mismatch. Legacy services have not been retired.
