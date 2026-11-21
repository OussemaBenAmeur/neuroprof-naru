package org.naruprof.app;

import java.nio.file.Path;
import org.naruprof.jpa.JpaProfile;
import org.naruprof.learning.LegacyProfile;
import org.naruprof.infra.Json;

final class Maintenance {
    static void run(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("profile preview|import|export <directory> [preview-sha256], status, or retire <profile-id>");
        Path path = args.length > 2 ? Path.of(args[2]) : null;
        if ("preview".equals(args[1])) {
            System.out.println(Json.MAPPER.writeValueAsString(LegacyProfile.preview(path)));
            return;
        }
        try (var store = new JpaProfile(Application.profileDirectory(System.getenv().getOrDefault("NARUPROF_DATA_DIR",".profile")),System.getenv("NARUPROF_PROFILE_ID"))) {
            switch (args[1]) {
                case "import" -> {
                    if (args.length != 4) throw new IllegalArgumentException("Supply the SHA-256 from preview");
                    System.out.println(LegacyProfile.importProfile(path,store,args[3]));
                }
                case "export" -> LegacyProfile.export(store,path);
                case "status" -> {
                    Object state = store.read(v -> v.snapshot());
                    System.out.println(Json.MAPPER.writeValueAsString(state));
                }
                case "retire" -> {
                    var state = store.read(v -> v.snapshot());
                    if (args.length != 3 || !state.profileId().equals(args[2])) throw new IllegalArgumentException("Supply the exact profile id from profile status");
                    if (!state.deleted()) store.write(tx -> { tx.project(true); return null; });
                }
                default -> throw new IllegalArgumentException("Unknown maintenance command");
            }
        }
    }
}
