# syntax=docker/dockerfile:1
ARG HANDBRAKE_VERSION=1.11.2
ARG HANDBRAKE_SHA256=12b046350f2422dc28783ff94229aff4ba5fe5e683431e057355d36163b2593a

# Build the official release rather than the older distribution package.
FROM rust:1.88.0-bookworm@sha256:af306cfa71d987911a781c37b59d7d67d934f49684058f96cf72079c3626bfe0 AS handbrake-build
ARG HANDBRAKE_VERSION
ARG HANDBRAKE_SHA256
ARG TARGETARCH
ARG HANDBRAKE_BUILD_JOBS=2
RUN apt-get update && apt-get install -y --no-install-recommends \
      autoconf automake build-essential ca-certificates cmake curl git libass-dev \
      libbz2-dev libfontconfig-dev libfreetype-dev libfribidi-dev libharfbuzz-dev \
      libjansson-dev liblzma-dev libmp3lame-dev libnuma-dev libogg-dev libopus-dev \
      libsamplerate0-dev libspeex-dev libssl-dev libtheora-dev libtool libtool-bin \
      libturbojpeg0-dev libvorbis-dev libvpx-dev libx11-dev libx264-dev libxml2-dev \
      m4 make meson nasm ninja-build patch pkg-config python3 zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
COPY scripts/build-handbrake.sh /usr/local/bin/build-handbrake
RUN build-handbrake
# Backport upstream motion-metric memory fixes without changing the encoder
# release or dropping the established peak-frame-rate policy.
COPY scripts/patches/handbrake-motion-metric-*.patch /opt/handbrake/patches/
RUN cd "/build/HandBrake-${HANDBRAKE_VERSION}" \
    && patch --batch --fuzz=0 -p1 < /opt/handbrake/patches/handbrake-motion-metric-fast.patch \
    && patch --batch --fuzz=0 -p1 < /opt/handbrake/patches/handbrake-motion-metric-arm64.patch \
    && make -C build -j "${HANDBRAKE_BUILD_JOBS}" \
    && install -m 0755 build/HandBrakeCLI /opt/handbrake/HandBrakeCLI \
    && strip /opt/handbrake/HandBrakeCLI

FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS build
WORKDIR /src
COPY pom.xml ./
COPY java ./java
RUN mvn -B -ntp -pl java/modules/ViddyoopCore -am package -DskipTests

FROM debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc AS runtime
ARG HANDBRAKE_VERSION
ARG MKVTOOLNIX_VERSION=74.0.0-1+deb12u1
ARG FFMPEG_VERSION=7:5.1.9-0+deb12u1
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates openjdk-17-jre-headless \
      libfontconfig1 libjansson4 libnuma1 libturbojpeg0 \
      mkvtoolnix="${MKVTOOLNIX_VERSION}" ffmpeg="${FFMPEG_VERSION}" \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 1000 viddyoop \
    && useradd --uid 1000 --gid 1000 --create-home --shell /usr/sbin/nologin viddyoop \
    && mkdir -p /app /inbox /work /library /archive /db \
    && chown -R 1000:1000 /app /inbox /work /library /archive /db
COPY --from=handbrake-build /opt/handbrake/HandBrakeCLI /usr/bin/HandBrakeCLI
COPY --from=handbrake-build /opt/handbrake/LICENSE /usr/share/doc/handbrake/LICENSE
COPY --from=handbrake-build /opt/handbrake/patches /usr/share/doc/handbrake/patches
RUN ldd /usr/bin/HandBrakeCLI > /tmp/handbrake-libraries \
    && cat /tmp/handbrake-libraries \
    && ! grep -q 'not found' /tmp/handbrake-libraries \
    && HandBrakeCLI --version > /tmp/handbrake-version 2>&1 \
    && cat /tmp/handbrake-version \
    && grep -Fx "HandBrake ${HANDBRAKE_VERSION}" /tmp/handbrake-version \
    && rm /tmp/handbrake-version /tmp/handbrake-libraries
LABEL io.viddyoop.handbrake.version="${HANDBRAKE_VERSION}"
LABEL io.viddyoop.handbrake.patches="c81f57ce59d65bbfce94a3473bc53e224c9350cd,c23c4f717d5fcee6db6561d6bfd6d3479adfb709"
COPY --from=build /src/java/modules/ViddyoopCore/target/viddyoop-core-1.0.0-SNAPSHOT.jar /app/viddyoop.jar
WORKDIR /app
USER 1000:1000
ENV VIDYOOP_TEMPORAL=temporal:7233
ENTRYPOINT ["java", "-jar", "/app/viddyoop.jar"]
CMD ["pipeline"]
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 CMD ["java", "-jar", "/app/viddyoop.jar", "health"]

# Opt-in test tooling layered onto the actual runtime/media packages. Never published.
FROM runtime AS verification
USER root
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /usr/share/maven /usr/share/maven
COPY --from=build --chown=1000:1000 /src /src
COPY --from=build --chown=1000:1000 /root/.m2 /m2
WORKDIR /src
USER 1000:1000
HEALTHCHECK NONE
ENTRYPOINT ["/usr/share/maven/bin/mvn", "-Dmaven.repo.local=/m2"]
CMD ["-B", "-ntp", "-pl", "java/modules/ViddyoopCore", "-am", "verify"]

# Keep the default build free of the verification tooling.
FROM runtime AS release
