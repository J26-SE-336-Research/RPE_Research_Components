package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Append-only audit of recommendations, human decisions and deliveries (NFR auditability). Persisted to the
 * PostgreSQL schema "intel" (migration 003, append-only) when RPE_AUDIT_JDBC_URL is set; otherwise kept in memory
 * (local runs and tests). Synthetic (MOCK) entries are stored too and are distinguishable by their source.
 */
@Component
public class AuditLog {
    public record Entry(Instant at, String kind, String proposalId, String target, String action, String source,
            double currentValue, double proposedValue, String unit, String decision, String actor, String delivery, String detail, String policyVersion) {}

    static final AuditLog NONE = new AuditLog("", "", "");
    private static final Logger LOG = Logger.getLogger(AuditLog.class.getName());
    private final String url, user, password;
    private final Deque<Entry> recent = new ArrayDeque<>();
    private boolean schemaReady;

    public AuditLog(@Value("${rpe.audit.jdbc-url:}") String url, @Value("${rpe.audit.user:}") String user,
            @Value("${rpe.audit.password:}") String password) {
        this.url = url; this.user = user; this.password = password;
    }

    public void proposed(PolicyChange c) { record("proposed", c, null); }
    public void decided(PolicyChange c) { record(c.decision() == Decision.APPROVED ? "approved" : "rejected", c, c.reviewer()); }
    public void delivery(PolicyChange c) { record("delivery-" + c.delivery().name().toLowerCase(), c, "component-3"); }

    /** Deadline v2 event kinds (additive; the table and its columns are unchanged). */
    public static final String DEADLINE_DECISION = "DEADLINE_DECISION", DEADLINE_STATE_CHANGED = "DEADLINE_STATE_CHANGED",
            DEADLINE_VERIFICATION = "DEADLINE_VERIFICATION", DEADLINE_CUT_DETECTED = "DEADLINE_CUT_DETECTED",
            DEADLINE_HEALTHY_RELEARNED = "DEADLINE_HEALTHY_RELEARNED";
    private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();

    /**
     * A deadline v2 event for a chain. {@code proposal_id} is NOT NULL in {@code intel.audit}, so chain events use a stable
     * per-chain UUID; the payload ({@code DeadlineEvidence} or a short text) goes to {@code detail} as JSON.
     */
    public void deadline(String kind, String chain, Source source, double currentMs, Double proposedMs, String decision, Object payload) {
        if (this == NONE) return;
        String detail;
        try { detail = payload instanceof String s ? s : json.writeValueAsString(payload); }
        catch (RuntimeException e) { detail = String.valueOf(payload); }
        record(new Entry(Instant.now(), kind, chainId(chain, source).toString(), chain, Action.CHAIN_DEADLINE.name(), source.name(), currentMs,
                proposedMs == null ? currentMs : proposedMs, "milliseconds", decision, "c4-deadline-v2", null, detail, null));
    }
    /** Rate/retry v2 automatic-approval event kinds (additive; the table and its columns are unchanged). */
    public static final String RATE_AUTO_APPROVED = "RATE_AUTO_APPROVED", RATE_AUTO_REVERTED = "RATE_AUTO_REVERTED", RATE_AUTO_PAUSED = "RATE_AUTO_PAUSED";

