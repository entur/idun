FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts version.txt ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version
COPY src src
RUN ./gradlew --no-daemon installDist -x test

FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY --from=build /workspace/build/install/idun /app
ENV PORT=8080
EXPOSE 8080
USER nonroot
ENTRYPOINT ["java", "-cp", "/app/lib/*", "org.entur.idun.ApplicationKt"]
