package coop.config;

import coop.config.CoopOptionsRegistry.Option;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shipped {@code data/config/coop_options.json}, which nothing else can check: a typo in
 * it is invisible until a player launches the game, and even then it fails quietly (the store
 * degrades to the registry defaults with one WARN).
 *
 * <p>The file uses {@code #} comments, the same convention as the game's own
 * {@code data/config/settings.json}, which the engine's JSON loader strips before parsing. The
 * bundled {@code json.jar} tokeniser does not strip them, so this test does it the same way before
 * parsing.
 */
class CoopOptionsShippedFileTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final Path SHIPPED = PROJECT_ROOT.resolve(CoopOptionsStore.SHIPPED_PATH);

    private static JSONObject parseShipped() throws IOException, JSONException {
        String text = Files.readString(SHIPPED, StandardCharsets.UTF_8);
        StringBuilder stripped = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int hash = line.indexOf('#');
            stripped.append(hash < 0 ? line : line.substring(0, hash)).append('\n');
        }
        return new JSONObject(new JSONTokener(stripped.toString()));
    }

    @Test
    void theShippedFileIsValidJsonOnceCommentsAreStripped() throws IOException, JSONException {
        assertTrue(Files.exists(SHIPPED), CoopOptionsStore.SHIPPED_PATH + " is missing");
        assertTrue(parseShipped().length() > 0);
    }

    @Test
    void itListsEveryFileBackedOptionAndNothingElse() throws IOException, JSONException {
        JSONObject json = parseShipped();

        List<String> missing = new ArrayList<>();
        for (Option option : CoopOptionsRegistry.fileBackedOptions()) {
            if (!json.has(option.key())) {
                missing.add(option.key());
            }
        }
        assertTrue(missing.isEmpty(), "shipped file is missing " + missing);

        List<String> extra = new ArrayList<>();
        Iterator<?> keys = json.keys();
        while (keys.hasNext()) {
            String key = String.valueOf(keys.next());
            Option option = CoopOptionsRegistry.option(key);
            if (option == null || option.dOnly()) {
                extra.add(key);
            }
        }
        assertTrue(extra.isEmpty(), "shipped file has unregistered or -D-only entries: " + extra);
    }

    /**
     * The shipped values must equal the registry defaults. That is what makes the file safe: if the
     * engine ever fails to hand it to us, the store falls back to the registry and the session
     * behaves identically instead of quietly changing.
     */
    @Test
    void everyShippedValueMatchesTheRegistryDefault() throws IOException, JSONException {
        JSONObject json = parseShipped();
        for (Option option : CoopOptionsRegistry.fileBackedOptions()) {
            String shipped = String.valueOf(json.opt(option.key()));
            assertEquals(option.defaultValue(), shipped,
                    option.key() + " ships a value other than its registry default");
        }
    }

    /**
     * The store runs inside Starsector's script classloader, which refuses {@code java.io.*},
     * {@code java.nio.file.*} and {@code java.lang.reflect} - code that compiles and unit-tests
     * green and then throws in-game. Same guard the net and handshake packages carry.
     */
    @Test
    void theConfigPackageStaysInsideTheScriptSandbox() throws IOException {
        Path sourceRoot = PROJECT_ROOT.resolve("src/main/java/coop/config");
        List<Path> sources;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertFalse(sources.isEmpty());
        List<String> offenders = new ArrayList<>();
        for (Path path : sources) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            // Strip javadoc/line comments so the class's own explanation of the rule is not a
            // violation of it.
            String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
            for (String banned : List.of("java.io.", "java.nio.file", "java.lang.reflect")) {
                if (code.contains(banned)) {
                    offenders.add(path.getFileName() + " uses " + banned);
                }
            }
        }
        assertTrue(offenders.isEmpty(), String.valueOf(offenders));
    }
}