    /** An automatic rate or retry change (approved or reverted); the diagnosis, inputs and check result go to {@code detail} as JSON. */
    public void rate(String kind, PolicyChange c, Object payload) {
        if (this == NONE) return;
        var p = c.proposal();
        record(new Entry(Instant.now(), kind, p.id().toString(), p.target(), p.action().name(), p.source().name(), p.currentValue(),
                p.proposedValue(), p.unit(), c.decision().name(), c.reviewer(), c.delivery().name(), detail(payload), c.appliedVersion()));
    }
    /** Automatic approval paused for a service; uses a stable per-service UUID like chain events. */
    public void ratePaused(String service, Source source, double rate, Object payload) {
        if (this == NONE) return;
        var id = java.util.UUID.nameUUIDFromBytes(("rate:" + source + ":" + service).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        record(new Entry(Instant.now(), RATE_AUTO_PAUSED, id.toString(), service, Action.LEAKY_BUCKET.name(), source.name(), rate, rate,
                "requests/second", "PAUSED", "c4-auto", null, detail(payload), null));
    }
    private String detail(Object payload) {
        try { return payload instanceof String s ? s : json.writeValueAsString(payload); }
        catch (RuntimeException e) { return String.valueOf(payload); }
    }
    public static java.util.UUID chainId(String chain, Source source) {
        return java.util.UUID.nameUUIDFromBytes(("deadline:" + source + ":" + chain).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private synchronized void record(String kind, PolicyChange c, String actor) {
        if (this == NONE) return;
        var p = c.proposal();
        record(new Entry(Instant.now(), kind, p.id().toString(), p.target(), p.action().name(), p.source().name(), p.currentValue(),
                p.proposedValue(), p.unit(), c.decision().name(), actor, c.delivery().name(), c.detail(), c.appliedVersion()));
    }
    private synchronized void record(Entry e) {
        if (this == NONE) return;
        recent.addFirst(e);
        while (recent.size() > 500) recent.removeLast();
        if (url.isBlank()) return;
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Connection db = DriverManager.getConnection(url, user, password)) {
                ensureSchema(db);
                insert(db, e);
                return;
            } catch (SQLException ex) {
                schemaReady = false; // the database may have been recreated: re-check the schema once
                if (attempt == 1) LOG.log(Level.WARNING, "audit write failed (kept in memory): " + ex.getMessage());
            }
        }
    }

    private static void insert(Connection db, Entry e) throws SQLException {
        try (var st = db.prepareStatement("insert into intel.audit(at, kind, proposal_id, target, action, source, current_value, proposed_value, unit,"
                + " decision, actor, delivery, detail, policy_version) values (?,?,?::uuid,?,?,?,?,?,?,?,?,?,?,?)")) {
            st.setTimestamp(1, Timestamp.from(e.at()));
            st.setString(2, e.kind()); st.setString(3, e.proposalId()); st.setString(4, e.target()); st.setString(5, e.action());
            st.setString(6, e.source()); st.setDouble(7, e.currentValue()); st.setDouble(8, e.proposedValue()); st.setString(9, e.unit());
            st.setString(10, e.decision()); st.setString(11, e.actor()); st.setString(12, e.delivery()); st.setString(13, e.detail());
            st.setString(14, e.policyVersion());
            st.executeUpdate();
        }
    }

    private void ensureSchema(Connection db) throws SQLException {
        if (schemaReady) return;
        try (var st = db.createStatement()) {
            st.execute("create schema if not exists intel");
            st.execute("create table if not exists intel.audit (id bigserial primary key, at timestamptz not null, kind text not null,"
                    + " proposal_id uuid not null, target text not null, action text not null, source text not null, current_value double precision,"
                    + " proposed_value double precision, unit text, decision text, actor text, delivery text, detail text, policy_version text)");
        }
        schemaReady = true;
    }

    /** Most recent audit entries (persisted rows when the database is configured). */
    public synchronized List<Entry> recent(int limit) {
        if (!url.isBlank()) {
            try (Connection db = DriverManager.getConnection(url, user, password)) {
                ensureSchema(db);
                var out = new ArrayList<Entry>();
                try (var st = db.prepareStatement("select at, kind, proposal_id::text, target, action, source, current_value, proposed_value, unit,"
                        + " decision, actor, delivery, detail, policy_version from intel.audit order by id desc limit ?")) {
                    st.setInt(1, limit);
                    var rs = st.executeQuery();
                    while (rs.next()) out.add(new Entry(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getDouble(7), rs.getDouble(8), rs.getString(9), rs.getString(10),
                            rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14)));
                }
                return out;
            } catch (SQLException ex) {
                schemaReady = false;
                LOG.log(Level.WARNING, "audit read failed: " + ex.getMessage());
            }
        }
        return recent.stream().limit(limit).toList();
    }

    public boolean persistent() { return !url.isBlank(); }
}
