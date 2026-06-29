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
    public static final int EXPECTED_ABI_VERSION = 2;

    private static final String LIB_NAME = "mcdlss_native";

    // Streamline feature ids — must match sl_core_types.h.
    public static final int FEATURE_DLSS   = 0;     // Super Resolution
    public static final int FEATURE_REFLEX = 3;
    public static final int FEATURE_PCL    = 4;     // PC Latency
    public static final int FEATURE_DLSS_G = 1000;  // Frame Generation

    private static boolean loaded = false;
    private static String loadError = null;
    private static Path nativeDir = null;

    private static boolean streamlineInitialized = false;
    // Per-feature support, populated by reportFeatures().
    public static boolean dlssSupported = false;
    public static boolean frameGenSupported = false;
    public static boolean reflexSupported = false;

    private NativeBridge() {}

    // --- Phase 0 native methods (no Streamline dependency) ---

    /** Round-trips a string through native code; returns a native-built banner string. */
    public static native String hello(String from);

    /** Returns the native library's ABI version, validated against {@link #EXPECTED_ABI_VERSION}. */
    public static native int abiVersion();

    // --- Phase 1 native methods (Streamline) — only present when the DLL is built with Streamline ---

    /** slInit against Vulkan. pluginDir = folder holding sl.*.dll. Returns sl::Result (0 = eOk). */
    public static native int slInitNative(String pluginDir, int logLevel);

    /** slIsFeatureSupported for the given feature + VkPhysicalDevice handle (0 = no adapter). 0 = supported. */
    public static native int slIsFeatureSupportedNative(int feature, long vkPhysicalDevice);

    /** Human-readable slGetFeatureRequirements summary (driver/os/flags/queues) for the feature. */
    public static native String slFeatureRequirementsNative(int feature);

    /** Maps an sl::Result code to its enum name. */
    public static native String slResultNameNative(int code);

    /** slShutdown. Returns sl::Result (0 = eOk). */
    public static native int slShutdownNative();

    // --- Phase 3 (DLSS-SR) native methods ---

    /** slSetVulkanInfo — hand SL the Vulkan device (manual hooking). Returns sl::Result (0 = eOk). */
    public static native int slSetVulkanInfoNative(long instance, long physicalDevice, long device,
                                                   int gfxFamily, int gfxIndex, int cmpFamily, int cmpIndex);

    /** slDLSSGetOptimalSettings for the given output size + sl::DLSSMode; returns a formatted summary. */
    public static native String slDlssOptimalSettingsNative(int outputWidth, int outputHeight, int mode);

    /** Newline-joined Vulkan device extensions DLSS requires (from slGetFeatureRequirements). */
    public static native String slDlssDeviceExtensionsNative();
    /** Newline-joined Vulkan instance extensions DLSS requires. */
    public static native String slDlssInstanceExtensionsNative();
    /** Newline-joined Vulkan 1.2/1.3 feature names DLSS requires (diagnostic). */
    public static native String slDlssFeaturesNative();

    /** Device extensions DLSS needs, for injection into VulkanMod's vkCreateDevice. Empty if unavailable. */
    public static synchronized java.util.List<String> dlssDeviceExtensions() {
        if (!streamlineInitialized) return java.util.List.of();
        try {
            String s = slDlssDeviceExtensionsNative();
            if (s == null || s.isBlank()) return java.util.List.of();
            return java.util.List.of(s.split("\n"));
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    /** Instance extensions DLSS needs, for injection into vkCreateInstance. */
    public static synchronized java.util.List<String> dlssInstanceExtensions() {
        if (!streamlineInitialized) return java.util.List.of();
        try {
            String s = slDlssInstanceExtensionsNative();
            if (s == null || s.isBlank()) return java.util.List.of();
            return java.util.List.of(s.split("\n"));
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    // sl::DLSSMode values.
    public static final int DLSS_OFF = 0, DLSS_PERF = 1, DLSS_BALANCED = 2, DLSS_QUALITY = 3,
            DLSS_ULTRA_PERF = 4, DLSS_ULTRA_QUALITY = 5, DLSS_DLAA = 6;

    private static boolean vulkanInfoSet = false;

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
                nativeDir = explicit.toAbsolutePath().getParent();
                // Preload Streamline's interposer (if staged next to us) so the OS loader can
                // satisfy mcdlss_native.dll's import of the SL core API. Best-effort: a Phase 0
                // (Streamline-less) DLL has no such import and this is simply skipped.
                preloadIfPresent(nativeDir, "sl.interposer.dll");
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

    private static void preloadIfPresent(Path dir, String dll) {
        if (dir == null) return;
        Path p = dir.resolve(dll);
        if (Files.isRegularFile(p)) {
            try {
                System.load(p.toAbsolutePath().toString());
                LOGGER.info("Preloaded {}", p.getFileName());
            } catch (Throwable t) {
                LOGGER.warn("Preload of {} failed: {}", dll, t.toString());
            }
        }
    }

    // --- Phase 1: Streamline lifecycle (all best-effort; failure → plain VulkanMod) ---

    /** Initializes Streamline (slInit). Call early, before the Vulkan device is created. */
    public static synchronized void initStreamline() {
        if (!loaded || streamlineInitialized) return;
        try {
            String pluginDir = (nativeDir != null) ? nativeDir.toString() : "";
            int r = slInitNative(pluginDir, /* LogLevel.eDefault */ 1);
            if (r == 0) {
                streamlineInitialized = true;
                LOGGER.info("Streamline initialized (SDK 2.12.0, Vulkan).");
                try {
                    LOGGER.info("DLSS requires VK device extensions: {}", slDlssDeviceExtensionsNative().replace("\n", ", "));
                    LOGGER.info("DLSS requires VK instance extensions: {}", slDlssInstanceExtensionsNative().replace("\n", ", "));
                    LOGGER.info("DLSS requires VK 1.2/1.3 features: {}", slDlssFeaturesNative().replace("\n", ", "));
                } catch (Throwable ignored) {}
            } else {
                LOGGER.warn("Streamline init failed: {} — DLSS disabled.", resultName(r));
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("Native library built without Streamline — DLSS disabled.");
        } catch (Throwable t) {
            LOGGER.warn("Streamline init error: {} — DLSS disabled.", t.toString());
        }
    }

    /**
     * Queries and logs which DLSS features the current adapter supports.
     * Call after the Vulkan device exists, passing VkPhysicalDevice.address().
     */
    public static synchronized void reportFeatures(long vkPhysicalDevice) {
        if (!streamlineInitialized) return;
        try {
            dlssSupported     = checkFeature("DLSS Super Resolution", FEATURE_DLSS,   vkPhysicalDevice);
            reflexSupported   = checkFeature("Reflex",                FEATURE_REFLEX, vkPhysicalDevice);
            frameGenSupported = checkFeature("DLSS Frame Generation", FEATURE_DLSS_G, vkPhysicalDevice);
            checkFeature("PC Latency (PCL)", FEATURE_PCL, vkPhysicalDevice);
            LOGGER.info("DLSS feature report — Super Resolution: {}, Frame Generation: {}, Reflex: {}",
                    yesNo(dlssSupported), yesNo(frameGenSupported), yesNo(reflexSupported));
        } catch (Throwable t) {
            LOGGER.warn("DLSS feature query failed: {}", t.toString());
        }
    }

    /**
     * Hand Streamline the Vulkan device (required before any DLSS feature function) and log
     * the optimal render resolutions per quality preset. Called once after device creation.
     */
    public static synchronized void setupDlssDevice(long instance, long physicalDevice, long device,
                                                    int gfxFamily, int gfxIndex, int cmpFamily, int cmpIndex) {
        if (!streamlineInitialized || vulkanInfoSet) return;
        try {
            int r = slSetVulkanInfoNative(instance, physicalDevice, device, gfxFamily, gfxIndex, cmpFamily, cmpIndex);
            if (r != 0) {
                LOGGER.warn("slSetVulkanInfo failed: {} — DLSS evaluate unavailable.", resultName(r));
                return;
            }
            vulkanInfoSet = true;
            LOGGER.info("Streamline Vulkan device set (manual hooking).");
        } catch (Throwable t) {
            LOGGER.warn("slSetVulkanInfo error: {}", t.toString());
            return;
        }

        if (!dlssSupported) return;
        try {
            com.mojang.blaze3d.platform.Window w = net.minecraft.client.Minecraft.getInstance().getWindow();
            int ow = w.getWidth(), oh = w.getHeight();
            LOGGER.info("DLSS optimal render resolutions for output {}x{}:", ow, oh);
            logDlssMode("DLAA       ", DLSS_DLAA, ow, oh);
            logDlssMode("Quality    ", DLSS_QUALITY, ow, oh);
            logDlssMode("Balanced   ", DLSS_BALANCED, ow, oh);
            logDlssMode("Performance", DLSS_PERF, ow, oh);
            logDlssMode("UltraPerf  ", DLSS_ULTRA_PERF, ow, oh);
        } catch (Throwable t) {
            LOGGER.warn("DLSS optimal-settings query failed: {}", t.toString());
        }
    }

    private static void logDlssMode(String label, int mode, int ow, int oh) {
        try {
            LOGGER.info("  {} -> {}", label, slDlssOptimalSettingsNative(ow, oh, mode));
        } catch (Throwable t) {
            LOGGER.warn("  {} -> query error: {}", label, t.toString());
        }
    }

    private static boolean checkFeature(String name, int feature, long physDev) {
        int r = slIsFeatureSupportedNative(feature, physDev);
        boolean ok = (r == 0);
        String req = requirements(feature);
        if (ok) LOGGER.info("  {}: SUPPORTED   [{}]", name, req);
        else    LOGGER.info("  {}: unavailable ({})   [{}]", name, resultName(r), req);
        return ok;
    }

    /** Shuts Streamline down (slShutdown). Call on game shutdown. */
    public static synchronized void shutdownStreamline() {
        if (!streamlineInitialized) return;
        try {
            int r = slShutdownNative();
            LOGGER.info("Streamline shutdown: {}", resultName(r));
        } catch (Throwable t) {
            LOGGER.warn("Streamline shutdown error: {}", t.toString());
        } finally {
            streamlineInitialized = false;
        }
    }

    private static String resultName(int code) {
        try { return slResultNameNative(code); } catch (Throwable t) { return "code=" + code; }
    }

    private static String requirements(int feature) {
        try { return slFeatureRequirementsNative(feature); } catch (Throwable t) { return "n/a"; }
    }

    private static String yesNo(boolean b) { return b ? "yes" : "no"; }

    public static boolean isLoaded() { return loaded; }

    public static boolean isStreamlineInitialized() { return streamlineInitialized; }

    public static String getLoadError() { return loadError; }
}
