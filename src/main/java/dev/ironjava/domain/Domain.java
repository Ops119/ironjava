package dev.ironjava.domain;

import java.util.EnumSet;
import java.util.Set;

public final class Domain {
    private Domain() {
    }

    public enum Role {
        CONTROL, WORKER, ALL
    }

    public enum RunKind {
        SCAN, REMEDIATION
    }

    public enum RunState {
        QUEUED,
        BASELINING,
        SCANNING,
        COMPLETED,
        PLANNING,
        PATCHING,
        VERIFYING,
        PUBLISHING,
        PR_OPENED,
        BLOCKED,
        FAILED,
        CANCELLED,
        INTERRUPTED;

        public boolean terminal() {
            return this == COMPLETED || this == PR_OPENED || this == BLOCKED || this == FAILED
                    || this == CANCELLED || this == INTERRUPTED;
        }
    }

    public enum ReasonCode {
        BASELINE_FAILED,
        UNSUPPORTED_VERSION_SOURCE,
        TESTS_FAILED,
        SCAN_INCONCLUSIVE,
        STALE_BASE,
        BUDGET_EXCEEDED,
        LIMIT_EXCEEDED,
        REPOSITORY_INACCESSIBLE,
        INSUFFICIENT_TEST_EVIDENCE,
        MODEL_OUTPUT_REJECTED,
        MODEL_UNAVAILABLE,
        PUBLICATION_NOT_CONFIGURED,
        PUBLISH_RECONCILE_REQUIRED,
        TARGET_ADVISORY_PRESENT,
        NEW_ADVISORY_PAIR,
        DIFF_REJECTED,
        POLICY_VIOLATION,
        RUNNER_UNAVAILABLE,
        UNSUPPORTED_MAVEN_EXTENSIONS,
        NO_ELIGIBLE_CANDIDATE,
        MAJOR_UPGRADE_REQUIRED,
        CANDIDATE_RESOLUTION_FAILED,
        METADATA_UNAVAILABLE,
        IDEMPOTENCY_KEY_REUSED,
        NOT_ELIGIBLE
    }

    public enum Eligibility {
        ELIGIBLE, MANUAL_REVIEW, INELIGIBLE
    }

    public enum VersionSource {
        EXPLICIT, PROPERTY, MANAGED, UNKNOWN
    }

    public enum SourceKind {
        LOCAL, GITHUB
    }

    public static final class Transitions {
        private static final Set<RunState> TERMINAL = EnumSet.of(
                RunState.COMPLETED, RunState.PR_OPENED, RunState.BLOCKED, RunState.FAILED,
                RunState.CANCELLED, RunState.INTERRUPTED);

        private Transitions() {
        }

        public static boolean allowed(RunKind kind, RunState from, RunState to) {
            if (from == null || to == null || from.terminal()) {
                return false;
            }
            if (to == RunState.BLOCKED || to == RunState.FAILED || to == RunState.CANCELLED || to == RunState.INTERRUPTED) {
                return true;
            }
            if (kind == RunKind.SCAN) {
                return switch (from) {
                    case QUEUED -> to == RunState.BASELINING;
                    case BASELINING -> to == RunState.SCANNING;
                    case SCANNING -> to == RunState.COMPLETED;
                    default -> false;
                };
            }
            return switch (from) {
                case QUEUED -> to == RunState.PLANNING;
                case PLANNING -> to == RunState.PATCHING;
                case PATCHING -> to == RunState.VERIFYING;
                case VERIFYING -> to == RunState.PUBLISHING;
                case PUBLISHING -> to == RunState.PR_OPENED;
                default -> false;
            };
        }

        public static void require(RunKind kind, RunState from, RunState to) {
            if (!allowed(kind, from, to)) {
                throw new IllegalTransitionException(kind, from, to);
            }
        }

        public static boolean terminal(RunState state) {
            return state != null && TERMINAL.contains(state);
        }
    }

    public static final class IllegalTransitionException extends RuntimeException {
        public IllegalTransitionException(RunKind kind, RunState from, RunState to) {
            super("Illegal " + kind + " transition " + from + " -> " + to);
        }
    }

    public static final class ReasonMessages {
        private ReasonMessages() {
        }

        public static String userMessage(ReasonCode code) {
            if (code == null) {
                return "The run stopped.";
            }
            return switch (code) {
                case BASELINE_FAILED ->
                        "The baseline build or tests failed at the recorded commit. No fix was published.";
                case UNSUPPORTED_VERSION_SOURCE ->
                        "The dependency version is not an explicit root POM version IronJava can edit.";
                case TESTS_FAILED ->
                        "Candidate tests failed. No verified-fix pull request was published.";
                case SCAN_INCONCLUSIVE ->
                        "The scanner did not return a complete result. This is inconclusive, not a clean scan.";
                case STALE_BASE ->
                        "The default branch moved off the verified base commit. Start a new run.";
                case BUDGET_EXCEEDED ->
                        "The model budget was exceeded. No patch was published.";
                case LIMIT_EXCEEDED ->
                        "A stage exceeded its time limit. No verified-fix pull request was published.";
                case REPOSITORY_INACCESSIBLE ->
                        "The repository could not be read at the allowed branch. Check the allowlist and credentials.";
                case INSUFFICIENT_TEST_EVIDENCE ->
                        "No executed tests were observed. That is insufficient test evidence, not a verified fix.";
                case MODEL_OUTPUT_REJECTED ->
                        "The model response was rejected because it was not schema-valid or not one of the server-supplied candidates.";
                case MODEL_UNAVAILABLE ->
                        "The model provider is not configured or did not respond. No recommendation was invented.";
                case PUBLICATION_NOT_CONFIGURED ->
                        "Verification finished, but GitHub App publishing credentials are not configured. No pull request was opened.";
                case PUBLISH_RECONCILE_REQUIRED ->
                        "GitHub may already have a branch or pull request from an ambiguous publish. Reconcile it before retrying. Nothing was marked as a new verified fix.";
                case TARGET_ADVISORY_PRESENT ->
                        "The rescan still reports the targeted advisory for this component. No pull request was published.";
                case NEW_ADVISORY_PAIR ->
                        "The candidate scan added an advisory for a component. No pull request was published.";
                case DIFF_REJECTED ->
                        "The patch changed more than the approved dependency version. Execution stopped.";
                case POLICY_VIOLATION ->
                        "An eligibility or patch policy rule rejected this remediation.";
                case RUNNER_UNAVAILABLE ->
                        "The isolated runner is not available. The build was not executed on the host as a fallback.";
                case UNSUPPORTED_MAVEN_EXTENSIONS ->
                        "The repository has Maven extensions or .mvn configuration that this pilot will not execute.";
                case NO_ELIGIBLE_CANDIDATE ->
                        "No published candidate in the current major version is outside the affected range.";
                case MAJOR_UPGRADE_REQUIRED ->
                        "The only known fixes are outside the current major version. This is manual review.";
                case CANDIDATE_RESOLUTION_FAILED ->
                        "The candidate dependency resolution failed. No pull request was published.";
                case METADATA_UNAVAILABLE ->
                        "Published version metadata could not be confirmed, so no candidate was invented.";
                case IDEMPOTENCY_KEY_REUSED ->
                        "This Idempotency-Key was already used with a different request.";
                case NOT_ELIGIBLE ->
                        "This finding is no longer eligible for an automated fix.";
            };
        }
    }
}
