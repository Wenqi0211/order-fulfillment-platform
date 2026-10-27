# Release automation may override these with reviewed immutable digests.
ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-17
ARG JAVA_IMAGE=eclipse-temurin:17-jre-noble
FROM --platform=$BUILDPLATFORM ${MAVEN_IMAGE} AS build
WORKDIR /workspace
COPY pom.xml ./
COPY common ./common
COPY inventory-service ./inventory-service
COPY payment-service ./payment-service
COPY order-service ./order-service
# All three image builds share this identical Maven stage. CI runs tests separately.
RUN mvn -B -ntp -Dmaven.test.skip=true package

FROM ${JAVA_IMAGE} AS runtime
ARG SERVICE
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 fulfillment \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin fulfillment
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/${SERVICE}/target/${SERVICE}-1.0.0-SNAPSHOT.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:InitialRAMPercentage=20.0 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080 8081 8082
HEALTHCHECK --interval=10s --timeout=4s --start-period=60s --retries=12 CMD curl --fail --silent --max-time 3 "http://127.0.0.1:${PORT:-8080}/actuator/health/readiness" > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
