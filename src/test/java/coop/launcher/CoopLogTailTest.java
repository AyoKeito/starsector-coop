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
