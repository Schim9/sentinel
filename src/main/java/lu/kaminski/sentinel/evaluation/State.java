package lu.kaminski.sentinel.evaluation;

public enum State {
    OK, WARN, CRIT;

    public boolean isWorseThan(State other) {
        return this.ordinal() > other.ordinal();
    }

    public boolean isBetterThan(State other) {
        return this.ordinal() < other.ordinal();
    }

    public State worst(State other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }

    public static State fromString(String s) {
        if (s == null) return OK;
        return switch (s.toUpperCase()) {
            case "WARN" -> WARN;
            case "CRIT" -> CRIT;
            default     -> OK;
        };
    }
}
