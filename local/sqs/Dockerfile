FROM eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS build
WORKDIR /build
COPY gradlew ./
COPY gradle/wrapper/ gradle/wrapper/
COPY local/sqs/build.gradle local/sqs/settings.gradle local/sqs/
RUN ./gradlew -p local/sqs stageRuntime --no-daemon

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
RUN apk add --no-cache 'zlib>=1.3.2-r1' 'libpng>=1.6.59-r0' 'libexpat>=2.9.0-r0' \
    'musl>=1.2.6-r3' 'musl-utils>=1.2.6-r3' 'brotli-libs>=1.2.1-r0'
COPY --from=build /build/local/sqs/build/runtime/ /opt/elasticmq/
USER 10001:10001
ENTRYPOINT ["java", "-Dconfig.file=/opt/elasticmq.conf", "-cp", "/opt/elasticmq/lib/*", "org.elasticmq.server.Main"]
