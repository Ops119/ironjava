# Troubleshooting

## The app exits immediately

`IRONJAVA_AUTH_PASSWORD` is required when `IRONJAVA_ROLE` is `CONTROL` or `ALL`. Set it in `.env`. The worker profile may start without a dashboard password.

## Health is up and scans stay queued

The worker loop is off, the queue is paused, or the role is `CONTROL` on the only process. `ALL` runs both loops. Paused queues do not take new claims. In-flight work is left alone.

## `REPOSITORY_INACCESSIBLE`

`IRONJAVA_FIXTURE_LOCAL_PATH` is empty or is not a checkout of `cursor/ironjava-acceptance-fixture-68a8` with a root `pom.xml`. The branch must exist as `refs/heads/<allowed-branch>`. Clients cannot send a clone URL to fix this.

## `BASELINE_FAILED`

`mvn verify` at the recorded commit exited non-zero. IronJava does not scan after that. This is not the same as a scanner failure.

## `SCAN_INCONCLUSIVE`

OSV-Scanner was missing, exited with a code other than 0 or 1, returned empty output, returned exit 1 with no advisories, or returned a partial report. That is not a clean scan and not a baseline failure.

## `RUNNER_UNAVAILABLE`

`IRONJAVA_RUNNER_MODE=container` and `docker version` failed. The build is not executed on the host as a fallback. Use `process` for a workstation loop and record isolation as `process`.

## `PUBLICATION_NOT_CONFIGURED`

Verification passed and the GitHub App id, private key, installation id, or GitHub source is missing. Evidence stays on the run. No pull request was opened.

## `MODEL_UNAVAILABLE`

`IRONJAVA_MODEL_MODE=required` and the endpoint, model name, or key file is missing or the provider did not answer. No candidate was invented. `disabled` does not call a model. It picks the lowest permitted server candidate and labels the source `SERVER_POLICY`.

## `TESTS_FAILED` on `scenario/test-failure`

Commons Text 1.10.0 removes the legacy DNS, URL, and script lookups. `LegacyDefaultLookupsTest` fails. That run must not publish.

## `STALE_BASE` or idempotency 409

The allowlisted branch moved, the finding is no longer eligible, or the `Idempotency-Key` was reused with a different body. Start a new key only for a new request. The same key and same body replay the stored response.

## Logs

Log lines pass through a redacting converter. Tokens, PEM blocks, `Bearer` values, and `password=` / `api_key=` assignments are replaced. Do not add those values to dashboard fields.

## Cleanup and shutdown

Workspaces under `IRONJAVA_WORKSPACE_ROOT` are deleted when a run reaches a terminal state and again after the retention window. Database evidence and a published pull request body are not part of that cleanup. Stopping the process marks leases owned by that process `INTERRUPTED`. Those runs are not resumed, including a publish that already started.
