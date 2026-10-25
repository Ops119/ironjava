package dev.ironjava.domain;

import dev.ironjava.domain.Domain.ReasonCode;
import dev.ironjava.domain.Domain.RunKind;
import dev.ironjava.domain.Domain.RunState;
import dev.ironjava.domain.Domain.Transitions;
import dev.ironjava.scan.OsvReportParser;
import dev.ironjava.scan.ScanOutcomeInterpreter;
import dev.ironjava.verification.CommandProfiles;
import dev.ironjava.verification.CommandRunner;
import dev.ironjava.verification.PublicationGateEvaluator;
import dev.ironjava.config.IronJavaProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionAndGateTest {
    private final PublicationGateEvaluator gates = new PublicationGateEvaluator();
    private final ScanOutcomeInterpreter interpreter = new ScanOutcomeInterpreter();

    @Test
    void interruptedRunsCannotResumePublishing() {
        assertTrue(Transitions.allowed(RunKind.REMEDIATION, RunState.VERIFYING, RunState.PUBLISHING));
        assertTrue(Transitions.allowed(RunKind.REMEDIATION, RunState.PUBLISHING, RunState.INTERRUPTED));
        assertFalse(Transitions.allowed(RunKind.REMEDIATION, RunState.INTERRUPTED, RunState.PUBLISHING));
        assertFalse(Transitions.allowed(RunKind.REMEDIATION, RunState.INTERRUPTED, RunState.PR_OPENED));
        assertFalse(Transitions.allowed(RunKind.SCAN, RunState.COMPLETED, RunState.SCANNING));
        assertThrows(Domain.IllegalTransitionException.class,
                () -> Transitions.require(RunKind.REMEDIATION, RunState.PR_OPENED, RunState.FAILED));
    }

    @Test
    void eachPublicationGateHasItsOwnReason() {
        assertEquals(ReasonCode.BASELINE_FAILED, gates.evaluate(input(false, true, true, true, true, true, false, true, false, false, false, false, false, false, false, "a", "a")).reasonCode());
        assertEquals(ReasonCode.DIFF_REJECTED, gates.evaluate(input(true, true, false, true, true, true, false, true, false, false, false, false, false, false, false, "a", "a")).reasonCode());
        assertEquals(ReasonCode.CANDIDATE_RESOLUTION_FAILED, gates.evaluate(input(true, true, true, false, true, true, false, true, false, false, false, false, false, false, false, "a", "a")).reasonCode());
        assertEquals(ReasonCode.INSUFFICIENT_TEST_EVIDENCE, gates.evaluate(input(true, true, true, true, true, false, false, true, false, false, false, false, false, false, false, "a", "a")).reasonCode());
        assertEquals(ReasonCode.TESTS_FAILED, gates.evaluate(input(true, true, true, true, false, true, false, true, false, false, false, false, false, false, false, "a", "a")).reasonCode());
        assertEquals(ReasonCode.SCAN_INCONCLUSIVE, gates.evaluate(input(true, true, true, true, true, true, false, false, false, false, false, false, false, false, false, "a", "a")).reasonCode());
        assertEquals(ReasonCode.SCAN_INCONCLUSIVE, gates.evaluate(input(true, true, true, true, true, true, false, true, false, false, true, false, false, false, false, "a", "a")).reasonCode());
        assertEquals(ReasonCode.TARGET_ADVISORY_PRESENT, gates.evaluate(input(true, true, true, true, true, true, false, true, true, false, false, false, false, false, false, "a", "a")).reasonCode());
        assertEquals(ReasonCode.NEW_ADVISORY_PAIR, gates.evaluate(input(true, true, true, true, true, true, false, true, false, true, false, false, false, false, false, "a", "a")).reasonCode());
        assertEquals(ReasonCode.STALE_BASE, gates.evaluate(input(true, true, true, true, true, true, false, true, false, false, false, false, false, false, false, "moved", "a")).reasonCode());
        assertNull(gates.evaluate(passing()).reasonCode());
        assertTrue(gates.evaluate(passing()).publishable());
    }

    @Test
    void scannerErrorsStayDistinctFromFindings() {
        assertFalse(interpreter.interpret(127, "", "not found").complete());
        assertEquals("SCANNER_EXIT_127", interpreter.interpret(127, "", "not found").failure());
        assertEquals("EMPTY_OUTPUT", interpreter.interpret(1, "  ", "").failure());
        assertEquals("EXIT_1_WITHOUT_FINDINGS", interpreter.interpret(1, "{\"results\":[]}", "").failure());
        assertEquals("MALFORMED_JSON", interpreter.interpret(0, "not-json", "").failure());
        var found = interpreter.interpret(1, """
                {"results":[{"source":{"type":"lockfile","path":"pom.xml"},"packages":[{"package":{"name":"org.apache.commons:commons-text","version":"1.9","ecosystem":"Maven"},"groups":[{"ids":["GHSA-599f-7c49-w659"],"aliases":["CVE-2022-42889"],"max_severity":"9.8"}]}]}]}
                """, "");
        assertTrue(found.complete());
        assertEquals(1, found.parsed().advisories().size());
        assertEquals("GHSA-599f-7c49-w659", found.parsed().advisories().get(0).primaryId());
        var clean = interpreter.interpret(0, "{\"results\":[]}", "");
        assertTrue(clean.complete());
        assertTrue(clean.parsed().advisories().isEmpty());
    }

    @Test
    void commandProfilesAreFixedArgvWithoutSkipFlags() {
        var verify = CommandProfiles.verify("mvn", "/tmp/settings.xml", "/tmp/.m2");
        assertEquals(List.of("mvn", "-B", "-ntp", "-s", "/tmp/settings.xml", "-Dmaven.repo.local=/tmp/.m2", "verify"), verify);
        var scan = CommandProfiles.scan("osv-scanner", "/workspace");
        assertEquals(List.of("osv-scanner", "scan", "source", "--recursive", "--format", "json", "--verbosity", "error",
                "--x-exclude", ".m2", "--x-exclude", "target", "/workspace"), scan);
        assertThrows(IllegalArgumentException.class, () -> CommandProfiles.rejectSkipFlags(List.of("mvn", "-DskipTests")));
        IronJavaProperties properties = new IronJavaProperties();
        properties.getRunner().setDockerBinary("docker");
        properties.getRunner().setNetwork("ironjava_build");
        properties.getRunner().setImage("ironjava-runner@sha256:abc");
        var args = CommandRunner.containerArgs(properties, Path.of("/tmp/ws"), List.of("mvn", "-B", "verify"));
        assertTrue(args.contains("--cap-drop"));
        assertTrue(args.contains("ALL"));
        assertTrue(args.contains("65532:65532"));
        assertTrue(args.contains("no-new-privileges:true"));
        assertTrue(args.contains("--read-only"));
        assertFalse(String.join(" ", args).contains("sh -c"));
        assertEquals("mvn", args.get(args.indexOf("--entrypoint") + 1));
    }

    private PublicationGateEvaluator.Input passing() {
        return input(true, true, true, true, true, true, false, true, false, false, false, false, false, false, false, "a", "a");
    }

    private PublicationGateEvaluator.Input input(boolean baseline, boolean baselineTests, boolean diff, boolean resolution,
                                                  boolean testsPassed, boolean testsExecuted, boolean skip, boolean rescan,
                                                  boolean target, boolean newPairs, boolean feed, boolean budget, boolean cancelled,
                                                  boolean policy, boolean limit, String head, String base) {
        return new PublicationGateEvaluator.Input(baseline, baselineTests, diff, resolution, testsPassed, testsExecuted,
                skip, rescan, target, newPairs, feed, budget, cancelled, policy, limit, head, base);
    }
}
