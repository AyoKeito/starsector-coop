package coop.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CoopNetServiceSandboxCompatibilityTest {
    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void runtimeNetcodeAvoidsNettyBecauseStarsectorScriptSandboxBlocksItsReflection() throws IOException {
        Path sourceRoot = PROJECT_ROOT.resolve("src/main/java/coop/net");
        List<Path> sourceFiles;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            sourceFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }

        List<String> offenders = new ArrayList<>();
        for (Path path : sourceFiles) {
            if (Files.readString(path, StandardCharsets.UTF_8).contains("io.netty.")) {
                offenders.add(PROJECT_ROOT.relativize(path).toString());
            }
        }

        assertEquals(List.of(), offenders,
                "Netty initializes reflection classes that Starsector scripts are not allowed to load");
    }

    @Test
    void modDescriptorDoesNotLoadNettyJarsIntoScriptClassLoader() throws IOException {
        String modInfo = Files.readString(PROJECT_ROOT.resolve("mod_info.json"), StandardCharsets.UTF_8);

        assertFalse(modInfo.contains("jars/netty/"),
                "Bundled Netty jars are loaded by Starsector's script classloader and trigger reflection checks");
    }

    @Test
    void runtimeNetcodeDoesNotCreateBackgroundThreadsInStarsectorScriptSandbox() throws IOException {
        String source = Files.readString(
                PROJECT_ROOT.resolve("src/main/java/coop/net/CoopNetService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("new Thread("),
                "Starsector can terminate mod-created networking threads without surfacing the failure in starsector.log");
        assertFalse(source.contains("coop-net-"),
                "Networking should progress from the campaign pump thread instead of named background workers");
    }

    @Test
    void runtimeNetcodeAvoidsJavaIoClassesBecauseStarsectorTreatsThemAsFileAccess() throws IOException {
        String source = Files.readString(
                PROJECT_ROOT.resolve("src/main/java/coop/net/CoopNetService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("java.io."),
                "Starsector blocks java.io classes for scripts, including in-memory stream classes");
        assertFalse(source.contains("ByteArrayOutputStream"),
                "Frame buffering must avoid java.io stream types inside Starsector's script sandbox");
    }

    @Test
    void portMappingCodeAvoidsTheApisTheScriptClassloaderBlocks() throws IOException {
        // The port mapper speaks HTTP and SOAP, which is exactly the shape of code that reaches for
        // HttpURLConnection, URL.openStream or an XML parser. All three drag in java.io, which the
        // Starsector script classloader refuses at runtime while compiling and unit-testing green.
        List<String> classes = List.of(
                "CoopPortMapper.java",
                "CoopConnectionDoctor.java",
                "CoopHttpMessages.java",
                "CoopSsdpMessages.java",
                "CoopUpnpDescriptor.java",
                "CoopUpnpSoap.java",
                "CoopNatPmpMessages.java");

        List<String> offenders = new ArrayList<>();
        for (String className : classes) {
            Path path = PROJECT_ROOT.resolve("src/main/java/coop/net").resolve(className);
            // Comments name these APIs on purpose (explaining why they are avoided), so scan code only.
            String source = stripComments(Files.readString(path, StandardCharsets.UTF_8));
            for (String banned : List.of("java.io.", "java.nio.file", "java.lang.reflect",
                    "HttpURLConnection", "URL.openStream", "javax.xml", "new Thread(")) {
                if (source.contains(banned)) {
                    offenders.add(className + " uses " + banned);
                }
            }
        }

        assertEquals(List.of(), offenders);
    }

    /** Crude but sufficient: the sources scanned here contain no comment markers inside literals. */
    private static String stripComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else {
                code.append(source.charAt(i));
                i++;
            }
        }
        return code.toString();
    }
}
