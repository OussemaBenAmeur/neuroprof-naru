package org.naruprof.ports;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import tools.jackson.databind.node.ObjectNode;

public interface LessonRuntime {
    record Request(String chatId, String turnId, String provider, String model, String prompt, List<String> tools, ObjectNode record) {
        public Request(String chatId,String turnId,String model,String prompt,List<String> tools,ObjectNode record) {
            this(chatId,turnId,"learning",model,prompt,tools,record);
        }
    }
    boolean ready();
    boolean configured();
    default boolean configured(String provider) { return configured(); }
    ObjectNode respond(Request request, BooleanSupplier aborted, Consumer<ObjectNode> partial) throws Exception;
}
