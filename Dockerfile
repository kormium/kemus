# Runtime image for the native kemus-server binary — no JVM, just the self-contained Kotlin/Native
# executable on a minimal glibc base (distroless/cc provides libc/libstdc++/libgcc).
#
# Multi-arch: buildx sets TARGETARCH (amd64 / arm64) per platform; we copy the matching prebuilt
# binary that CI (or you) staged under docker/<arch>/kemus-server. Build the binaries first:
#
#   ./gradlew :kemus-server:linkReleaseExecutableLinuxX64 :kemus-server:linkReleaseExecutableLinuxArm64
#   mkdir -p docker/amd64 docker/arm64
#   cp kemus-server/build/bin/linuxX64/releaseExecutable/kemus-server.kexe   docker/amd64/kemus-server
#   cp kemus-server/build/bin/linuxArm64/releaseExecutable/kemus-server.kexe docker/arm64/kemus-server
#   docker buildx build --platform linux/amd64,linux/arm64 -t kemus-server .
#
# CI (.github/workflows/docker.yml) does exactly this, then pushes to Docker Hub.
FROM gcr.io/distroless/cc-debian12

ARG TARGETARCH
COPY docker/${TARGETARCH}/kemus-server /usr/local/bin/kemus-server

ENV KEMUS_PORT=6390
EXPOSE 6390

# Override via env: KEMUS_PORT, KEMUS_AOF (mount a volume for the AOF to persist), KEMUS_SYNC.
ENTRYPOINT ["/usr/local/bin/kemus-server"]
