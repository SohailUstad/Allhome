package com.allhome.colourcoats.flowlog;

import java.util.concurrent.CancellationException;

/** Only the webhook request thread accepts a prepared reply; a late worker cannot persist it. */
public final class TurnCompletion {
    private boolean deferred;
    private boolean abandoned;
    private boolean accepted;
    private Runnable pending;

    public synchronized void defer() { deferred = true; }
    public synchronized boolean deferred() { return deferred; }
    public synchronized boolean abandoned() { return abandoned; }
    public synchronized void checkActive() {
        if (abandoned || Thread.currentThread().isInterrupted()) throw new CancellationException("Turn abandoned");
    }
    public synchronized void prepare(Runnable commit) {
        checkActive();
        if (pending != null || accepted) throw new IllegalStateException("Reply already prepared or accepted");
        pending = commit;
    }
    public void accept() {
        Runnable commit;
        synchronized (this) {
            checkActive();
            if (accepted) throw new IllegalStateException("Reply already accepted");
            accepted = true;
            commit = pending;
            pending = null;
        }
        if (commit != null) commit.run();
    }
    public synchronized void abandon() {
        abandoned = true;
        pending = null;
    }
}
