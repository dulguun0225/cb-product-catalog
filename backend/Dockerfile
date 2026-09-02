# PLATFORM-14 · 001-product-family-catalog · runtime image.
#
# Copies a finished artifact rather than building inside the image (plan §9). The base is
# tag-pinned, not digest-pinned: plan §9 declined digest pinning for this feature and lld §7
# records it as ungated.
#
# java-backend-observability, "The weaving-agent ban": there is no -javaagent anywhere in this
# entrypoint, and no agent JAR in the image. Instrumentation is visible program text.
FROM eclipse-temurin:21-jre-alpine

ARG APP_PORT=8080
ENV APP_PORT=${APP_PORT}
EXPOSE ${APP_PORT}

# Non-root. The application writes nothing to the filesystem.
RUN addgroup --system catalog && adduser --system --ingroup catalog catalog
USER catalog

WORKDIR /app
COPY --chown=catalog:catalog target/*.jar /app/application.jar

ENTRYPOINT ["sh", "-c", "exec java -jar /app/application.jar --server.port=${APP_PORT}"]
