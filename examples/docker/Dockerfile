# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# Keycloak with the SCIM outbound plugin, built entirely inside Docker: the server that runs it
# needs Docker only, no Java or Maven. The plugin jar is downloaded from the GitHub release and the
# build fails if its SHA-256 checksum does not match.
#
#   docker build -t keycloak-scim:26.8.1-0.1.0 .
#
# Keep KEYCLOAK_VERSION within the plugin's compatibility table (README.md). To use another plugin
# release, change PLUGIN_VERSION and PLUGIN_SHA256 (from the release's .jar.sha256 file).

ARG KEYCLOAK_VERSION=26.8.1

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder
ARG PLUGIN_VERSION=0.1.0
ARG PLUGIN_SHA256=cb920b0b01f2f449fc506fcbb85131e624f2d4e682f029ea7cd50f52d9e28f09

# Build-time options: kc.sh build fixes them into the image. Match them to your current setup
# (KC_DB is the database vendor; KC_FEATURES, other providers and themes go here too). The plugin
# reads target secrets from Keycloak's vault, hence the files vault.
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true \
    KC_VAULT=file

ADD --checksum=sha256:${PLUGIN_SHA256} --chmod=0644 \
    https://github.com/shebang-labs/scim-outbound-for-keycloak/releases/download/v${PLUGIN_VERSION}/scim-outbound-for-keycloak-${PLUGIN_VERSION}.jar \
    /opt/keycloak/providers/scim-outbound-for-keycloak-${PLUGIN_VERSION}.jar

RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
