FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon help >/dev/null
COPY src src
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl openssl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/build/install/mcp-proxy /app
COPY scenarios /app/scenarios
COPY fixtures /app/fixtures
COPY README.md RUNBOOK.md /app/
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh
VOLUME ["/app/var"]
EXPOSE 18081
ENTRYPOINT ["/app/docker-entrypoint.sh"]
