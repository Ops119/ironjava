package dev.ironjava.scan;

public record ScannerOutcome(boolean complete, String failure, OsvReportParser.Parsed parsed) {
    public static ScannerOutcome complete(OsvReportParser.Parsed parsed) {
        return new ScannerOutcome(!parsed.partial(), parsed.partial() ? "PARTIAL_RESULT" : null, parsed);
    }

    public static ScannerOutcome error(String failure) {
        return new ScannerOutcome(false, failure, null);
    }
}
