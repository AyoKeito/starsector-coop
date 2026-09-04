package coop.launcher;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopLogTailTest {

    /** Real lines lifted out of a test client's starsector.log. */
    private static final String DOCTOR_HEADER =
            "33291 [Thread-2] INFO  coop.net.CoopNetPump  - Coop connection doctor:";
    private static final String DOCTOR_BODY_1 =
            "  role              host, listening on port 7777 (TCP+UDP)";
    private static final String DOCTOR_BODY_2 =
            "  share with guest  91.77.160.252:7777";
    private static final String DOCTOR_MARKER_LINE =
            "183534 [Thread-2] WARN  coop.ui.CoopDoctorMarker  - [COOP-DOCTOR] code=COOP-SESSION"
                    + " role=HOST cause=GRACE_EXPIRED retryable=false";
    private static final String COOP_WARN =
            "183532 [Thread-2] WARN  coop.net.CoopNetPump  - Coop peer disconnected; session reset";
    private static final String COOP_LOBBY_INFO =
            "183535 [Thread-2] INFO  coop.net.CoopNetPump  - Coop lobby opened as HOST";
    private static final String COOP_SESSION_INFO =
            "47448 [Thread-2] INFO  coop.net.CoopNetPump  - Coop session resumed";
    private static final String COOP_CHATTER_INFO =
            "47411 [Thread-2] INFO  coop.net.CoopNetPump  - Coop net HOST outbound LOBBY_ACCEPT seq=1";
    private static final String ENGINE_WARN =
            "12 [Thread-4] WARN  com.fs.starfarer.combat.String  - a vanilla warning";
    private static final String ENGINE_INFO =
            "12 [Thread-4] INFO  com.fs.starfarer.campaign.Q  - loading something";

    private static CoopLogTail.Filter filter() {
        return new CoopLogTail.Filter();
    }

    @Test
    void theDoctorBlockIsKeptWholeAndEndsAtTheNextLogLine() {
        CoopLogTail.Filter filter = filter();

        assertTrue(filter.accept(DOCTOR_HEADER));
        assertTrue(filter.accept(DOCTOR_BODY_1));
        assertTrue(filter.accept(DOCTOR_BODY_2));
        assertFalse(filter.accept(ENGINE_INFO), "the block has to end at the next log line");
    }

    @Test
    void aBlankLineAlsoEndsTheDoctorBlock() {
        CoopLogTail.Filter filter = filter();

        assertTrue(filter.accept(DOCTOR_HEADER));
        assertTrue(filter.accept(DOCTOR_BODY_1));
        assertFalse(filter.accept(""));
        assertFalse(filter.accept("  a stray indented line after the block"));
    }

    @Test
    void aDoctorBlockThatNeverEndsIsCutOffAtFortyLines() {
        CoopLogTail.Filter filter = filter();
        assertTrue(filter.accept(DOCTOR_HEADER));

        for (int i = 0; i < CoopLogTail.MAX_DOCTOR_BLOCK_LINES; i++) {
            assertTrue(filter.accept("  body line " + i), "line " + i);
        }
        assertFalse(filter.accept("  body line past the cap"));
    }

    @Test
    void doctorMarkerLinesAlwaysPass() {
        assertTrue(filter().accept(DOCTOR_MARKER_LINE));
    }

    @Test
    void coopWarningsAndErrorsPassAndEngineOnesDoNot() {
        assertTrue(filter().accept(COOP_WARN));
        assertTrue(filter().accept(
                "1 [main] ERROR coop.CoopModPlugin  - something blew up"));
        assertFalse(filter().accept(ENGINE_WARN));
    }

    @Test
    void onlyLobbyAndSessionInfoLinesPass() {
        assertTrue(filter().accept(COOP_LOBBY_INFO));
        assertTrue(filter().accept(COOP_SESSION_INFO));
        assertFalse(filter().accept(COOP_CHATTER_INFO));
        assertFalse(filter().accept(ENGINE_INFO));
    }

    @Test
    void aStackTraceLineOnItsOwnIsNotForwarded() {
        assertFalse(filter().accept("\tat com.fs.starfarer.campaign.Q.o00000(Unknown Source)"));
        assertFalse(filter().accept(null));
    }

    @Test
    void theTailFollowsAppendsAndSurvivesTheGameTruncatingTheFile(@TempDir Path temp)
            throws Exception {
        Path log = temp.resolve("starsector.log");
        Files.writeString(log, "0 [main] INFO  com.fs.starfarer.X  - old session\n",
                StandardCharsets.UTF_8);
        List<String> seen = new CopyOnWriteArrayList<>();

        try (CoopLogTail tail = CoopLogTail.start(log.toFile(), seen::add)) {
            append(log, COOP_WARN + "\n" + ENGINE_INFO + "\n");
            awaitContains(seen, COOP_WARN);

            // What the game does at startup: truncate and start over.
            Files.writeString(log, "", StandardCharsets.UTF_8);
            append(log, DOCTOR_HEADER + "\n" + DOCTOR_BODY_1 + "\n");
            awaitContains(seen, DOCTOR_HEADER);
            awaitContains(seen, DOCTOR_BODY_1);
        }

        assertFalse(seen.contains(ENGINE_INFO), "engine chatter must not reach the pane");
        assertFalse(seen.contains("0 [main] INFO  com.fs.starfarer.X  - old session"),
                "the tail starts at the end of the existing file");
    }

    @Test
    void theTailWaitsForAFileThatIsNotThereYet(@TempDir Path temp) throws Exception {
        Path log = temp.resolve("starsector.log");
        List<String> seen = new CopyOnWriteArrayList<>();

        try (CoopLogTail tail = CoopLogTail.start(log.toFile(), seen::add)) {
            Files.writeString(log, "", StandardCharsets.UTF_8);
            append(log, COOP_LOBBY_INFO + "\n");
            awaitContains(seen, COOP_LOBBY_INFO);
        }
    }

    /**
     * The game writes its log in the platform charset, so a byte that is not valid UTF-8 is normal.
     * Advancing the file position by the re-encoded length of the decoded text over-ran the end of
     * the file, which the tail then read as "the game truncated it" and replayed forever.
     */
    @Test
    void aByteThatIsNotValidUtf8DoesNotSendTheTailIntoAReplayLoop(@TempDir Path temp)
            throws Exception {
        Path log = temp.resolve("starsector.log");
        Files.write(log, new byte[0]);
        List<String> seen = new CopyOnWriteArrayList<>();

        try (CoopLogTail tail = CoopLogTail.start(log.toFile(), seen::add)) {
            // 0xE9 is "e with an acute accent" in Cp1252, the charset log4j 1.2.9 writes in here.
            byte[] accented = ("0 [main] INFO  com.fs.starfarer.X  - a ship named Café\n"
                    + COOP_WARN + "\n").getBytes(StandardCharsets.ISO_8859_1);
            appendBytes(log, accented);
            awaitContains(seen, COOP_WARN);

            // Several poll intervals: a desynchronised cursor replays the whole file every 0 ms.
            Thread.sleep(1_500L);
            assertEquals(1, count(seen, COOP_WARN), "the tail replayed the file: " + seen.size()
                    + " lines forwarded");
        }
    }

    /**
     * log4j rolls the log by renaming it. Windows refuses the rename while another process holds the
     * file without FILE_SHARE_DELETE, and log4j 1.2.9 answers a refused rename by reopening with
     * append=false - truncating the whole session's log.
     */
    @Test
    void theGameCanRollTheLogWhileTheTailIsOpen(@TempDir Path temp) throws Exception {
        Path log = temp.resolve("starsector.log");
        Files.writeString(log, "", StandardCharsets.UTF_8);
        List<String> seen = new CopyOnWriteArrayList<>();

        try (CoopLogTail tail = CoopLogTail.start(log.toFile(), seen::add)) {
            // A log rolls because it got big: the file that is renamed away is far longer than the
            // one that replaces it, which is what tells the tail to start over.
            append(log, "0 [main] INFO  com.fs.starfarer.X  - " + "filler ".repeat(200) + "\n");
            append(log, COOP_WARN + "\n");
            awaitContains(seen, COOP_WARN);

            Files.move(log, temp.resolve("starsector.log.1"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // The rolled-to file is shorter than where the tail was, which is its cue to start over.
            append(log, DOCTOR_MARKER_LINE + "\n");
            awaitContains(seen, DOCTOR_MARKER_LINE);
        }
    }

    /** The tail must not hold the file in a way that stops the writer, which is the game. */
    @Test
    void theWriterCanKeepWritingWhileTheTailIsOpen(@TempDir Path temp) throws Exception {
        Path log = temp.resolve("starsector.log");
        Files.writeString(log, "", StandardCharsets.UTF_8);
        List<String> seen = new CopyOnWriteArrayList<>();

        try (CoopLogTail tail = CoopLogTail.start(log.toFile(), seen::add);
             RandomAccessFile writer = new RandomAccessFile(log.toFile(), "rw")) {
            writer.seek(writer.length());
            writer.write((COOP_WARN + "\n").getBytes(StandardCharsets.UTF_8));
            awaitContains(seen, COOP_WARN);
        }
    }

    private static int count(List<String> seen, String line) {
        int found = 0;
        for (String candidate : seen) {
            if (line.equals(candidate)) {
                found++;
            }
        }
        return found;
    }

    private static void appendBytes(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static void awaitContains(List<String> seen, String line) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (seen.contains(line)) {
                return;
            }
            Thread.sleep(50L);
        }
        assertEquals(line, String.join(" | ", seen), "the tail never forwarded the line");
    }
}
