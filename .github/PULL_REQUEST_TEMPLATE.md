## What and why

<!-- What does this change do, and which problem does it solve? Link the issue: Fixes #123 -->

## How it was tested

<!-- Unit tests, integration tests, manual checks against a real application. -->

## Checklist

- [ ] All commits are signed off (`git commit -s`, see CONTRIBUTING.md)
- [ ] `./mvnw verify` passes locally
- [ ] Tests cover the new or changed behaviour
- [ ] No network calls inside Keycloak transactions or on the login path
- [ ] No secrets, tokens or personal data are logged
- [ ] No new bundled dependency (or it is justified and Apache-2.0 compatible)
- [ ] Docs and `CHANGELOG.md` updated for user-visible changes
