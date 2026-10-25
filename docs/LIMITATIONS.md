# Limitations

This is a pilot for one allowlisted Maven repository and one build profile, `verify`.

- No SSO. Dashboard access is one configured password. API access is one bearer token.
- No Gradle, no multi-module reactor edits, no parent or BOM rewrites, no property versions, no major upgrades.
- Test, provided, and system scopes, and scanner dependency groups `test` or `dev`, stay on manual review.
- A coordinate the scanner resolves from a BOM or reports with source type `unknown` and no matching explicit version stays on manual review.
- `mvn verify` runs the tests already bound in the repository. There is no integration-test profile. OSV-Scanner is not a complete security assessment.
- The runner image is pinned, but a container is not the production trust boundary. The worker's Docker socket is a documented pilot shortcut.
- GitHub App publishing needs credentials this pilot does not contain. Without them a verified run stops at `PUBLICATION_NOT_CONFIGURED`.
- Model output is limited to a server-supplied candidate list. The pilot demo uses server policy and does not present a stubbed model response as a model call.
- Private registries, Maven extensions, `.mvn/maven.config`, and wrapper execution are rejected.
- Publication refuses a new advisory pair, a feed change on an untouched coordinate, a stale base, skipped tests, or a diff that touches anything other than the approved version text.
