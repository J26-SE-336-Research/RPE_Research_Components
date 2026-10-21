package com.example.proxy;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Inbound leaky-bucket shaping and adaptive outbound concurrency/circuit breakers per sidecar Pod. */
final class BackpressureController {
    static final String SIGNAL_HEADER = "X-Proxy-Backpressure";
    static final String SIGNAL_VALUE = "slow-down";
    static final String QUEUE_UTILIZATION_HEADER = "X-Proxy-Queue-Utilization-Percent";
    static final String PEER_KEY_HEADER = "X-Proxy-Peer-Key";
    static final String CALLER_TOKEN_HEADER = "X-Proxy-Caller-Token";
    static final String SIGNAL_TOKEN_HEADER = "X-Backpressure-Caller-Token";
    static final String SIGNAL_UTILIZATION_HEADER = "X-Backpressure-Utilization-Percent";
    static final String SIGNAL_ACTIVE_HEADER = "X-Backpressure-Active";
    record OutboundPermit(boolean admitted, boolean halfOpenProbe) {}

    private static final int MAX_PEERS = 256;
    private static final int MAX_CALLER_TOKENS = MAX_PEERS;
    private static final long CALLER_TOKEN_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long RECENT_CALLER_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final int INITIAL_OUTBOUND_CONCURRENCY = 32;
    private static volatile BackpressureController INSTANCE;

