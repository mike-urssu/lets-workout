# Base image for the app container: Java 21 runtime + FFmpeg for media previews (architecture DEC-ARCH-015).
# Debian trixie ships FFmpeg 7.1, which reads iPhone HEIC photos (tiled HEIF); Ubuntu-based eclipse-temurin has 6.1.
# Jenkins builds and pushes it on main when this directory changes (Jenkinsfile, stage 'Base image'); Jib then builds
# the app on top of it (build.gradle.kts). To push it by hand:
#   docker build --pull --platform linux/arm64 -t ghcr.io/mike-urssu/workout-backend-base:21-ffmpeg7 deploy/base-image
#   docker push ghcr.io/mike-urssu/workout-backend-base:21-ffmpeg7
FROM debian:trixie-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jre-headless ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*
