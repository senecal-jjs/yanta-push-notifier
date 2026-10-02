FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q > /dev/null || true
COPY src src
RUN ./gradlew installDist --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
# The JVM runs as an unprivileged user. Fly mounts volumes root-owned, so the
# entrypoint fixes the data directory's owner and then drops privileges.
RUN apk add --no-cache su-exec \
 && addgroup -S push && adduser -S -G push -H push
WORKDIR /app
COPY --from=build /app/build/install/push-service ./
COPY docker-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080 9091
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["bin/push-service"]
