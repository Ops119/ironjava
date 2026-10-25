package dev.ironjava.policy;

import dev.ironjava.domain.Domain.Eligibility;
import dev.ironjava.domain.Domain.VersionSource;
import dev.ironjava.patch.PatchRejectedException;
import dev.ironjava.patch.PomPatchService;
import dev.ironjava.scan.OsvReportParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyAndPatchTest {
    private final EligibilityPolicy policy = new EligibilityPolicy();
    private final PomPatchService patcher = new PomPatchService();

    @Test
    void mavenOrdersOneNineBeforeOneTen() {
        assertTrue(MavenVersions.compare("1.9", "1.10.0") < 0);
        assertTrue("1.9".compareTo("1.10.0") > 0);
        assertTrue(MavenVersions.sameMajor("1.9", "1.10.0"));
        assertTrue(!MavenVersions.sameMajor("1.33", "2.0"));
    }

    @Test
    void commonsTextIsEligibleAndTheOtherFixtureCasesAreManualReview() {
        var ranges = List.of(new AffectedVersionMatcher.VersionRange("ECOSYSTEM", List.of(
                new AffectedVersionMatcher.RangeEvent("1.5", null, null, null),
                new AffectedVersionMatcher.RangeEvent(null, "1.10.0", null, null))));
        var advisory = new EligibilityPolicy.AdvisoryFact(
                "GHSA-599f-7c49-w659",
                List.of("GHSA-599f-7c49-w659", "CVE-2022-42889"),
                List.of("CVE-2022-42889"),
                "9.8",
                ranges,
                List.of(),
                "Text4Shell");
        var text = fact("org.apache.commons", "commons-text", "1.9", "compile", true, true, VersionSource.EXPLICIT, 1);
        var decision = policy.decide(text, advisory, true, true, List.of("1.10.0", "1.10.1", "1.11.0", "1.12.0", "1.15.0"));
        assertEquals(Eligibility.ELIGIBLE, decision.eligibility());
        assertEquals(List.of("1.10.0", "1.10.1", "1.11.0"), decision.candidates());

        var snakeRanges = List.of(new AffectedVersionMatcher.VersionRange("ECOSYSTEM", List.of(
                new AffectedVersionMatcher.RangeEvent("1.0", null, null, null),
                new AffectedVersionMatcher.RangeEvent(null, "2.0", null, null))));
        var snakeAdvisory = new EligibilityPolicy.AdvisoryFact(
                "GHSA-mjmj-j48q-9wg2", List.of("GHSA-mjmj-j48q-9wg2"), List.of("CVE-2022-1471"),
                "8.3", snakeRanges, List.of(), "SnakeYAML");
        var snake = fact("org.yaml", "snakeyaml", "1.33", "compile", true, true, VersionSource.EXPLICIT, 1);
        var snakeDecision = policy.decide(snake, snakeAdvisory, true, true, List.of("1.33", "2.0"));
        assertEquals(Eligibility.MANUAL_REVIEW, snakeDecision.eligibility());
        assertTrue(snakeDecision.reasons().contains("MAJOR_UPGRADE_REQUIRED"));

        var guava = fact("com.google.guava", "guava", "31.1-jre", "compile", true, true, VersionSource.MANAGED, 1);
        var guavaDecision = policy.decide(guava, advisory, true, true, List.of("32.0.0-jre"));
        assertEquals(Eligibility.MANUAL_REVIEW, guavaDecision.eligibility());
        assertTrue(guavaDecision.reasons().contains("BOM_OR_PARENT_MANAGED"));

        var gson = fact("com.google.code.gson", "gson", "2.8.6", "test", true, true, VersionSource.EXPLICIT, 1);
        var gsonDecision = policy.decide(gson, advisory, true, true, List.of("2.8.9"));
        assertEquals(Eligibility.MANUAL_REVIEW, gsonDecision.eligibility());
        assertTrue(gsonDecision.reasons().contains("NOT_PRODUCTION_SCOPE"));
    }

    @Test
    void normalizerTreatsTestGroupsAndUnknownBomAsManualInputs() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency><groupId>com.google.code.gson</groupId><artifactId>gson</artifactId><version>2.8.6</version><scope>test</scope></dependency>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId></dependency>
                    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-text</artifactId><version>1.9</version></dependency>
                  </dependencies>
                </project>
                """;
        var direct = new PomInspector().directDependencies(pom);
        var gson = advisory("com.google.code.gson:gson", "2.8.6", List.of("test"), "lockfile");
        assertEquals("test", FindingNormalizer.toFact(direct, gson, false).scope());
        var guava = advisory("com.google.guava:guava", "31.1-jre", List.of(), "unknown");
        assertEquals(VersionSource.MANAGED, FindingNormalizer.toFact(direct, guava, false).versionSource());
        var text = advisory("org.apache.commons:commons-text", "1.9", List.of(), "lockfile");
        assertEquals(VersionSource.EXPLICIT, FindingNormalizer.toFact(direct, text, false).versionSource());
    }

    @Test
    void patchRewritesOnlyTheApprovedVersionBytes() {
        String pom = """
                <project>
                  <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1.9</version></parent>
                  <groupId>dev.ironjava</groupId><artifactId>sample</artifactId><version>1.9</version>
                  <properties><commons.version>1.9</commons.version></properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>org.apache.commons</groupId><artifactId>commons-text</artifactId><version>1.9</version></dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.commons</groupId>
                      <artifactId>commons-text</artifactId>
                      <version>1.9</version>
                    </dependency>
                  </dependencies>
                  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>1.9</version></plugin></plugins></build>
                </project>
                """;
        PomPatchService.Patch patch = patcher.apply(pom, "org.apache.commons", "commons-text", "1.10.0");
        assertEquals("1.9", patch.previousVersion());
        assertEquals(1, patch.pom().split("<version>1.10.0</version>", -1).length - 1);
        assertTrue(patch.pom().contains("<parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1.9</version></parent>"));
        assertTrue(patch.pom().contains("<commons.version>1.9</commons.version>"));
        assertTrue(patch.pom().contains("<artifactId>maven-compiler-plugin</artifactId><version>1.9</version>"));
        String management = patch.pom().substring(patch.pom().indexOf("<dependencyManagement>"), patch.pom().indexOf("</dependencyManagement>"));
        assertTrue(management.contains("<version>1.9</version>"));
        assertThrows(PatchRejectedException.class, () -> patcher.apply(pom, "org.apache.commons", "commons-text", "1.9"));
        assertThrows(PatchRejectedException.class, () -> patcher.apply("<!DOCTYPE project [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><project/>", "a", "b", "1"));
        int rootDependencies = pom.lastIndexOf("</dependencies>");
        String doubled = pom.substring(0, rootDependencies)
                + "<dependency><groupId>org.apache.commons</groupId><artifactId>commons-text</artifactId><version>1.9</version></dependency>"
                + pom.substring(rootDependencies);
        assertThrows(PatchRejectedException.class, () -> patcher.apply(doubled, "org.apache.commons", "commons-text", "1.10.0"));
    }

    private static EligibilityPolicy.DependencyFact fact(String group, String artifact, String version, String scope,
                                                          boolean direct, boolean root, VersionSource source, int matches) {
        return new EligibilityPolicy.DependencyFact(group, artifact, version, scope, direct, root, source, false, matches);
    }

    private static OsvReportParser.Advisory advisory(String coordinate, String version, List<String> groups, String sourceType) {
        return new OsvReportParser.Advisory(coordinate, version, "GHSA-test", List.of("GHSA-test"), List.of(),
                "Unknown", List.of(), List.of(), "", sourceType, groups);
    }
}
