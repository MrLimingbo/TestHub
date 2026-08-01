FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY services/platform-api/pom.xml services/platform-api/pom.xml
COPY services/platform-api/src services/platform-api/src
RUN mvn -q -pl services/platform-api -am package -DskipTests

FROM gcr.io/distroless/java21-debian13@sha256:e7c40c2378f8c4462aea63041f6b29b7f2b9ab8e80b9217d6d5faf70dfff1779
WORKDIR /app
COPY --from=build --chown=nonroot:nonroot /workspace/services/platform-api/target/platform-api-0.1.0-SNAPSHOT.jar /app/testhub-platform-api.jar
USER nonroot
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/testhub-platform-api.jar"]
