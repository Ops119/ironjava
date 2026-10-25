package dev.ironjava.patch;

public final class PatchRejectedException extends RuntimeException {
    private final String code;

    public PatchRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
