# PawBridge modular monolith runtime image (production). Built locally on the Mac mini and not pushed to a registry.
# Build from a main commit after `./gradlew :boot:bootJar` (see README "운영 배포"):
#   docker build --pull=false --build-arg GIT_SHA=$(git rev-parse HEAD) -t pawbridge-monolith:prod-<sha12> backend-app
# eclipse-temurin:21-jre-noble (arm64), pinned by digest (JAVA_VERSION=jdk-21.0.12.1+1 at pull time).
FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c

ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="pawbridge-monolith" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.description="PawBridge modular monolith (production)"

RUN groupadd --system --gid 10001 pawbridge \
 && useradd --system --uid 10001 --gid pawbridge --home-dir /app --shell /usr/sbin/nologin pawbridge \
 && mkdir -p /app /tmp/pawbridge \
 && chown -R pawbridge:pawbridge /app /tmp/pawbridge

WORKDIR /app
COPY --chown=pawbridge:pawbridge boot/build/libs/pawbridge-backend-app.jar /app/app.jar

USER 10001:10001
# application.properties binds 127.0.0.1 by default; containers must listen on all interfaces.
ENV SERVER_ADDRESS=0.0.0.0 \
    PAWBRIDGE_MANAGEMENT_ADDRESS=0.0.0.0 \
    PAWBRIDGE_MANAGEMENT_PORT=9464 \
    PAWBRIDGE_SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp/pawbridge"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
