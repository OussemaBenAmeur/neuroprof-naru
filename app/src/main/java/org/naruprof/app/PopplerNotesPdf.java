package org.naruprof.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.naruprof.infra.Refusal;
import org.naruprof.ports.NotesPdf;

final class PopplerNotesPdf implements NotesPdf {
    private static final int MAX_PAGES = 1_000;
    private static final int MAX_TEXT_BYTES = 25 * 1024 * 1024;
    private static final Pattern PAGES = Pattern.compile("(?m)^Pages:\\s+(\\d+)\\s*$");

    @Override public Text convert(byte[] input) throws Exception {
        Path directory = Files.createTempDirectory("naruprof-pdf-");
        Path pdf = directory.resolve("notes.pdf");
        Path info = directory.resolve("info.txt");
        Path text = directory.resolve("notes.txt");
        try {
            Files.write(pdf,input);
            run(info,"pdfinfo",pdf.toString());
            var pages = PAGES.matcher(read(info,128 * 1024));
            if (!pages.find()) throw new Refusal(400,"Could not open this PDF.");
            int count = Integer.parseInt(pages.group(1));
            if (count > MAX_PAGES) throw new Refusal(400,"Too long. Max 1,000 pages.");
            run(text,"pdftotext","-enc","UTF-8",pdf.toString(),"-");
            String[] decoded = read(text,MAX_TEXT_BYTES).split("\f",-1);
            StringBuilder markdown = new StringBuilder();
            int skipped = 0;
            for (int i = 0; i < count; i++) {
                if (i >= decoded.length || decoded[i].isBlank()) skipped++;
                else markdown.append("## Page ").append(i + 1).append("\n\n").append(decoded[i].strip()).append("\n\n");
            }
            if (markdown.isEmpty()) throw new Refusal(400,"OCR not supported yet.");
            String omitted = skipped == 0 ? "" : " Skipped " + skipped + " image-only " + (skipped == 1 ? "page" : "pages") + "; OCR is not supported.";
            return new Text(markdown.toString(),"Scanned pages may have errors." + omitted);
        } finally {
            Files.deleteIfExists(text);
            Files.deleteIfExists(info);
            Files.deleteIfExists(pdf);
            Files.deleteIfExists(directory);
        }
    }

    private static String read(Path file, int limit) throws IOException {
        if (Files.size(file) > limit) throw new Refusal(413,"Converted PDF text is too large.");
        return Files.readString(file,StandardCharsets.UTF_8);
    }

    private static void run(Path output, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(30,TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new Refusal(400,"Could not open this PDF.");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5,TimeUnit.SECONDS);
            }
        }
    }
}
