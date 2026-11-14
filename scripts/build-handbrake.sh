#!/usr/bin/env bash
# Called only in the disposable Docker build stage; no source/tools enter runtime.
set -euo pipefail
: "${HANDBRAKE_VERSION:?}" "${HANDBRAKE_SHA256:?}" "${TARGETARCH:?}"

# Official cargo-c release helpers are static musl binaries. Rust itself is
# pinned by the builder image; cargo-c is needed for libdovi's C interface.
case "$TARGETARCH" in
  amd64) cargo_arch=x86_64; cargo_sha=0afd191619fd93cb2989a9239e89a059ea0dfe78eb3a86b7728717fa08a832a2 ;;
  arm64) cargo_arch=aarch64; cargo_sha=9c0042f3e2053d267564ba64975c1bcaa2408522d818bd7fc1033c6d0e9d1308 ;;
  *) echo "Unsupported media build architecture: $TARGETARCH" >&2; exit 2 ;;
esac
mkdir -p /build /opt/handbrake
cd /build
curl -fsSL --retry 3 -o cargo-c.tar.gz \
  "https://github.com/lu-zero/cargo-c/releases/download/v0.10.13/cargo-c-${cargo_arch}-unknown-linux-musl.tar.gz"
printf '%s  %s\n' "$cargo_sha" cargo-c.tar.gz | sha256sum --check -
tar -xzf cargo-c.tar.gz -C /usr/local/bin
curl -fsSL --retry 3 -o handbrake.tar.bz2 \
  "https://github.com/HandBrake/HandBrake/releases/download/${HANDBRAKE_VERSION}/HandBrake-${HANDBRAKE_VERSION}-source.tar.bz2"
printf '%s  %s\n' "$HANDBRAKE_SHA256" handbrake.tar.bz2 | sha256sum --check -
tar -xjf handbrake.tar.bz2
cd "HandBrake-${HANDBRAKE_VERSION}"
export CARGO_BUILD_JOBS="${HANDBRAKE_BUILD_JOBS:-2}"
./configure --disable-gtk --enable-libdovi --launch-jobs="${HANDBRAKE_BUILD_JOBS:-2}" --launch
install -m 0755 build/HandBrakeCLI /opt/handbrake/HandBrakeCLI
strip /opt/handbrake/HandBrakeCLI
install -m 0644 LICENSE /opt/handbrake/LICENSE
