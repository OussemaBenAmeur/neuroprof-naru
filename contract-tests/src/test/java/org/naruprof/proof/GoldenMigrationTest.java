package org.naruprof.proof;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.naruprof.learning.LegacyProfile;
import org.naruprof.infra.Json;
import org.naruprof.learning.LearningModule;
import org.naruprof.ports.ProfileStore;
import org.naruprof.ports.LessonRuntime;
import java.util.function.*;
import java.io.*;
import java.util.zip.*;
import tools.jackson.databind.node.ObjectNode;

class GoldenMigrationTest {
    @Test void originalFilesRemainUntouchedAndReadableAfterRoundTrip(@TempDir Path dir) throws Exception {
        var store = new DurableFixture(dir);
        try { roundTrip(store,dir); }
        finally { store.close(); }
    }

    @Test void archiveMatchesCanonicalGoldenProfile(@TempDir Path dir) throws Exception {
        roundTrip(new GraphAndSideTest.TestStore(),dir);
    }

    private void roundTrip(ProfileStore store,Path dir) throws Exception {
        Path source = Path.of("../fixtures/golden-data").toRealPath();
        var preview = LegacyProfile.preview(source);
        var report = LegacyProfile.importProfile(source,store,preview.sha256());
        assertEquals(2,report.path("chats").asInt());
        assertEquals(5,report.path("jobs").asInt());
        assertEquals(278,report.at("/projection/propositions").asInt());
        assertEquals(173,report.at("/projection/reasoningChains").asInt());
        assertTrue(report.path("skipped").isEmpty());
        assertEquals(report.toString(),LegacyProfile.importProfile(source,store,preview.sha256()).toString());
        assertEquals(preview,LegacyProfile.preview(source));
        var export = dir.resolve("roundtrip");
        LegacyProfile.export(store,export);
        for (var key : preview.files().keySet()) {
            if (!key.endsWith(".json")) continue;
            assertEquals(Json.MAPPER.readTree(Files.readAllBytes(source.resolve(key))),
                    Json.MAPPER.readTree(Files.readAllBytes(export.resolve(key))),key);
        }
        for (String script : List.of("sessions","graph")) {
            var process = new ProcessBuilder("python3","../helpers/learning-scripts/"+script+".py",
                    "--data-dir",export.toString(),"--format","json",script.equals("sessions") ? "list" : "propositions").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(0,process.waitFor(),() -> output.substring(0,Math.min(400,output.length())));
        }
        try (var module = new LearningModule(store,noInference(),"fixture")) {
            var bytes = new ByteArrayOutputStream();
            module.exportProfile(bytes);
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    assertArrayEquals(Files.readAllBytes(export.resolve(entry.getName())),zip.readAllBytes(),entry.getName());
                }
            }
        }
    }

    private static LessonRuntime noInference() {
        return new LessonRuntime() {
            public boolean ready() { return true; }
            public boolean configured() { return false; }
            public ObjectNode respond(Request request,BooleanSupplier aborted,Consumer<ObjectNode> updates) {
                throw new AssertionError("Profile migration and export must never invoke a model");
            }
        };
    }
}
