package org.naruprof.app;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.naruprof.infra.*;
import org.naruprof.ports.*;

final class DeepgramDictation implements Dictation {
    private final KeyStore keys;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    DeepgramDictation(KeyStore keys) { this.keys = keys; }

    @Override public String token() throws Exception {
        String key = keys.resolve("DEEPGRAM_API_KEY");
        if (key == null || key.isBlank()) throw refusal(503,"No DEEPGRAM_API_KEY — add it to auth.json (desktop) or .env.local (dev).");
        var request = HttpRequest.newBuilder(URI.create("https://api.deepgram.com/v1/auth/grant"))
                .timeout(Duration.ofSeconds(10)).header("Authorization","Token "+key)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"ttl_seconds\":300}")).build();
        var response = client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode()/100 != 2) throw refusal(502,"Deepgram token grant failed ("+response.statusCode()+")");
            var bytes = body.readNBytes(65_537);
            if (bytes.length > 65_536) throw refusal(502,"Deepgram grant returned an invalid response");
            String token = Json.text(Json.MAPPER.readTree(bytes),"access_token");
            if (token.isBlank()) throw refusal(502,"Deepgram grant returned no token");
            return token;
        }
    }
    private static Refusal refusal(int status,String message) { return new Refusal(status,Json.object("message",message)); }
}
