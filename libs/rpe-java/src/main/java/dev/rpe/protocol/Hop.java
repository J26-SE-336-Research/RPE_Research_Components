package dev.rpe.protocol;

/**
 * The hop-scoped {@code X-RPE-Hop} header: rewritten at every hop and stripped before the application.
 *
 * @param callerToken backpressure caller token ({@code cb}), "" when absent
 * @param attempt     attempt number ({@code att}), 1 = first attempt, 0 when absent
 */
public record Hop(String callerToken, long attempt) {
    public Hop {
        callerToken = callerToken == null ? "" : callerToken;
    }

    /** Parses a serialized value; unknown members are ignored. */
    public static Hop decode(String raw) {
        String cb = "";
        long att = 0;
        for (Sfv.Member m : Sfv.parseDictionary(RpeHeaders.HOP, raw)) {
            switch (m.key()) {
                case "cb" -> cb = Sfv.wantString(m, 1, 64);
                case "att" -> att = Sfv.wantInt(m, 1, 255);
                default -> { }
            }
        }
        return new Hop(cb, att);
    }

    public String encode() {
        Sfv.Writer w = new Sfv.Writer();
        if (!callerToken.isEmpty()) w.string("cb", callerToken);
        if (attempt > 0) w.integer("att", attempt);
        return w.toString();
    }
}