    private volatile double inboundRate;
    private volatile int inboundQueueCapacity;
    private volatile boolean inboundPressureActive;
    /**
     * Backpressure mode, switched at runtime for the comparison experiment (user decision, 21 Oct 2026). "full" (the
     * default) is the complete mechanism. "basic" keeps only the inbound leaky bucket and the circuit breaker: no
     * pressure signals are sent or acted on, and outbound calls are not limited or queued per destination.
     */
    private volatile boolean adaptive = true;
    private final int failureThreshold;
    private final long breakerOpenMillis;
    private final int outboundQueueCapacity;
    private final String controlToken;
    private final int controlPort;
    private final int[] signalThresholdsPercent;
    private final int signalClearPercent;
    private final ConcurrentHashMap<CallerTarget, CallerRegistration> recentCallers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PeerToken> outboundSignalTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> outboundPeerTokens = new ConcurrentHashMap<>();
    private final HttpClient signalClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "proxy-leaky-bucket");
        thread.setDaemon(true);
        return thread;
    });
    private final LeakyQueue inboundQueue = new LeakyQueue();
    private final ConcurrentHashMap<String, PeerState> peers = new ConcurrentHashMap<>();
    private final AtomicLong inboundRejected = new AtomicLong();
    private final AtomicLong outboundRejected = new AtomicLong();
    private final AtomicLong breakerOpened = new AtomicLong();
    private final AtomicLong pressureSignalsReceived = new AtomicLong();
    private final AtomicLong pressureSignalsSent = new AtomicLong();
    private final AtomicLong pressureSignalsFailed = new AtomicLong();
    private final AtomicLong pressureSignalsRejected = new AtomicLong();
    private int inboundSignalLevel = -1;

    BackpressureController(ProxyConfig config) {
        inboundRate = config.inboundRequestsPerSecond();
        inboundQueueCapacity = config.inboundQueueCapacity();
        outboundQueueCapacity = config.outboundQueueCapacity();
        failureThreshold = config.circuitBreakerFailureThreshold();
        breakerOpenMillis = config.circuitBreakerOpenMillis();
        controlToken = config.backpressureControlToken();
        controlPort = config.metricsListenPort();
        signalThresholdsPercent = config.backpressureSignalThresholdsPercent().clone();
        signalClearPercent = config.backpressureSignalClearPercent();
    }

    static BackpressureController instance(ProxyConfig config) {
        BackpressureController current = INSTANCE;
        if (current != null) return current;
        synchronized (BackpressureController.class) {
            if (INSTANCE == null) INSTANCE = new BackpressureController(config);
            return INSTANCE;
        }
    }

    static BackpressureController current() { return INSTANCE; }

    static final String MODE_FULL = "full";
    static final String MODE_BASIC = "basic";

    String mode() { return adaptive ? MODE_FULL : MODE_BASIC; }

    /** Sets the backpressure mode; false (and no change) for an unknown mode. */
    boolean setMode(String mode) {
        if (MODE_FULL.equals(mode)) adaptive = true;
        else if (MODE_BASIC.equals(mode)) adaptive = false;
        else return false;
        return true;
    }
    boolean enqueueInbound(Runnable dispatch) {
        boolean queued = inboundQueue.submit(dispatch, () -> inboundRate, inboundQueueCapacity);
        updateInboundPressureState(inboundQueue.size());
        if (!queued) inboundRejected.incrementAndGet();
        return queued;
    }

    void noteInboundCaller(InetAddress callerAddress, String peer, String token) {
        if (callerAddress == null || !(callerAddress.isSiteLocalAddress() || callerAddress.isLoopbackAddress())
                || peer == null || !isValidPeerKey(peer) || !isValidCallbackToken(token)) return;
        String callerIp = callerAddress.getHostAddress();
        CallerTarget caller = new CallerTarget(callerIp, peer);
        if (recentCallers.size() >= 1024 && !recentCallers.containsKey(caller)) {
            pruneCallers();
            if (recentCallers.size() >= 1024) return;
        }
        long now = System.nanoTime();
        AtomicBoolean sendCurrentPressure = new AtomicBoolean();
        recentCallers.compute(caller, (key, previous) -> {
            long lastSignal = previous == null ? 0 : previous.lastSignalNanos();
            boolean shouldSend = inboundPressureActive && (previous == null
                    || now - lastSignal >= TimeUnit.SECONDS.toNanos(1));
            sendCurrentPressure.set(shouldSend);
            return new CallerRegistration(token, now, shouldSend ? now : lastSignal);
        });
        if (sendCurrentPressure.get()) {
            sendSignal(caller, token, inboundQueueUtilizationPercent(), true);
        }
    }

    synchronized String registerOutboundSignalToken(String peer) {
        if (!isValidPeerKey(peer) || state(peer) == null) return null;
        long now = System.nanoTime();
        long expiresAt = saturatingAdd(now, CALLER_TOKEN_TTL_NANOS);
        String existingToken = outboundPeerTokens.get(peer);
        if (existingToken != null) {
            PeerToken existing = outboundSignalTokens.get(existingToken);
            if (existing != null && existing.expiresAtNanos() > now) {
                outboundSignalTokens.put(existingToken, new PeerToken(peer, expiresAt));
                return existingToken;
            }
            if (existing != null) outboundSignalTokens.remove(existingToken, existing);
            outboundPeerTokens.remove(peer, existingToken);
        }
        pruneTokens();
        if (outboundSignalTokens.size() >= MAX_CALLER_TOKENS) return null;
        String token = java.util.UUID.randomUUID().toString();
        outboundSignalTokens.put(token, new PeerToken(peer, expiresAt));
        outboundPeerTokens.put(peer, token);
        return token;
    }

    boolean receivePressureSignal(String token, int utilizationPercent, boolean active) {
        if (!isValidCallbackToken(token) || utilizationPercent < 0 || utilizationPercent > 100) return false;
        PeerToken registration = outboundSignalTokens.get(token);
        if (registration == null || registration.expiresAtNanos() <= System.nanoTime()) {
            if (registration != null) {
                outboundSignalTokens.remove(token, registration);
                outboundPeerTokens.remove(registration.peer(), token);
            }
            return false;
        }
        // basic mode: a signal still in transit from before the switch is accepted but not acted on
        if (!adaptive) return true;
        String peer = registration.peer();
        PeerState state = state(peer);
        if (state == null) return false;
        synchronized (state) {
            pressureSignalsReceived.incrementAndGet();
            if (!active) {
                state.pressureActive = false;
                state.lastPressureUtilizationPercent = -1;
                return true;
            }
            int target = scaleConcurrency(state.healthyConcurrencyLimit, utilizationPercent);
            if (!state.pressureActive || utilizationPercent > state.lastPressureUtilizationPercent) {
                state.concurrencyLimit = Math.min(state.concurrencyLimit, target);
            } else if (utilizationPercent < state.lastPressureUtilizationPercent
                    && state.concurrencyLimit < target) {
                state.concurrencyLimit++;
            }
            state.pressureActive = true;
            state.lastPressureUtilizationPercent = utilizationPercent;
            return true;
        }
    }

    void applyInboundPressureSignal(io.netty.handler.codec.http.HttpHeaders headers) {
        if (inboundPressureActive && adaptive) {
            headers.set(SIGNAL_HEADER, SIGNAL_VALUE);
            headers.set(QUEUE_UTILIZATION_HEADER, Integer.toString(inboundQueueUtilizationPercent()));
        }
    }

    int inboundQueueUtilizationPercent() {
        int capacity = Math.max(1, inboundQueueCapacity);
        return (int) Math.min(100L, ((long) inboundQueue.size() * 100 + capacity - 1) / capacity);
    }

    static Integer parseQueueUtilizationPercent(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            int percent = Integer.parseInt(value.trim());
            return percent >= 0 && percent <= 100 ? percent : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** Check the outbound circuit breaker. Outbound calls are not rate shaped. */
    OutboundPermit admitOutbound(String peer) {
        PeerState state = state(peer);
        if (state == null) return new OutboundPermit(true, false);
        synchronized (state) {
            if (state.openUntilNanos > System.nanoTime()) {
                outboundRejected.incrementAndGet();
                return new OutboundPermit(false, false);
            }
            if (state.openUntilNanos != 0) {
                if (state.probeInFlight) {
                    outboundRejected.incrementAndGet();
                    return new OutboundPermit(false, false);
                }
                state.probeInFlight = true;
                return new OutboundPermit(true, true);
            }
            return new OutboundPermit(true, false);
        }
    }

    void cancelProbe(String peer) {
        if (peer == null) return;
        PeerState state = peers.get(peer);
        if (state == null) return;
        synchronized (state) { state.probeInFlight = false; }
    }

    /** Adaptive concurrency control slows calls to a peer after it signals overload. */
    boolean enqueueOutbound(String peer, Runnable dispatch, Runnable reject) {
        PeerState state = state(peer);
        if (state == null) {
            dispatch.run();
            return true;
        }
        boolean runNow = false;
        synchronized (state) {
            if (!adaptive) {
                // basic mode: no per-destination limit and no outbound queue; the breaker still applies (admitOutbound)
                state.inFlight++;
                runNow = true;
            } else if (state.inFlight < state.concurrencyLimit && state.waiting.isEmpty()) {
                state.inFlight++;
                runNow = true;
            } else if (state.waiting.size() < outboundQueueCapacity) {
                state.waiting.addLast(new OutboundTask(dispatch, reject));
            } else {
                outboundRejected.incrementAndGet();
                return false;
            }
        }
        if (runNow) dispatch.run();
        return true;
    }

    void releaseOutbound(String peer) {
        if (peer == null) return;
        PeerState state = peers.get(peer);
        if (state == null) return;
        List<OutboundTask> dispatches = new ArrayList<>();
        List<OutboundTask> rejections = new ArrayList<>();
        synchronized (state) {
            if (state.inFlight > 0) state.inFlight--;
            while (state.inFlight < state.concurrencyLimit && !state.waiting.isEmpty()) {
                OutboundTask candidate = state.waiting.removeFirst();
                if (state.openUntilNanos > System.nanoTime()) {
                    outboundRejected.incrementAndGet();
                    rejections.add(candidate);
                    continue;
                }
                state.inFlight++;
                dispatches.add(candidate);
            }
        }
        rejections.forEach(task -> task.reject().run());
        dispatches.forEach(task -> task.dispatch().run());
    }

    void recordResponse(String peer, int status, boolean pressureSignal,
                        Integer queueUtilizationPercent, boolean halfOpenProbe) {
        if (peer == null) return;
        PeerState state = state(peer);
        if (state == null) return;
        synchronized (state) {
            long now = System.nanoTime();
            if (!adaptive) {
                // basic mode: no adaptive concurrency; only the circuit breaker below reacts to the response
            } else if (pressureSignal || status == 429 || status == 503) {
                pressureSignalsReceived.incrementAndGet();
                int utilization = pressureSignal && queueUtilizationPercent != null
                        ? queueUtilizationPercent : 50;
                int pressureTarget = scaleConcurrency(state.healthyConcurrencyLimit, utilization);
                if (!state.pressureActive || utilization > state.lastPressureUtilizationPercent) {
                    state.concurrencyLimit = Math.min(state.concurrencyLimit, pressureTarget);
                } else if (utilization < state.lastPressureUtilizationPercent
                        && state.concurrencyLimit < pressureTarget) {
                    state.concurrencyLimit++;
                }
                state.pressureActive = true;
                state.lastPressureUtilizationPercent = utilization;
            } else if (status >= 500) {
                reduceConcurrency(state);
            } else if (status >= 200 && status < 400) {
                state.pressureActive = false;
                state.lastPressureUtilizationPercent = -1;
                if (state.concurrencyLimit < state.healthyConcurrencyLimit) {
                    state.concurrencyLimit++;
                } else if (!state.waiting.isEmpty()
                        && state.healthyConcurrencyLimit < Integer.MAX_VALUE) {
                    // Grow the healthy baseline only when real queued demand exists.
                    state.healthyConcurrencyLimit++;
                    state.concurrencyLimit++;
                }
            }
            if (status >= 500 || status == 429) {
                state.consecutiveFailures++;
                if (state.consecutiveFailures >= failureThreshold) open(state, now);
            } else if (halfOpenProbe && state.openUntilNanos != 0) {
                state.consecutiveFailures = 0;
                state.openUntilNanos = 0;
                breakerOpened.decrementAndGet();
            } else if (state.openUntilNanos == 0 && status < 500) {
                state.consecutiveFailures = 0;
            }
            if (halfOpenProbe) state.probeInFlight = false;
        }
    }

    void recordTransportFailure(String peer, boolean halfOpenProbe) {
        if (peer == null) return;
        PeerState state = state(peer);
        if (state == null) return;
        synchronized (state) {
            if (adaptive) reduceConcurrency(state);
            state.consecutiveFailures++;
            if (halfOpenProbe) state.probeInFlight = false;
            if (state.consecutiveFailures >= failureThreshold) open(state, System.nanoTime());
        }
    }

    private void open(PeerState state, long now) {
        if (state.openUntilNanos <= now) {
            if (state.openUntilNanos == 0) breakerOpened.incrementAndGet();
            state.openUntilNanos = saturatingAdd(now, millisToNanos(breakerOpenMillis));
        }
        state.probeInFlight = false;
    }

    private static int scaleConcurrency(int baseline, int utilizationPercent) {
        int clampedUtilization = Math.max(0, Math.min(100, utilizationPercent));
        int target = (int) Math.floor(baseline * ((100 - clampedUtilization) / 100.0));
        return Math.max(1, target);
    }

    private static void reduceConcurrency(PeerState state) {
        state.healthyConcurrencyLimit = Math.max(1, state.healthyConcurrencyLimit / 2);
        state.concurrencyLimit = Math.max(1, Math.min(
                state.concurrencyLimit / 2, state.healthyConcurrencyLimit));
        state.pressureActive = false;
        state.lastPressureUtilizationPercent = -1;
    }

    private PeerState state(String peer) {
        PeerState existing = peers.get(peer);
        if (existing != null) return existing;
        if (peers.size() >= MAX_PEERS) return null;
        PeerState created = new PeerState();
        PeerState raced = peers.putIfAbsent(peer, created);
        return raced == null ? created : raced;
    }

    String prometheusText() {
        return "# HELP rpe_backpressure_inbound_rate_per_second Current configured inbound release rate.\n"
                + "# TYPE rpe_backpressure_inbound_rate_per_second gauge\nrpe_backpressure_inbound_rate_per_second " + inboundRate + "\n"
                + "# HELP rpe_backpressure_inbound_queue_capacity Maximum waiting inbound requests.\n"
                + "# TYPE rpe_backpressure_inbound_queue_capacity gauge\nrpe_backpressure_inbound_queue_capacity " + inboundQueueCapacity + "\n"
                + "# HELP rpe_backpressure_inbound_queue_depth Requests waiting in the inbound leaky-bucket queue.\n"
                + "# TYPE rpe_backpressure_inbound_queue_depth gauge\nrpe_backpressure_inbound_queue_depth " + inboundQueue.size() + "\n"
                + "# HELP rpe_backpressure_inbound_queue_utilization_percent Current inbound waiting queue utilization from 0 to 100 percent.\n"
                + "# TYPE rpe_backpressure_inbound_queue_utilization_percent gauge\nrpe_backpressure_inbound_queue_utilization_percent " + inboundQueueUtilizationPercent() + "\n"
                + "# HELP rpe_backpressure_inbound_pressure_active Whether the inbound queue has crossed its high watermark and not yet drained to its low watermark.\n"
                + "# TYPE rpe_backpressure_inbound_pressure_active gauge\nrpe_backpressure_inbound_pressure_active " + (inboundPressureActive ? 1 : 0) + "\n"
                + "# HELP rpe_backpressure_inbound_rejected_total Inbound requests rejected because the leaky-bucket queue is full.\n"
                + "# TYPE rpe_backpressure_inbound_rejected_total counter\nrpe_backpressure_inbound_rejected_total " + inboundRejected.get() + "\n"
                + "# HELP rpe_backpressure_outbound_rejected_total Outbound calls rejected because a peer circuit is open.\n"
                + "# TYPE rpe_backpressure_outbound_rejected_total counter\nrpe_backpressure_outbound_rejected_total " + outboundRejected.get() + "\n"
                + "# HELP rpe_backpressure_peer_pressure_signals_total Downstream overload signals or overload responses received.\n"
                + "# TYPE rpe_backpressure_peer_pressure_signals_total counter\nrpe_backpressure_peer_pressure_signals_total " + pressureSignalsReceived.get() + "\n"
                + "# HELP rpe_backpressure_peer_pressure_notifications_sent_total Authenticated pressure notifications successfully delivered to caller sidecars.\n"
                + "# TYPE rpe_backpressure_peer_pressure_notifications_sent_total counter\nrpe_backpressure_peer_pressure_notifications_sent_total " + pressureSignalsSent.get() + "\n"
                + "# HELP rpe_backpressure_peer_pressure_notifications_failed_total Pressure notification delivery failures.\n"
                + "# TYPE rpe_backpressure_peer_pressure_notifications_failed_total counter\nrpe_backpressure_peer_pressure_notifications_failed_total " + pressureSignalsFailed.get() + "\n"
                + "# HELP rpe_backpressure_peer_pressure_notifications_rejected_total Invalid or unauthenticated pressure notifications.\n"
                + "# TYPE rpe_backpressure_peer_pressure_notifications_rejected_total counter\nrpe_backpressure_peer_pressure_notifications_rejected_total " + pressureSignalsRejected.get() + "\n"
                + "# HELP rpe_backpressure_outbound_in_flight Current outbound calls in flight across tracked peers.\n"
                + "# TYPE rpe_backpressure_outbound_in_flight gauge\nrpe_backpressure_outbound_in_flight " + outboundInFlight() + "\n"
                + "# HELP rpe_backpressure_outbound_queued Current outbound calls waiting for peer capacity.\n"
                + "# TYPE rpe_backpressure_outbound_queued gauge\nrpe_backpressure_outbound_queued " + outboundQueued() + "\n"
                + "# HELP rpe_circuit_breaker_open_peers Current number of peers with an open circuit breaker.\n"
                + "# TYPE rpe_circuit_breaker_open_peers gauge\nrpe_circuit_breaker_open_peers " + breakerOpened.get() + "\n"
                + "# HELP rpe_backpressure_adaptive_enabled Backpressure mode: 1 full (signals and adaptive concurrency), 0 basic (leaky bucket and breaker only).\n"
                + "# TYPE rpe_backpressure_adaptive_enabled gauge\nrpe_backpressure_adaptive_enabled " + (adaptive ? 1 : 0) + "\n"
                + peerMetricsText();
    }

    /** A copy of one destination's adaptive concurrency and breaker state, for the metrics only. */
    private record PeerSnapshot(String peer, int limit, int healthyLimit, int inFlight, int queued,
                                boolean pressureActive, int reportedUtilization, int breakerState) {}

    /**
     * Per-destination view of the adaptive concurrency and the circuit breaker. Read only: each peer's state is copied
     * under its lock and nothing is changed. Breaker state: 0 closed, 1 open, 2 half-open (open period over, the next
     * call is the probe).
     */
    private String peerMetricsText() {
        List<PeerSnapshot> snapshots = new ArrayList<>();
        long now = System.nanoTime();
        new java.util.TreeMap<>(peers).forEach((peer, state) -> {
            synchronized (state) {
                int breaker = state.openUntilNanos == 0 ? 0 : state.openUntilNanos > now ? 1 : 2;
                snapshots.add(new PeerSnapshot(peer, state.concurrencyLimit, state.healthyConcurrencyLimit, state.inFlight,
                        state.waiting.size(), state.pressureActive, state.lastPressureUtilizationPercent, breaker));
            }
        });
        StringBuilder out = new StringBuilder();
        peerGauge(out, snapshots, "rpe_backpressure_peer_concurrency_limit",
                "Current outbound concurrency limit per destination.", PeerSnapshot::limit);
        peerGauge(out, snapshots, "rpe_backpressure_peer_healthy_concurrency_limit",
                "Learned healthy outbound concurrency limit per destination.", PeerSnapshot::healthyLimit);
        peerGauge(out, snapshots, "rpe_backpressure_peer_in_flight",
                "Outbound calls in flight per destination.", PeerSnapshot::inFlight);
        peerGauge(out, snapshots, "rpe_backpressure_peer_queued",
                "Outbound calls waiting for capacity per destination.", PeerSnapshot::queued);
        peerGauge(out, snapshots, "rpe_backpressure_peer_pressure_active",
                "Whether the destination currently reports pressure (1) or not (0).", s -> s.pressureActive() ? 1 : 0);
        peerGauge(out, snapshots, "rpe_backpressure_peer_reported_utilization_percent",
                "Queue utilization last reported by the destination (-1 when none is active).", PeerSnapshot::reportedUtilization);
        peerGauge(out, snapshots, "rpe_circuit_breaker_peer_state",
                "Circuit breaker state per destination: 0 closed, 1 open, 2 half-open.", PeerSnapshot::breakerState);
        return out.toString();
    }

    private static void peerGauge(StringBuilder out, List<PeerSnapshot> snapshots, String name, String help,
                                  java.util.function.ToIntFunction<PeerSnapshot> value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(" gauge\n");
        // peer keys are validated host:port values ([A-Za-z0-9._:-]), so they need no label escaping
        for (PeerSnapshot s : snapshots) {
            out.append(name).append("{peer=\"").append(s.peer()).append("\"} ").append(value.applyAsInt(s)).append('\n');
        }
    }

    boolean updateRate(String token, double rate, int queueCapacity) {
        if (!authenticateControlToken(token)) {
            return false;
        }
        if (!Double.isFinite(rate) || rate <= 0 || rate > 1_000_000
                || queueCapacity <= 0 || queueCapacity > 100_000) {
            throw new IllegalArgumentException("rate must be greater than 0 and at most 1000000; queue_capacity must be 1-100000");
        }
        inboundRate = rate;
        inboundQueueCapacity = queueCapacity;
        updateInboundPressureState(inboundQueue.size());
        return true;
    }

    /**
     * Applies the release rate of an activated Component 3 policy ({@code policy.leakyBucket.ratePerSec}). It sets
     * the same rate as the authenticated runtime update ({@link #updateRate}) and nothing else: the waiting-queue
     * capacity is never changed by a policy, and how the bucket releases requests is unchanged. The policy path is
     * authenticated by the control API it arrived on instead of the backpressure token.
     *
     * @return false when the rate is out of range and nothing was changed
     */
    boolean applyPolicyRate(double rate) {
        if (!Double.isFinite(rate) || rate <= 0 || rate > 1_000_000) {
            return false;
        }
        inboundRate = rate;
        updateInboundPressureState(inboundQueue.size());
        return true;
    }

    boolean controlUpdatesEnabled() { return !controlToken.isBlank(); }

    boolean authenticateControlToken(String token) {
        return !controlToken.isBlank() && token != null
                && MessageDigest.isEqual(controlToken.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8));
    }

    private static long millisToNanos(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis); // saturates at Long.MAX_VALUE
    }

    private static long saturatingAdd(long left, long right) {
        return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private synchronized void updateInboundPressureState(int depth) {
        int capacity = inboundQueueCapacity;
        int utilization = (int) Math.min(100L, ((long) depth * 100 + capacity - 1) / capacity);
        if (!inboundPressureActive && utilization >= signalThresholdsPercent[0]) {
            inboundPressureActive = true;
            inboundSignalLevel = highestReachedLevel(utilization);
            notifyRecentCallers(utilization, true);
        } else if (inboundPressureActive && utilization <= signalClearPercent) {
            inboundPressureActive = false;
            inboundSignalLevel = -1;
            notifyRecentCallers(utilization, false);
        } else if (inboundPressureActive) {
            int newLevel = highestReachedLevel(utilization);
            if (newLevel != inboundSignalLevel) {
                inboundSignalLevel = newLevel;
                notifyRecentCallers(utilization, true);
            }
        }
    }

    private int highestReachedLevel(int utilization) {
        int reached = -1;
        for (int i = 0; i < signalThresholdsPercent.length; i++) {
            if (utilization >= signalThresholdsPercent[i]) reached = i;
            else break;
        }
        return reached;
    }

    private void notifyRecentCallers(int utilization, boolean active) {
        pruneCallers();
        recentCallers.forEach((caller, registration) -> {
            long now = System.nanoTime();
            recentCallers.replace(caller, registration,
                    new CallerRegistration(registration.token(), registration.lastSeenNanos(), now));
            sendSignal(caller, registration.token(), utilization, active);
        });
    }

    private void pruneCallers() {
        long oldest = System.nanoTime() - RECENT_CALLER_TTL_NANOS;
        recentCallers.forEach((caller, registration) -> {
            if (registration.lastSeenNanos() < oldest) recentCallers.remove(caller, registration);
        });
    }

    private void pruneTokens() {
        long now = System.nanoTime();
        outboundSignalTokens.forEach((token, registration) -> {
            if (registration.expiresAtNanos() <= now && outboundSignalTokens.remove(token, registration)) {
                outboundPeerTokens.remove(registration.peer(), token);
            }
        });
    }

    private void sendSignal(CallerTarget caller, String token, int utilization, boolean active) {
        if (!adaptive) return; // basic mode: no pressure signals

        String address = caller.ip().replace("%", "%25");
        String host = address.contains(":") ? "[" + address + "]" : address;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://" + host + ":" + controlPort + "/backpressure/signal"))
                    .timeout(Duration.ofSeconds(1))
                    .header(SIGNAL_TOKEN_HEADER, token)
                    .header(SIGNAL_UTILIZATION_HEADER, Integer.toString(utilization))
                    .header(SIGNAL_ACTIVE_HEADER, Boolean.toString(active))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            signalClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, failure) -> {
                        if (failure == null && response.statusCode() >= 200 && response.statusCode() < 300) {
                            pressureSignalsSent.incrementAndGet();
                        } else {
                            pressureSignalsFailed.incrementAndGet();
                        }
                    });
        } catch (IllegalArgumentException malformedAddress) {
            pressureSignalsFailed.incrementAndGet();
        }
    }

    static boolean isValidPeerKey(String peer) {
        if (peer == null || peer.length() > 300 || !peer.matches("[A-Za-z0-9._:-]+")) return false;
        int separator = peer.lastIndexOf(':');
        if (separator <= 0 || separator == peer.length() - 1) return false;
        for (int i = separator + 1; i < peer.length(); i++) {
            if (!Character.isDigit(peer.charAt(i))) return false;
        }
        try {
            int port = Integer.parseInt(peer.substring(separator + 1));
            return port > 0 && port <= 65535;
        } catch (NumberFormatException invalidPort) {
            return false;
        }
    }

    static boolean isValidCallbackToken(String token) {
        return token != null && token.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    void recordRejectedSignal() { pressureSignalsRejected.incrementAndGet(); }

    private record CallerTarget(String ip, String peer) {}
    private record CallerRegistration(String token, long lastSeenNanos, long lastSignalNanos) {}
    private record PeerToken(String peer, long expiresAtNanos) {}

    private final class PeerState {
        final ArrayDeque<OutboundTask> waiting = new ArrayDeque<>();
        int concurrencyLimit = INITIAL_OUTBOUND_CONCURRENCY;
        int healthyConcurrencyLimit = INITIAL_OUTBOUND_CONCURRENCY;
        int lastPressureUtilizationPercent = -1;
        boolean pressureActive;
        int inFlight;
        int consecutiveFailures;
        long openUntilNanos;
        boolean probeInFlight;
    }

    private record OutboundTask(Runnable dispatch, Runnable reject) {}

    private long outboundInFlight() {
        return peers.values().stream().mapToLong(state -> {
            synchronized (state) { return state.inFlight; }
        }).sum();
    }

    private long outboundQueued() {
        return peers.values().stream().mapToLong(state -> {
            synchronized (state) { return state.waiting.size(); }
        }).sum();
    }

    /** A FIFO whose head is released at fixed intervals: classic leaky-bucket shaping. */
    private final class LeakyQueue {
        private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
        private boolean draining;
        private long nextReleaseNanos;

        synchronized boolean submit(Runnable dispatch, DoubleSupplier rate, int capacity) {
            if (waiting.size() >= capacity) return false;
            waiting.addLast(dispatch);
            if (!draining) {
                draining = true;
                scheduleNext(rate);
            }
            return true;
        }

        synchronized int size() { return waiting.size(); }

        private void scheduleNext(DoubleSupplier rate) {
            double currentRate = rate.getAsDouble();
            long interval = Math.max(1L, (long) (1_000_000_000.0 / currentRate));
            long delay = Math.max(0, nextReleaseNanos - System.nanoTime());
            scheduler.schedule(() -> releaseNext(rate, interval), delay, TimeUnit.NANOSECONDS);
        }

        private void releaseNext(DoubleSupplier rate, long interval) {
            Runnable next;
            synchronized (this) {
                next = waiting.pollFirst();
                updateInboundPressureState(waiting.size());
                if (next == null) {
                    draining = false;
                    return;
                }
                long now = System.nanoTime();
                nextReleaseNanos = saturatingAdd(now, interval);
                if (waiting.isEmpty()) draining = false;
                else scheduleNext(rate);
            }
            try {
                next.run();
            } catch (RuntimeException dispatchFailure) {
                // Keep the shared scheduler alive; the request callback owns its own cleanup.
            }
        }
    }
}
