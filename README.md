# IronJava

IronJava prepares verified dependency fixes for a small allowlist of Maven repositories. It baselines the repository, scans with a pinned OSV-Scanner, patches one explicit production version, re-runs the build, and opens a draft pull request only when every publication gate passes.

The acceptance fixture is [Ops119/ironjava-fixture](https://github.com/Ops119/ironjava-fixture) on branch `cursor/ironjava-acceptance-fixture-68a8` (PR #1). This repository stores the allowlist entry and the `verify` build profile. It does not vendor the vulnerable sample.

## Pins

| Tool | Pin |
| --- | --- |
| JDK | 21, evidence build 21.0.12.1 |
| Spring Boot | 4.1.2 |
| Maven used by the runner | 3.9.16, SHA-512 `831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6` |
| OSV-Scanner | 2.7.0, SHA-256 `160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b` |
| PostgreSQL image | 17.6 `sha256:00bc86618629af00d2937fdc5a5d63db3ff8450acf52f0636ec813c7f4902929` |

The scanner command is `osv-scanner scan source --recursive --format json --verbosity error --x-exclude .m2 --x-exclude target` with the default deps.dev resolver. The excludes keep the workspace Maven cache and build output out of the project scan. Exit code 1 with a parsed report means findings. Any other failure is inconclusive, not a clean scan.

## Install

```bash
cp .env.example .env
# Set IRONJAVA_AUTH_PASSWORD and POSTGRES_PASSWORD.
git clone --depth 1 --branch cursor/ironjava-acceptance-fixture-68a8 \
  https://github.com/Ops119/ironjava-fixture.git /tmp/ironjava-fixture
git clone --depth 1 --branch scenario/test-failure \
  https://github.com/Ops119/ironjava-fixture.git /tmp/ironjava-fixture-test-failure
```

Point `.env` at those checkouts:

```bash
IRONJAVA_FIXTURE_LOCAL_PATH=/tmp/ironjava-fixture
IRONJAVA_FIXTURE_TEST_FAILURE_PATH=/tmp/ironjava-fixture-test-failure
IRONJAVA_MODEL_MODE=disabled
```

`disabled` selects the lowest server-supplied candidate. No model is called. A missing model in `required` mode blocks with `MODEL_UNAVAILABLE` and does not invent a recommendation.

## Start with Docker Compose

Docker was not available in the environment that produced this pilot, so the compose file is the supported startup path and was not executed here.

```bash
docker compose --profile tools build
docker compose up --build
```

- `control` plans, calls the model when configured, and publishes. It does not receive the Docker socket.
- `worker` baselines, scans, and verifies. It does not receive GitHub App or model secrets. Container mode mounts `/var/run/docker.sock`. That socket is a pilot limitation, not a production trust boundary.
- Build the `runner` profile before using `IRONJAVA_RUNNER_MODE=container`. If Docker is missing, the runner reports `RUNNER_UNAVAILABLE` and does not fall back onto the host.
- Open `http://localhost:8080`, sign in with `IRONJAVA_AUTH_USERNAME` / `IRONJAVA_AUTH_PASSWORD`. Health is `GET /actuator/health` with no session.

## Start one process without Compose

Use this when you want the local process runner. Isolation is the host process with a scrubbed environment, not a container.

```bash
export IRONJAVA_ROLE=ALL
export IRONJAVA_WORKER_ENABLED=true
export IRONJAVA_CONTROL_LOOP_ENABLED=true
export IRONJAVA_RUNNER_MODE=process
export IRONJAVA_AUTH_PASSWORD=change-me
export POSTGRES_HOST=localhost
export POSTGRES_PASSWORD=ironjava
mvn -B -ntp -DskipTests package
java -jar target/ironjava.jar
```

PostgreSQL must already be running and reachable with the credentials in `.env`.

## Demo script

1. Sign in to the dashboard.
2. Queue a scan of `pilot-fixture` (branch `cursor/ironjava-acceptance-fixture-68a8`).
3. Expect a completed scan. `org.apache.commons:commons-text` 1.9 is eligible for `1.10.0` (`GHSA-599f-7c49-w659` / `CVE-2022-42889`). SnakeYAML 1.33 is manual review because the only fix is 2.0. Guava via `guava-bom` 31.1-jre is manual review because the version is BOM-managed and the scanner source type is `unknown`. Gson 2.8.6 is manual review because OSV-Scanner reports `dependency_groups: ["test"]`.
4. Prepare the Commons Text fix. With `IRONJAVA_MODEL_MODE=disabled`, the run evidence says `SERVER_POLICY` and no model was called.
5. Expect the candidate `mvn verify` to report 17 tests and the rescan to drop only the Commons Text advisory pairs. The run then stops `BLOCKED` / `PUBLICATION_NOT_CONFIGURED` unless GitHub App credentials are set. No pull request is opened, and `publish_attempted` stays false because no GitHub write started. Requesting the same coordinate again after credentials are configured starts a new run. An already opened pull request is reused.
6. Queue `pilot-fixture-test-failure`. Preparing the same 1.10.0 upgrade fails `LegacyDefaultLookupsTest` and ends `FAILED` / `TESTS_FAILED`. Nothing is published.

API calls need `Authorization: Bearer $IRONJAVA_API_TOKEN` and `Idempotency-Key`. The bearer request skips CSRF. Form posts include the CSRF token and a fresh idempotency key. Reusing a key with a different body returns 409. Unknown repositories, extra fields, and findings that are not eligible return 422.

Pause the queue from the dashboard. In-flight work continues. New claims wait.

Cancel a queued or running scan or remediation before publish is attempted. After `publish_attempted` is set, cancellation does not roll back a partial publish. A lost lease marks the run `INTERRUPTED` and does not resume it.

## What you must supply for a real draft pull request

| Name | Where |
| --- | --- |
| `IRONJAVA_GITHUB_APP_ID` | GitHub App id |
| `IRONJAVA_GITHUB_PRIVATE_KEY_PATH` | PEM file mounted into the control process only |
| `IRONJAVA_GITHUB_INSTALLATION_ID` | Installation on `Ops119/ironjava-fixture` |
| `IRONJAVA_FIXTURE_SOURCE=github` | So the publisher can push the branch |
| `IRONJAVA_MODEL_MODE=required` plus `IRONJAVA_MODEL_ENDPOINT`, `IRONJAVA_MODEL_NAME`, and `IRONJAVA_MODEL_API_KEY_PATH` | Only if a model should rank candidates |

App permissions: metadata read, contents read/write, pull requests read/write. No workflow and no administration.

Leave the GitHub values empty to keep the verified evidence and stop before publication. That is the honest result of this pilot.

## Tests

```bash
mvn -B -ntp test
```

The default suite does not clone the fixture or call GitHub. `IRONJAVA_LIVE=true` runs `LiveFixtureIT` against the real fixture with the process runner and stops before publication.

More detail: [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) and [docs/LIMITATIONS.md](docs/LIMITATIONS.md).
