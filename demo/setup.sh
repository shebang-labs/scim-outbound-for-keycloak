#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Creates a SCIM target in the demo realm and provisions the existing users with a reconciliation.
set -euo pipefail

KC=${KC:-http://localhost:8080}
API="$KC/admin/realms/demo/scim-outbound"

echo "Waiting for Keycloak..."
until curl -sf "$KC/realms/demo" >/dev/null; do sleep 2; done

TOKEN=$(curl -sf -d client_id=admin-cli -d username=admin -d password=admin -d grant_type=password \
  "$KC/realms/master/protocol/openid-connect/token" | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
auth=(-H "Authorization: Bearer $TOKEN")

echo "Creating target 'demo-app'..."
TARGET=$(curl -sf -X POST "$API/targets" "${auth[@]}" -H 'Content-Type: application/json' -d '{
  "name": "demo-app",
  "baseUrl": "http://scim-app:8080/scim/v2",
  "auth": { "type": "BEARER", "token": "${vault.scim-token}" },
  "syncGroups": true
}')
ID=$(echo "$TARGET" | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo "Target id: $ID"

echo "Testing the connection..."
curl -sf -X POST "$API/targets/$ID/test" "${auth[@]}"; echo

echo "Provisioning existing users (reconciliation)..."
curl -sf -X POST "$API/targets/$ID/reconcile?dryRun=false" "${auth[@]}"; echo
sleep 8
curl -sf "$API/targets/$ID/status" "${auth[@]}"; echo

echo
echo "Done. Requests received by the demo SCIM app: http://localhost:8081/__admin/requests"
echo "Admin console: $KC/admin (admin/admin), realm 'demo'. Metrics: http://localhost:9000/metrics"
