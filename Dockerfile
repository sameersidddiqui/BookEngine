FROM gradle:9.8.1-jdk21-alpine AS build

WORKDIR /workspace

COPY --chown=gradle:gradle gradlew build.gradle settings.gradle ./
COPY --chown=gradle:gradle gradle ./gradle
COPY --chown=gradle:gradle src ./src
USER gradle
RUN chmod +x gradlew

RUN ./gradlew --no-daemon clean bootJar \
	&& mkdir -p /workspace/package \
	&& jar="$(find /workspace/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
	&& test -n "$jar" \
	&& cp "$jar" /workspace/package/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

RUN addgroup -S spring \
	&& adduser -S spring -G spring

COPY --from=build --chown=spring:spring /workspace/package/app.jar /app/app.jar

USER spring:spring

EXPOSE 10000

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -Dserver.port=${PORT:-10000} -jar /app/app.jar"]
