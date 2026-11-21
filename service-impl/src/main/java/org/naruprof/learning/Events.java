package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.util.concurrent.*;
import org.naruprof.api.Learning;

/** Ephemeral notify-then-fetch channel, independent of the transactional projection. */
final class Events {
    private final CopyOnWriteArrayList<ArrayBlockingQueue<String>> readers = new CopyOnWriteArrayList<>();
    void emit(String type,String chat) {
        var payload = chat == null ? object() : object("chatId",chat);
        String frame = "event: "+type+"\ndata: "+payload+"\n\n";
        for (var queue : readers) if (!queue.offer(frame)) {
            readers.remove(queue); queue.clear(); queue.offer("");
        }
    }
    Learning.Subscription subscribe() {
        return out -> {
            var queue = new ArrayBlockingQueue<String>(128);
            readers.add(queue);
            try {
                for (;;) {
                    String frame = queue.poll(25,TimeUnit.SECONDS);
                    if ("".equals(frame)) return;
                    out.send(frame == null ? ": ping\n\n" : frame);
                }
            } finally { readers.remove(queue); }
        };
    }
}
