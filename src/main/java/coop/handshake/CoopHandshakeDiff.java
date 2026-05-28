package coop.handshake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CoopHandshakeDiff(List<String> lines) {
    public CoopHandshakeDiff {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static CoopHandshakeDiff compare(CoopHandshakeManifest host, CoopHandshakeManifest guest) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(guest, "guest");

        List<String> lines = new ArrayList<>();
        compareField(lines, "gameVersion", host.gameVersion(), guest.gameVersion());
        compareField(lines, "coopBuildVersion", host.coopBuildVersion(), guest.coopBuildVersion());
        compareField(lines, "coopGitCommit", host.coopGitCommit(), guest.coopGitCommit());

        Map<String, CoopHandshakeManifest.ModEntry> hostMods = byId(host.enabledMods());
        Map<String, CoopHandshakeManifest.ModEntry> guestMods = byId(guest.enabledMods());

        for (String modId : hostMods.keySet()) {
            CoopHandshakeManifest.ModEntry hostMod = hostMods.get(modId);
            CoopHandshakeManifest.ModEntry guestMod = guestMods.get(modId);
            if (guestMod == null) {
                lines.add("mod " + modId + ": missing on guest");
                continue;
            }
            compareMod(lines, hostMod, guestMod);
        }

        for (String modId : guestMods.keySet()) {
            if (!hostMods.containsKey(modId)) {
                lines.add("mod " + modId + ": extra on guest");
            }
        }

        return new CoopHandshakeDiff(lines);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public String toDisplayString() {
        return String.join("\n", lines);
    }

    private static Map<String, CoopHandshakeManifest.ModEntry> byId(List<CoopHandshakeManifest.ModEntry> mods) {
        LinkedHashMap<String, CoopHandshakeManifest.ModEntry> byId = new LinkedHashMap<>();
        mods.stream()
                .sorted(java.util.Comparator.comparing(CoopHandshakeManifest.ModEntry::id))
                .forEach(mod -> byId.put(mod.id(), mod));
        return byId;
    }

    private static void compareMod(List<String> lines, CoopHandshakeManifest.ModEntry host,
                                   CoopHandshakeManifest.ModEntry guest) {
        String prefix = "mod " + host.id() + ".";
        compareField(lines, prefix + "name", host.name(), guest.name());
        compareField(lines, prefix + "version", host.version(), guest.version());
        compareField(lines, prefix + "gameVersion", host.gameVersion(), guest.gameVersion());
        compareField(lines, prefix + "path", host.path(), guest.path());
        compareField(lines, prefix + "jars", host.jars().toString(), guest.jars().toString());
        compareChecksums(lines, host.id(), host.checksums(), guest.checksums());
    }

    private static void compareChecksums(List<String> lines, String modId, Map<String, String> host,
                                         Map<String, String> guest) {
        for (String file : host.keySet()) {
            String hostChecksum = host.get(file);
            String guestChecksum = guest.get(file);
            if (guestChecksum == null) {
                lines.add("mod " + modId + ".checksum " + file + ": host=" + hostChecksum + " guest=<missing>");
                continue;
            }
            if (!Objects.equals(hostChecksum, guestChecksum)) {
                lines.add("mod " + modId + ".checksum " + file + ": host=" + hostChecksum + " guest=" + guestChecksum);
            }
        }
        for (String file : guest.keySet()) {
            if (!host.containsKey(file)) {
                lines.add("mod " + modId + ".checksum " + file + ": host=<missing> guest=" + guest.get(file));
            }
        }
    }

    private static void compareField(List<String> lines, String field, String host, String guest) {
        if (!Objects.equals(host, guest)) {
            lines.add(field + ": host=" + host + " guest=" + guest);
        }
    }
}
