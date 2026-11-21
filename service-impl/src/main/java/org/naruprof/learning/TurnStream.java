package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.io.IOException;
import java.util.*;
import org.naruprof.api.Learning;
import tools.jackson.databind.node.ObjectNode;

/** Readers can detach freely. A slow reader skips superseded partials of the same message. */
final class TurnStream implements Learning.Subscription {
    private record Frame(long sequence,String data) {}
    private final LinkedHashMap<String,Frame> frames = new LinkedHashMap<>();
    final String owner = id("turn");
    private long sequence;
    volatile boolean aborted;
    private boolean finished;
    synchronized void emit(String type,ObjectNode message) {
        if (finished) return;
        var event = object("type",type);
        if (message != null) event.set("message",message);
        String slot = type.equals("message_update") ? "update/"+text(message,"id") : UUID.randomUUID().toString();
        frames.put(slot,new Frame(++sequence,event.toString()));
        notifyAll();
    }
    synchronized void finish(String error) {
        if (error != null) frames.put("error",new Frame(++sequence,object("type","error","errorText",error).toString()));
        finished = true;
        notifyAll();
    }
    public void consume(Learning.Stream out) throws InterruptedException,IOException {
        long cursor = 0;
        while (true) {
            List<Frame> batch;
            boolean done;
            synchronized(this) {
                while (!finished && sequence == cursor) wait();
                final long after = cursor;
                batch = frames.values().stream().filter(f -> f.sequence > after).sorted(Comparator.comparingLong(Frame::sequence)).toList();
                cursor = sequence;
                done = finished;
            }
            for (var frame : batch) out.send(frame.data);
            if (done) { out.send("[DONE]"); return; }
        }
    }
}
