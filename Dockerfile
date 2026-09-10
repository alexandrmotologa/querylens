# Multi-stage Dockerfile for QueryLens
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN addgroup --system --gid 1001 querylens && \
    adduser --system --uid 1001 --ingroup querylens querylens

COPY --from=builder /build/target/querylens-1.0.0-SNAPSHOT.jar /app/querylens.jar

RUN chown -R querylens:querylens /app
USER querylens

EXPOSE 5433 8080

ENTRYPOINT ["java", "-jar", "/app/querylens.jar"]
CMD ["proxy", "--listen-port", "5433", "--target-host", "postgres", "--target-port", "5432", "--dashboard-port", "8080"]
