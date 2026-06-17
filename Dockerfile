FROM krccr.ccs.tencentyun.com/uigpt/backend:v4.1.0

WORKDIR /app

ENV SERVER_PORT=8080 \
    MYSQL_HOST=127.0.0.1 \
    MYSQL_PORT=33066 \
    MYSQL_DATABASE=OKX \
    MYSQL_USERNAME=root \
    JAVA_OPTS=""

USER root
RUN mkdir -p /app/static /app/.data && chown -R app:app /app

COPY --chown=app:app target/quant-assistant-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --chown=app:app frontend/dist/ /app/static/

USER app

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --server.port=${SERVER_PORT} --spring.web.resources.static-locations=file:/app/static/"]
