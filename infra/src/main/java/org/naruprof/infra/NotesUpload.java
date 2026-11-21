package org.naruprof.infra;

public record NotesUpload(String filename, String path, byte[] content) {
    public static final int MAX_CONTENT_BYTES = 25 * 1024 * 1024;
}
