package org.naruprof.api;

import java.io.IOException;
import java.util.List;
import org.naruprof.infra.NotesUpload;
import tools.jackson.databind.node.ObjectNode;

public interface Learning extends ProjectionRead {
    ObjectNode create(ObjectNode body);
    ObjectNode chats();
    ObjectNode chat(String id);
    ObjectNode rename(String id, ObjectNode body);
    ObjectNode delete(String id, String mode);
    boolean submit(String id, ObjectNode input);
    void abort(String id);
    boolean dismiss(String id,ObjectNode input);
    void steer(String id,ObjectNode input);
    void exportProfile(java.io.OutputStream destination) throws IOException;
    interface Stream { void send(String data) throws IOException; }
    interface Subscription { void consume(Stream out) throws IOException, InterruptedException; }
    Subscription stream(String id);
    Subscription events();
    ObjectNode sideChats(String chatId);
    Subscription askSide(String chatId,ObjectNode input);
    Subscription watchSide(String chatId,String threadId);
    ObjectNode openSide(String chatId,String threadId,ObjectNode input);
    void stopSide(String chatId,String threadId);
    ObjectNode keys();
    ObjectNode markdown(String id);
    ObjectNode dictationToken();
    ObjectNode key(String id,ObjectNode input);
    ObjectNode prompts();
    ObjectNode override(String path,ObjectNode input);
    ObjectNode themes();
    ObjectNode createTheme(ObjectNode input);
    ObjectNode deleteTheme(String id);
    ObjectNode image(String chat,String snapshotId);
    ObjectNode chatSettings(String id,ObjectNode input);
    ObjectNode positions(ObjectNode input);
    ObjectNode jobs(String id);
    ObjectNode models();
    ObjectNode settings();
    ObjectNode settings(String section, ObjectNode body);
    ObjectNode graph();
    ObjectNode renderSettings(ObjectNode input);
    ObjectNode importNotes(List<NotesUpload> files);
    void extractChat(String id);
    ObjectNode extractionStatus();
    ObjectNode activity();
    void abortExtraction(String id);
    ObjectNode deleteGraphNode(String id);
    ObjectNode sources();
    ObjectNode organizeSources(ObjectNode input);
    ObjectNode deleteSources(ObjectNode input);
    ObjectNode mergeGraph(ObjectNode batch);
    boolean ready();
}
