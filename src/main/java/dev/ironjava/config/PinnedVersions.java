package dev.ironjava.config;

public final class PinnedVersions {
    public static final String APP = "1.0.0";
    public static final String SPRING_BOOT = "4.1.2";
    public static final String JDK = "21";
    public static final String MAVEN = "3.9.16";
    public static final String MAVEN_SHA512 =
            "831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6";
    public static final String JDK_BUILD = "21.0.12.1";
    public static final String OSV_SCANNER = "2.7.0";
    public static final String OSV_SCANNER_SHA256 =
            "160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b";
    public static final String POSTGRES = "17.6";
    public static final String POSTGRES_DIGEST =
            "sha256:00bc86618629af00d2937fdc5a5d63db3ff8450acf52f0636ec813c7f4902929";
    public static final String TEMURIN_JDK_DIGEST =
            "sha256:742daacb9316be4b6fc60258f6e4ac2a11882f6c597373c92290a57bfad0d2e0";
    public static final String TEMURIN_JRE_DIGEST =
            "sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0";

    private PinnedVersions() {
    }

    public static java.util.Map<String, String> asMap(String runnerImage) {
        java.util.Map<String, String> versions = new java.util.LinkedHashMap<>();
        versions.put("ironjava", APP);
        versions.put("springBoot", SPRING_BOOT);
        versions.put("jdk", JDK);
        versions.put("maven", MAVEN);
        versions.put("osvScanner", OSV_SCANNER);
        versions.put("postgres", POSTGRES);
        versions.put("runnerImage", runnerImage == null ? "" : runnerImage);
        versions.put("temurinJdkDigest", TEMURIN_JDK_DIGEST);
        versions.put("resolutionMode", dev.ironjava.verification.CommandProfiles.RESOLUTION_MODE);
        return versions;
    }
}
