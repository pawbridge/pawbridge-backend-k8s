# Build context is exactly one service's build/migration directory.
FROM eclipse-temurin:17-jre-jammy@sha256:24cd8eed18b5976441d27b45823490eb5e8efff4b3ecdc632e442717ea66f160
WORKDIR /opt/migration
COPY --chown=1000:1000 lib/ ./lib/
USER 1000:1000
ENTRYPOINT ["java"]
