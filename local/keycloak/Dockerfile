FROM quay.io/keycloak/keycloak:26.8.0@sha256:b0f60d489d51c5d113390bdf5461d4c06e6051be026c05549f2e1e10ec352bcc AS keycloak
FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS runtime
RUN apk add --no-cache 'zlib>=1.3.2-r1' 'libpng>=1.6.59-r0' 'libexpat>=2.9.0-r0' \
    'musl>=1.2.6-r3' 'musl-utils>=1.2.6-r3' 'brotli-libs>=1.2.1-r0'
FROM runtime AS patched
COPY --from=keycloak --chown=65532:0 /opt/keycloak /opt/keycloak
ADD --checksum=sha256:b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c --chown=65532:0 https://repo.maven.apache.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar /opt/keycloak/lib/lib/main/com.google.guava.guava-33.7.2-jre.jar
ADD --checksum=sha256:42604178b9e57ec08ee0035079961516f086e455d0141f0b22c6eff29fb2fff4 --chown=65532:0 https://repo.maven.apache.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.11/mariadb-java-client-3.5.11.jar /opt/keycloak/lib/lib/main/org.mariadb.jdbc.mariadb-java-client-3.5.11.jar
ADD --checksum=sha256:538d1dff2f9a37aecbc0ef98fd17273a3affb058e734e6763a3a07a38a5fdc59 --chown=65532:0 https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-api/1.67.0/opentelemetry-api-1.67.0.jar /opt/keycloak/lib/lib/main/io.opentelemetry.opentelemetry-api-1.67.0.jar
ADD --checksum=sha256:40fb813fb5e27ec2691daa29c81b572b5b1541ef9b41e62490c3ee3e6407c55e --chown=65532:0 https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-context/1.67.0/opentelemetry-context-1.67.0.jar /opt/keycloak/lib/lib/main/io.opentelemetry.opentelemetry-context-1.67.0.jar
# Quarkus bootstrap metadata retains the vendor paths; aliases load only the patched JARs.
RUN rm /opt/keycloak/lib/lib/main/com.google.guava.guava-33.6.0-jre.jar \
    /opt/keycloak/lib/lib/main/org.mariadb.jdbc.mariadb-java-client-3.5.10.jar \
    /opt/keycloak/lib/lib/main/io.opentelemetry.opentelemetry-api-1.62.0.jar \
    /opt/keycloak/lib/lib/main/io.opentelemetry.opentelemetry-context-1.62.0.jar \
    && ln -s com.google.guava.guava-33.7.2-jre.jar /opt/keycloak/lib/lib/main/com.google.guava.guava-33.6.0-jre.jar \
    && ln -s org.mariadb.jdbc.mariadb-java-client-3.5.11.jar /opt/keycloak/lib/lib/main/org.mariadb.jdbc.mariadb-java-client-3.5.10.jar \
    && ln -s io.opentelemetry.opentelemetry-api-1.67.0.jar /opt/keycloak/lib/lib/main/io.opentelemetry.opentelemetry-api-1.62.0.jar \
    && ln -s io.opentelemetry.opentelemetry-context-1.67.0.jar /opt/keycloak/lib/lib/main/io.opentelemetry.opentelemetry-context-1.62.0.jar
ENV KC_RUN_IN_CONTAINER="true"
WORKDIR /opt/keycloak
USER 65532:0
RUN /opt/keycloak/bin/kc.sh build --db=dev-file
FROM runtime
COPY --from=patched --chown=65532:0 /opt/keycloak /opt/keycloak
ENV KC_RUN_IN_CONTAINER="true"
WORKDIR /opt/keycloak
USER 65532:0
EXPOSE 8080 8443 9000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
