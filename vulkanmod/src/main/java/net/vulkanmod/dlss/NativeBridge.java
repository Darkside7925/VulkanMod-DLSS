package net.vulkanmod.dlss;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JNI bridge to {@code mcdlss_native.dll} — the C++ glue that owns the NVIDIA Streamline
 * lifecycle (slInit / feature checks / tagging / evaluate) on top of VulkanMod's Vulkan device.
 *
 * <p>Phase 0 scope: prove the native library loads inside Minecraft and a JNI round-trip works
 * ({@link #hello(String)} / {@link #abiVersion()}) BEFORE any Streamline code is involved.
 * Streamline init and feature queries are added in Phase 1.
 *
 * <p>Loading is best-effort: any failure (missing DLL, non-Windows, load error) is caught and
 * leaves {@link #isLoaded()} false, so the game continues as plain VulkanMod (graceful fallback).
 */
public final class NativeBridge {
    public static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");

    /** Must match MCDLSS_ABI_VERSION in native/include/mcdlss.h. Bumped on any JNI signature change. */
    public static final int EXPECTED_ABI_VERSION = 1;

    private static final String LIB_NAME = "mcdlss_native";

    private static boolean loaded = false;
    private static String loadError = null;

    private NativeBridge() {}

    // --- Phase 0 native methods (no Streamline dependency) ---

    /** Round-trips a string through native code; returns a native-built banner string. */
    public static native String hello(String from);

    /** Returns the native library's ABI version, validated against {@link #EXPECTED_ABI_VERSION}. */
    public static native int abiVersion();

    /**
     * Attempts to load {@code mcdlss_native.dll}. Idempotent. Search order:
     * <ol>
     *   <li>{@code -Dmcdlss.native.path=<abs path to dll>} (dev override)</li>
     *   <li>{@code <gameDir>/mcdlss/mcdlss_native.dll}</li>
     *   <li>{@code <run>/../native/build/Release/mcdlss_native.dll} and Debug (dev-loop convenience)</li>
     *   <li>{@code System.loadLibrary(LIB_NAME)} via {@code java.library.path}</li>
     * </ol>
     */
    public static synchronized boolean load() {
        if (loaded) return true;
        if (loadError != null) return false; // already failed once; don't spam

        try {
            Path explicit = locate();
            if (explicit != null) {
                System.load(explicit.toAbsolutePath().toString());
                LOGGER.info("Loaded native library: {}", explicit.toAbsolutePath());
            } else {
                System.loadLibrary(LIB_NAME);
                LOGGER.info("Loaded native library via java.library.path: {}", LIB_NAME);
            }
        } catch (Throwable t) {
            loadError = t.toString();
            LOGGER.warn("DLSS native library not loaded — continuing as plain VulkanMod. Reason: {}", loadError);
            return false;
        }

        // Validate ABI before trusting any other native call.
        try {
            int abi = abiVersion();
            if (abi != EXPECTED_ABI_VERSION) {
                loadError = "ABI mismatch: native=" + abi + " expected=" + EXPECTED_ABI_VERSION;
                LOGGER.error("DLSS native {} — disabling DLSS.", loadError);
                return false;
            }
            loaded = true;
            return true;
        } catch (Throwable t) {
            loadError = "ABI check failed: " + t;
            LOGGER.error("DLSS native {} — disabling DLSS.", loadError);
            return false;
        }
    }

    private static Path locate() {
        // 1) explicit dev override
        String prop = System.getProperty("mcdlss.native.path");
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop);
            if (Files.isRegularFile(p)) return p;
            LOGGER.warn("mcdlss.native.path set but not a file: {}", prop);
        }

        String dll = mapLibraryName();
        Path gameDir = FabricLoader.getInstance().getGameDir();

        List<Path> candidates = List.of(
                gameDir.resolve("mcdlss").resolve(dll),
                gameDir.resolve(dll),
                // dev-loop: built straight from the CMake tree alongside the fork
                gameDir.resolve("../native/build/Release").resolve(dll).normalize(),
                gameDir.resolve("../native/build/Debug").resolve(dll).normalize(),
                gameDir.resolve("../../native/build/Release").resolve(dll).normalize()
        );
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) return c;
        }
        return null;
    }

    private static String mapLibraryName() {
        // On Windows: "mcdlss_native.dll"; keep cross-platform-friendly for a future Linux SR path.
        return System.mapLibraryName(LIB_NAME);
    }

    public static boolean isLoaded() { return loaded; }

    public static String getLoadError() { return loadError; }
}
