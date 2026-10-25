package dev.ironjava.scan;

public final class ScanOutcomeInterpreter {
    private final OsvReportParser parser = new OsvReportParser();

    public ScannerOutcome interpret(int exitCode, String stdout, String stderr) {
        if (exitCode != 0 && exitCode != 1) {
            return ScannerOutcome.error("SCANNER_EXIT_" + exitCode);
        }
        if (stdout == null || stdout.isBlank()) {
            return ScannerOutcome.error(stderr == null || stderr.isBlank() ? "EMPTY_OUTPUT" : "SCANNER_ERROR");
        }
        try {
            OsvReportParser.Parsed parsed = parser.parse(stdout);
            int findings = parsed.advisories().size();
            if (exitCode == 1 && findings == 0) {
                return ScannerOutcome.error("EXIT_1_WITHOUT_FINDINGS");
            }
            if (parsed.partial()) {
                return ScannerOutcome.error("PARTIAL_RESULT");
            }
            return ScannerOutcome.complete(parsed);
        } catch (OsvReportParser.ScannerParseException exception) {
            return ScannerOutcome.error(exception.getMessage());
        }
    }
}
