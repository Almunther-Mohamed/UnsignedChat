package me.almunther.unsignedchat;

import java.util.concurrent.atomic.AtomicLong;

/** Counters shown by /unsignedchat so admins can see that the fix is actually doing something. */
public final class Stats {
    public final AtomicLong players = new AtomicLong();
    public final AtomicLong chat = new AtomicLong();
    public final AtomicLong commands = new AtomicLong();
    public final AtomicLong sessions = new AtomicLong();
    public final AtomicLong modified = new AtomicLong();
    public final AtomicLong errors = new AtomicLong();
    public volatile String lastError = "none";

    public void error(Throwable t) {
        errors.incrementAndGet();
        lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
