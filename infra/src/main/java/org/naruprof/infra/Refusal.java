package org.naruprof.infra;

import tools.jackson.databind.node.ObjectNode;

public final class Refusal extends RuntimeException {
    public final int status;
    public final ObjectNode body;
    public Refusal(int status, ObjectNode body) {
        super(body.path("error").asString("Request refused"));
        this.status = status;
        this.body = body;
    }
    public Refusal(int status, String message) { this(status, Json.object("error", message)); }
}
