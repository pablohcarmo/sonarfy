FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 sonarfy \
    && useradd --uid 10001 --gid 10001 --no-create-home sonarfy
WORKDIR /app
COPY --chown=10001:10001 target/container/app.jar app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=32m -Xss256k"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/profile/me)" = 401
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.config.location=file:/config/application.properties"]
