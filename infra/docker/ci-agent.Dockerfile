FROM eclipse-temurin:17-jdk-jammy AS application_jdk
FROM node:24-bookworm-slim AS node
FROM docker:28-cli AS docker_cli

FROM jenkins/inbound-agent:jdk21
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 libatomic1 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=application_jdk /opt/java/openjdk /opt/jdk17
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
COPY --from=docker_cli /usr/local/bin/docker /usr/local/bin/docker
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx

# Remoting uses Java 21; only Gradle and application tests use Java 17.
ENV JENKINS_JAVA_BIN=/opt/java/openjdk/bin/java
ENV JAVA_HOME=/opt/jdk17
ENV PATH="/opt/jdk17/bin:${PATH}"
USER jenkins
