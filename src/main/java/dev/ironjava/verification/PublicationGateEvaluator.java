package dev.ironjava.verification;

import dev.ironjava.domain.Domain.ReasonCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PublicationGateEvaluator {
    public record Input(
            boolean baselineBuildPassed,
            boolean baselineTestsExecuted,
            boolean diffOnlyIntendedVersion,
            boolean resolutionSucceeded,
            boolean testsPassed,
            boolean testsExecuted,
            boolean skipFlagsUsed,
            boolean rescanComplete,
            boolean targetAdvisoryStillPresent,
            boolean newAdvisoryPairs,
            boolean feedChanged,
            boolean budgetExceeded,
            boolean cancelled,
            boolean policyViolated,
            boolean limitExceeded,
            String defaultBranchSha,
            String verifiedBaseSha
    ) {
    }

    public record Gate(String name, boolean passed, String detail) {
    }

    public record Report(List<Gate> gates, boolean publishable, ReasonCode reasonCode) {
    }

    public Report evaluate(Input input) {
        List<Gate> gates = new ArrayList<>();
        gates.add(gate("baseline", input.baselineBuildPassed() && input.baselineTestsExecuted(),
                "Baseline build and test checks passed at the recorded commit."));
        gates.add(gate("diff", input.diffOnlyIntendedVersion(),
                "The proposed diff changes only the intended dependency version."));
        gates.add(gate("resolution", input.resolutionSucceeded(),
                "Candidate dependency resolution succeeds."));
        boolean tests = input.testsPassed() && input.testsExecuted() && !input.skipFlagsUsed();
        gates.add(gate("tests", tests,
                input.testsExecuted()
                        ? "Compilation and the configured tests passed without skip flags."
                        : "No executed tests were observed."));
        boolean cleared = input.rescanComplete() && !input.targetAdvisoryStillPresent();
        gates.add(gate("advisory-cleared", cleared,
                input.rescanComplete()
                        ? "The rescan no longer reports the targeted advisory for the affected component."
                        : "The candidate scan did not complete."));
        boolean noNew = input.rescanComplete() && !input.newAdvisoryPairs() && !input.feedChanged();
        gates.add(gate("no-new-advisories", noNew,
                input.feedChanged()
                        ? "The advisory feed changed between scans and the comparison is inconclusive."
                        : "The candidate scan adds no new advisory/component pairs."));
        boolean limits = !input.budgetExceeded() && !input.cancelled() && !input.policyViolated() && !input.limitExceeded();
        gates.add(gate("limits", limits, "No execution limit, cancellation, policy, or model budget was violated."));
        boolean sha = Objects.equals(input.defaultBranchSha(), input.verifiedBaseSha())
                && input.verifiedBaseSha() != null && !input.verifiedBaseSha().isBlank();
        gates.add(gate("base-sha", sha, "The default branch still points at the verified base commit."));
        boolean publishable = gates.stream().allMatch(Gate::passed);
        return new Report(List.copyOf(gates), publishable, publishable ? null : firstReason(input));
    }

    private ReasonCode firstReason(Input input) {
        if (!input.baselineBuildPassed() || !input.baselineTestsExecuted()) {
            return input.baselineTestsExecuted() || !input.baselineBuildPassed()
                    ? ReasonCode.BASELINE_FAILED
                    : ReasonCode.INSUFFICIENT_TEST_EVIDENCE;
        }
        if (!input.diffOnlyIntendedVersion()) {
            return ReasonCode.DIFF_REJECTED;
        }
        if (!input.resolutionSucceeded()) {
            return ReasonCode.CANDIDATE_RESOLUTION_FAILED;
        }
        if (!input.testsExecuted() || input.skipFlagsUsed()) {
            return ReasonCode.INSUFFICIENT_TEST_EVIDENCE;
        }
        if (!input.testsPassed()) {
            return ReasonCode.TESTS_FAILED;
        }
        if (!input.rescanComplete() || input.feedChanged()) {
            return ReasonCode.SCAN_INCONCLUSIVE;
        }
        if (input.targetAdvisoryStillPresent()) {
            return ReasonCode.TARGET_ADVISORY_PRESENT;
        }
        if (input.newAdvisoryPairs()) {
            return ReasonCode.NEW_ADVISORY_PAIR;
        }
        if (input.budgetExceeded()) {
            return ReasonCode.BUDGET_EXCEEDED;
        }
        if (input.limitExceeded()) {
            return ReasonCode.LIMIT_EXCEEDED;
        }
        if (input.cancelled()) {
            return null;
        }
        if (input.policyViolated()) {
            return ReasonCode.POLICY_VIOLATION;
        }
        if (!Objects.equals(input.defaultBranchSha(), input.verifiedBaseSha())) {
            return ReasonCode.STALE_BASE;
        }
        return ReasonCode.POLICY_VIOLATION;
    }

    private Gate gate(String name, boolean passed, String detail) {
        return new Gate(name, passed, detail);
    }
}
