# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS build
WORKDIR /src
COPY pom.xml ./
COPY java ./java
RUN mvn -B -ntp -pl java/modules/ViddyoopCore -am package -DskipTests

FROM debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc AS runtime
ARG HANDBRAKE_VERSION=1.6.1+ds1-1
ARG MKVTOOLNIX_VERSION=74.0.0-1+deb12u1
ARG FFMPEG_VERSION=7:5.1.9-0+deb12u1
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates openjdk-17-jre-headless \
      handbrake-cli="${HANDBRAKE_VERSION}" mkvtoolnix="${MKVTOOLNIX_VERSION}" ffmpeg="${FFMPEG_VERSION}" \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 1000 viddyoop \
    && useradd --uid 1000 --gid 1000 --create-home --shell /usr/sbin/nologin viddyoop \
    && mkdir -p /app /inbox /work /library /archive /db \
    && chown -R 1000:1000 /app /inbox /work /library /archive /db
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
