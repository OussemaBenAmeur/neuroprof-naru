package org.naruprof.app;

import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.naruprof.api.Learning;
import org.naruprof.grpc.ProjectionServer;
import org.naruprof.http.*;
import org.naruprof.jpa.JpaProfile;
import org.naruprof.learning.LearningModule;
import org.naruprof.runtime.NaruRuntime;
import org.naruprof.runtime.ProtectedKeys;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class Application {
    private static String setting(String key,String fallback) { return System.getenv().getOrDefault(key,fallback); }
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless","true");
        if (args.length > 0 && args[0].equals("profile")) {
            Maintenance.run(args);
            return;
        }
        if (args.length > 0 && args[0].equals("projection-replay")) {
            ProjectionReplay.run(args);
            return;
        }
        URI http = URI.create(setting("NARU_HTTP_URL","http://127.0.0.1:8082"));
        var app = new SpringApplication(Application.class);
        app.setDefaultProperties(Map.of("server.address",http.getHost(),"server.port",http.getPort(),
                "spring.servlet.multipart.max-file-size","25MB","spring.servlet.multipart.max-request-size","26MB",
                "spring.mvc.async.request-timeout","0","spring.threads.virtual.enabled","true","spring.main.banner-mode","off"));
        app.run(args);
    }
    @Bean Path directory(Environment environment) throws Exception {
        return profileDirectory(environment.getProperty("NARUPROF_DATA_DIR",".profile"));
    }
    static Path profileDirectory(String value) throws Exception {
        Path path = Path.of(value).toAbsolutePath().normalize();
        try { Files.createDirectories(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
        catch (UnsupportedOperationException os) { Files.createDirectories(path); }
        return path;
    }
    @Bean JpaProfile store(Path directory,Environment environment) { return new JpaProfile(directory,environment.getProperty("NARUPROF_PROFILE_ID")); }
    @Bean ProtectedKeys keys(Path directory,Environment environment) { return new ProtectedKeys(Path.of(environment.getProperty("LH_AUTH_FILE",directory.resolve("auth.json").toString()))); }
    @Bean NaruRuntime runtime(Path directory,ProtectedKeys keys,Environment environment) throws Exception {
        String endpoint = environment.getProperty("NARUPROF_MODEL_URL");
        return new NaruRuntime(directory.resolve("runtime"), endpoint == null ? null : URI.create(endpoint), environment.getProperty("NARUPROF_MODEL_TOKEN"),keys);
    }
    @Bean LearningModule learning(JpaProfile store,NaruRuntime runtime,ProtectedKeys keys,Environment environment) { return new LearningModule(store,runtime,environment.getProperty("NARUPROF_MODEL_ID"),keys,new PopplerNotesPdf(),new DeepgramDictation(keys)); }
    @Bean LearningController controller(Learning learning) { return new LearningController(learning); }
    @Bean ServiceAuth serviceAuth(Environment environment) { return new ServiceAuth(environment.getProperty("VERTX_TO_NARU_TOKEN")); }
    @Bean ProjectionServer projection(Learning learning,Environment environment) throws Exception {
        URI target = URI.create("grpc://"+environment.getProperty("NARU_GRPC_TARGET","127.0.0.1:9092"));
        return new ProjectionServer(learning,new InetSocketAddress(target.getHost(),target.getPort()),environment.getProperty("TWIN_TO_NARU_TOKEN"));
    }
}
