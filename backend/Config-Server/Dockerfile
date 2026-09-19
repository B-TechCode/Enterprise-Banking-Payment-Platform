# config-server
#
# Build context is the repository root, not this directory: the service depends
# on the root pom and the shared commons-* modules, which a context limited to
# this folder cannot see. docker-compose.yml sets the context accordingly.

# ---- build ------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY backend ./backend

# The cache mount keeps downloaded dependencies between builds. sharing=locked
# makes parallel image builds take turns: the local Maven repository is not
# safe for concurrent writers, and eleven simultaneous Maven runs would also
# exhaust memory on a small machine.
#
# -am builds only the modules this service needs (itself plus commons-*).
# Tests are not compiled here; they belong to the test pipeline, not the image.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl backend/Config-Server -am -Dmaven.test.skip=true package \
 && cp backend/Config-Server/target/Config-Server-0.0.1-SNAPSHOT.jar /workspace/app.jar

# ---- runtime ----------------------------------------------------------------
# JRE only: no compiler or Maven in the image that runs.
FROM eclipse-temurin:21-jre

# curl is for the container healthcheck. The process runs as an unprivileged
# user, not root.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system app \
 && useradd --system --gid app --no-create-home app

WORKDIR /app

COPY --from=build --chown=app:app /workspace/app.jar app.jar

USER app

EXPOSE 8888

# The JVM sizes its heap from the container's memory limit, but defaults to only
# a quarter of it. docker-compose.yml overrides this with the tuned value; this
# default applies when the image is run on its own.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]