package com.getjobs.worker.boss.controlled;

import com.getjobs.worker.boss.delivery.ConfirmedTextSender.Outcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Server-frozen facts; never constructed from the execute request. No mutable BossConfig is used. */
public record ControlledTarget(String jobId, String hrId, String conversationId, String text,
                               String profileVersion, String assetVersion, String configVersion) {
    public ControlledTarget {
        for (String value : new String[]{jobId, hrId, conversationId, text, profileVersion, assetVersion, configVersion})
            if (value == null || value.isBlank()) throw new IllegalArgumentException("MISSING_FROZEN_FACT");
    }
    public String contentHash() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public enum Action { ENTRY, TEXT }
    /** No public constructor or request deserialization path. Issued only after the durable claim. */
    public static final class Permit {
        private final ControlledTarget target;
        private final Action action;
        private final AtomicBoolean used = new AtomicBoolean();
        private volatile boolean active = true;
        Permit(ControlledTarget target, Action action) { this.target = target; this.action = action; }
        public void consume(ControlledTarget observed, Action requested) {
            if (!active || !Objects.equals(target, observed) || action != requested || !used.compareAndSet(false, true))
                throw new IllegalStateException("CONTROLLED_PERMIT_REJECTED");
        }
        boolean consumed() { return used.get(); }
        void close() { active = false; }
    }
    public record Receipt(Outcome outcome, int observedOutbound, String evidence) {
        public Receipt { Objects.requireNonNull(outcome); Objects.requireNonNull(evidence); }
    }
}

