# Two stages: build the jar with the Maven wrapper, run it on a small JRE image as a non-root user.
# Tests are not run here; CI runs them on every pull request.

FROM docker.io/library/eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Sources uploaded from Windows (e.g. gcloud builds submit) carry no executable bit.
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests && cp target/chat-service-*.jar app.jar

FROM docker.io/library/eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /build/app.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=cloud
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
