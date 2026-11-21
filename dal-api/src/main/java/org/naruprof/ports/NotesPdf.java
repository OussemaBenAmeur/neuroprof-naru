package org.naruprof.ports;

public interface NotesPdf {
    record Text(String markdown, String notice) {}
    Text convert(byte[] input) throws Exception;
}
