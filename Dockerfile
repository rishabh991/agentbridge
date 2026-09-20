# One image recipe for both JVM modules; pick which with --build-arg MODULE=gateway|orders-service
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy poms first so dependency resolution is cached across source-only changes.
COPY pom.xml ./
COPY gateway/pom.xml gateway/
COPY orders-service/pom.xml orders-service/
RUN mvn -B -ntp dependency:go-offline || true

COPY gateway/src gateway/src
COPY orders-service/src orders-service/src

ARG MODULE
RUN mvn -B -ntp -pl ${MODULE} -am package -DskipTests \
    && cp ${MODULE}/target/*.jar /build/app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
