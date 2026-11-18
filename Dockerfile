FROM node:20-alpine AS frontend-build

WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-17 AS backend-build

WORKDIR /workspace
COPY pom.xml ./
COPY src/ ./src/
RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:17-jre-jammy

LABEL org.opencontainers.image.title="OKX Quant Assistant" \
      org.opencontainers.image.description="OKX quant trading terminal"

ENV SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8" \
    TZ=Asia/Shanghai

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app/static \
    && chown -R app:app /app

WORKDIR /app
COPY --from=backend-build --chown=app:app /workspace/target/quant-assistant-*.jar /app/app.jar
COPY --from=frontend-build --chown=app:app /workspace/frontend/dist/ /app/static/

USER app
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=12 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.web.resources.static-locations=file:/app/static/"]
