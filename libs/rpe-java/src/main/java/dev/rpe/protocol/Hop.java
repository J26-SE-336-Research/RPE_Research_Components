package dev.rpe.protocol;

/**
 * The {@code X-RPE-Hop} header. It only lives for one hop, gets rewritten each time and is removed before the app.
 *
 * @param callerToken backpressure caller token ({@code cb}), "" if missing
 * @param attempt     attempt number ({@code att}), 1 is the first attempt, 0 if missing
 */
public record Hop(String callerToken, long attempt) {
    public Hop {
        callerToken = callerToken == null ? "" : callerToken;
    }

    /** Parses a header value. Unknown members are ignored. */
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
