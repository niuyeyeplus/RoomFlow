# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and project files
COPY backend/.mvn ./.mvn
COPY backend/mvnw ./
COPY backend/pom.xml ./
COPY backend/src ./src

# Ensure the wrapper is executable and build the jar (tests run in CI)
# Unset MAVEN_CONFIG: the base image sets it to /root/.m2 which the Maven
# wrapper misinterprets as a lifecycle phase ("Unknown lifecycle phase /root/.m2")
ENV MAVEN_CONFIG=""
RUN chmod +x ./mvnw && \
    ./mvnw -B -DskipTests=true package

# Promote the generated jar to a stable path for the runner stage
RUN mkdir -p /app && \
    cp /build/target/roomflow-backend-*.jar /app/app.jar

FROM eclipse-temurin:21-jre-alpine AS runner

# Create a non-root user with uid 1000
RUN addgroup -g 1000 appgroup && \
    adduser -D -u 1000 -G appgroup appuser

WORKDIR /app

COPY --from=builder /app/app.jar /app/app.jar

USER 1000:1000

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
