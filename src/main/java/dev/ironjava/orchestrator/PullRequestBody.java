package dev.ironjava.orchestrator;

public final class PullRequestBody {
    private PullRequestBody() {
    }

    public static String render(String coordinate, String previous, String proposed, String advisoryLinks,
                                String qualification, String patchScope, String baseSha, String patchHash,
                                String toolVersions, String verifiedAt, String baseline, String candidate,
                                String testCounts, String scanSummary, String caveats, String testsNotRun,
                                String runId, String evidenceNote) {
        return """
                ## IronJava draft fix

                IronJava generated this change. A developer must review it before merge. A green verification report is not a claim that the service is vulnerability-free or that every compatibility risk is covered.

                ### Dependency
                - Coordinate: `%s`
                - Previous version: `%s`
                - Proposed version: `%s`
                - Advisories: %s

                ### Why this qualified
                %s

                ### Patch scope
                %s

                ### Verification
                - Base commit: `%s`
                - Patch hash: `%s`
                - Tool versions: %s
                - Verification timestamp (UTC): %s
                - Baseline: %s
                - Candidate: %s
                - Tests: %s
                - Scan: %s

                ### Caveats
                %s

                Tests not executed: %s

                ### Run
                - Run ID: `%s`
                - Evidence: %s
                """.formatted(coordinate, previous, proposed, advisoryLinks, qualification, patchScope, baseSha,
                patchHash, toolVersions, verifiedAt, baseline, candidate, testCounts, scanSummary, caveats,
                testsNotRun, runId, evidenceNote);
    }
}
