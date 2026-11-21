package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NElement;

/**
 * Drop after exactly one consumption — unicast semantics.
 */
public class OnceRetentionPolicy implements NaruRetentionPolicy {
    public static final OnceRetentionPolicy INSTANCE = new OnceRetentionPolicy();

    public boolean shouldDrop(NaruEvent event) {
        return event.consumedCount() > 0;
    }

    public long nextCheckMillis(NaruEvent event) {
        return 0;
    }

    @Override
    public NElement toElement() {
        return NElement.ofName("once");
    }

    @Override
    public String toString() {
        return toElement().toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        OnceRetentionPolicy that = (OnceRetentionPolicy) o;
        return true;
    }

    @Override
    public int hashCode() {
        return 3;
    }
}
