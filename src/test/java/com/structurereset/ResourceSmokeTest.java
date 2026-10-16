package com.structurereset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Minecraft-free smoke tests for the workspace itself.
 *
 * <p>They compile and execute against the JUnit 5 API only, so the offline
 * toolchain (tools/run_tests.py) gets a real test run long before a Forge
 * classpath exists. The workspace root is handed over by the runner as
 * {@code -Dstructurereset.workspace=<path>} and falls back to the current
 * working directory.</p>
 */
class ResourceSmokeTest {

    private static final String MODID = "structurereset";

    private static Path workspace() {
        String configured = System.getProperty("structurereset.workspace");
        Path root = (configured == null || configured.isBlank())
                ? Path.of("").toAbsolutePath()
                : Path.of(configured).toAbsolutePath();
        return root;
    }

    private static String read(Path relative) throws IOException {
        Path path = workspace().resolve(relative);
        assertTrue(Files.isRegularFile(path), "missing file: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path resources(String... parts) {
        Path path = Path.of("src", "main", "resources");
        for (String part : parts) {
            path = path.resolve(part);
        }
        return path;
    }

    /**
     * Keys of the entries directly inside the root object of a small JSON file.
     *
     * <p>Deliberately a hand written scanner instead of gson: this test must run
     * with nothing but the JUnit console jar on the classpath.</p>
     */
    private static Set<String> topLevelKeys(String json) {
        Set<String> keys = new LinkedHashSet<>();
        StringBuilder current = null;
        boolean inString = false;
        int depth = 0;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (inString) {
                if (character == '\\') {
                    index++;
                    if (current != null && index < json.length()) {
                        current.append(json.charAt(index));
                    }
                    continue;
                }
                if (character == '"') {
                    inString = false;
                    int next = index + 1;
                    while (next < json.length() && Character.isWhitespace(json.charAt(next))) {
                        next++;
                    }
                    if (depth == 1 && current != null && next < json.length()
                            && json.charAt(next) == ':') {
                        keys.add(current.toString());
                    }
                    current = null;
                    continue;
                }
                if (current != null) {
                    current.append(character);
                }
                continue;
            }
            if (character == '"') {
                inString = true;
                current = new StringBuilder();
            } else if (character == '{' || character == '[') {
                depth++;
            } else if (character == '}' || character == ']') {
                depth--;
            }
        }
        return keys;
    }

    @Test
    void modsTomlDeclaresTheModAndMandatoryForgeDependencies() throws IOException {
        String toml = read(resources("META-INF", "mods.toml"));
        assertTrue(toml.contains("modId=\"" + MODID + "\""),
                "mods.toml must declare modId=" + MODID);
        assertTrue(toml.contains("[[dependencies." + MODID + "]]"),
                "mods.toml needs a [[dependencies." + MODID + "]] block");
        assertTrue(toml.contains("modId=\"forge\"") && toml.contains("modId=\"minecraft\""),
                "mods.toml must depend on both forge and minecraft");
        assertTrue(toml.contains("mandatory=true"),
                "the forge/minecraft dependencies must be mandatory");
    }

    @Test
    void theMainClassUsesTheSameModId() throws IOException {
        String source = read(Path.of("src", "main", "java", "com", "structurereset",
                "StructureResetMod.java"));
        assertTrue(source.contains("MODID = \"" + MODID + "\""),
                "the MODID constant must match mods.toml");
        assertTrue(source.contains("@Mod(StructureResetMod.MODID)"),
                "the main class must be annotated with @Mod(StructureResetMod.MODID)");
    }

    @Test
    void packMetaUsesPackFormat15() throws IOException {
        String meta = read(resources("pack.mcmeta")).replaceAll("[ \t\r\n]", "");
        assertTrue(meta.contains("\"pack_format\":15"),
                "pack.mcmeta must declare pack_format 15 for 1.20.1");
    }

    @Test
    void englishAndChineseLangFilesExposeTheSameKeys() throws IOException {
        Set<String> english = topLevelKeys(read(resources("assets", MODID, "lang", "en_us.json")));
        Set<String> chinese = topLevelKeys(read(resources("assets", MODID, "lang", "zh_cn.json")));
        assertFalse(english.isEmpty(), "en_us.json must declare at least one key");
        assertEquals(english, chinese,
                "en_us.json and zh_cn.json must expose exactly the same key set");
    }

    @Test
    void itemLangKeysAreNamespacedToThisMod() throws IOException {
        Set<String> english = topLevelKeys(read(resources("assets", MODID, "lang", "en_us.json")));
        for (String key : english) {
            assertTrue(key.endsWith(MODID) || key.contains(MODID + "."),
                    "lang key '" + key + "' is not namespaced to " + MODID);
        }
    }

    /**
     * Every translation key of the shipped language files is really looked up.
     *
     * <p>A key nothing uses is a promise nothing keeps. The audit found
     * {@code itemGroup.structurereset} in exactly that state: it described a
     * creative tab this mod never registered, so a player searching for the item
     * there found nothing. The item name key itself passes because its longer
     * tooltip key contains it, which is also how vanilla composes the name.</p>
     */
    @Test
    void everyLangKeyIsUsedByTheSource() throws IOException {
        Set<String> english = topLevelKeys(read(resources("assets", MODID, "lang", "en_us.json")));
        StringBuilder source = new StringBuilder();
        try (var paths = Files.walk(workspace().resolve(Path.of("src", "main", "java")))) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                source.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
            }
        }
        for (String key : english) {
            assertTrue(source.indexOf(key) >= 0,
                    "lang key '" + key + "' is not used by any source file");
        }
    }
}
