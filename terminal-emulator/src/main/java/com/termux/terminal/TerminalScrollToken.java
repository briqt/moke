package com.termux.terminal;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellation shared by all encoded input batches belonging to one scroll operation.
 *
 * <p>Cancellation is permanent, thread-safe and never waits for transport I/O. Transports check the
 * token immediately before starting a write; bytes already inside a write cannot be retracted.
 */
public final class TerminalScrollToken {

    private final AtomicBoolean mValid = new AtomicBoolean(true);

    public boolean isValid() {
        return mValid.get();
    }

    public void cancel() {
        mValid.set(false);
    }
}
