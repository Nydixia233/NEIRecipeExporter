package io.gtnh.neidump.util;

/**
 * Lightweight progress sink so long-running jobs (icon export) can report back
 * without owning chat/console formatting themselves.
 *
 * <p>Stage is a free-form label like {@code "items"} or {@code "fluids"}.
 * Implementations may throttle (e.g. only forward every N calls) — the producer
 * does NOT.
 */
public interface ProgressCallback {

    ProgressCallback NOOP = new ProgressCallback() {
        @Override public void onProgress(String stage, int done, int total) {}
        @Override public void onStageDone(String stage, int done, int total) {}
    };

    void onProgress(String stage, int done, int total);

    void onStageDone(String stage, int done, int total);
}
