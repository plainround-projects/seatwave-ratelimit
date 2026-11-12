# One image recipe for the five Spring Boot programs; Compose builds it once per program with the
# PROGRAM argument. The base is the Docker Official Image of Eclipse Temurin's Java 25 runtime,
# pinned by tag and digest, checked on Docker Hub on 12 November 2026 (it has an arm64 variant).
# The tag was rebuilt that day, so the digest differs from the one checked on 11 November.
# Build the jars first: ./gradlew bootJar.

# Stage 1: unpack the Spring Boot jar into layers (libraries, then the program), so a rebuild after
# a code change reuses the library layer.
FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c AS layers
ARG PROGRAM
COPY ${PROGRAM}/build/libs/${PROGRAM}-1.0.0.jar /work/app.jar
RUN java -Djarmode=tools -jar /work/app.jar extract --layers --destination /work/extracted

# Stage 2: the runtime, run by a user with no login, no home folder and no rights beyond reading
# the application. 10001 is above Ubuntu's system user ids (up to 999).
FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c
RUN useradd --uid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin seatwave
WORKDIR /app
COPY --from=layers /work/extracted/dependencies/ ./
COPY --from=layers /work/extracted/spring-boot-loader/ ./
COPY --from=layers /work/extracted/snapshot-dependencies/ ./
COPY --from=layers /work/extracted/application/ ./
USER 10001
# The exec form makes Java process 1, so docker stop's SIGTERM reaches it and the graceful
# shutdown of server.shutdown runs.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/app.jar"]
