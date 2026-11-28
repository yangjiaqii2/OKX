FROM node:20-alpine AS frontend-build

WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,id=okx-quant-npm,target=/root/.npm,sharing=locked \
    npm ci --prefer-offline --no-audit --no-fund
COPY frontend/ ./
RUN NODE_OPTIONS=--max-old-space-size=1024 npm run build

FROM maven:3.9.9-eclipse-temurin-17 AS backend-build

WORKDIR /workspace
COPY pom.xml ./
COPY src/main/ ./src/main/
RUN --mount=type=cache,id=okx-quant-maven,target=/root/.m2/repository,sharing=locked \
    MAVEN_OPTS="-Xmx512m" mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy AS runtime-base

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
USER app
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=12 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.web.resources.static-locations=file:/app/static/"]

FROM runtime-base AS web-runtime
COPY --from=backend-build --chown=app:app /workspace/target/quant-assistant-*.jar /app/app.jar
COPY --from=frontend-build --chown=app:app /workspace/frontend/dist/ /app/static/

FROM runtime-base AS miren-runtime

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/akshare
COPY akshare-service/requirements.txt /app/akshare-service/requirements.txt
RUN --mount=type=cache,id=okx-quant-pip,target=/root/.cache/pip,sharing=locked \
    /opt/akshare/bin/pip install --disable-pip-version-check -r /app/akshare-service/requirements.txt \
    && /opt/akshare/bin/python -c "import akshare, fastapi, uvicorn"
COPY akshare-service/server.py /app/akshare-service/server.py
COPY --chmod=0555 scripts/start-miren.sh /app/start-miren.sh
COPY --from=web-runtime --chown=app:app /app/app.jar /app/app.jar
COPY --from=web-runtime --chown=app:app /app/static/ /app/static/
USER app
# Miren selects /app/start-miren.sh through services.web.command. A plain
# docker build/run retains web-runtime's Java entrypoint for Compose users.
