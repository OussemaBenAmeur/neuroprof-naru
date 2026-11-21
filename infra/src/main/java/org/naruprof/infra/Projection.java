package org.naruprof.infra;

public record Projection(String profileId, long sequence, long teachingSessions, long practiceSessions,
                         long propositions, long reasoningChains, boolean deleted) {}
