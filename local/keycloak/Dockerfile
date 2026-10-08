FROM quay.io/keycloak/keycloak:26.8.0@sha256:b0f60d489d51c5d113390bdf5461d4c06e6051be026c05549f2e1e10ec352bcc AS keycloak
FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
COPY --from=keycloak --chown=65532:0 /opt/keycloak /opt/keycloak
ENV KC_RUN_IN_CONTAINER="true"
WORKDIR /opt/keycloak
USER 65532:0
EXPOSE 8080 8443 9000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
