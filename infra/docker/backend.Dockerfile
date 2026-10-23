FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY . .
# Normalize the wrapper when the checkout was made on Windows.
RUN sed -i 's/\r$//' gradlew \
    && sh ./gradlew --no-daemon clean test bootJar \
    && cp build/libs/yamu-be-0.0.1-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /app.jar ./app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
