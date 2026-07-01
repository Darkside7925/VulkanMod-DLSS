package net.kaiten.config;

import com.google.gson.*;
import net.kaiten.NativeBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Per-GPU settings profile system for Kaiten.
 *
 * <p>On startup, the active Vulkan device's GPU identity (name + PCI vendor/device ID)
 * is computed and used to auto-select the last-active profile. Multiple profiles per
 * GPU are supported (e.g. "Quality" vs "Competitive" on the same card).
 *
 * <p>Storage: {@code .minecraft/config/kaiten/kaiten-profiles.json} (schemaVersion 1).
 * Writes are atomic (temp file + rename). Corrupt files fall back to defaults.
 */
public final class KaitenConfig {
    private static final Logger LOGGER = LogManager.getLogger("Kaiten");
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final KaitenConfig INSTANCE = new KaitenConfig();

    // --- Global settings (cross-GPU, General tab) ---
    public boolean updateCheck = true;
    public int logVerbosity = 1;          // 0=Off, 1=Normal, 2=Verbose
    public boolean hudOverlay = false;

    // --- Active state ---
    private String activeGpuKey = null;
    private Profile activeProfile = null;
    private final Map<String, List<Profile>> profilesByGpu = new LinkedHashMap<>();

    private Path configDir;

    private KaitenConfig() {}

    /** Call once during client init, before any UI opens. */
    public void init(Path gameConfigDir) {
        this.configDir = gameConfigDir.resolve("kaiten");
        try { Files.createDirectories(configDir); } catch (IOException ignored) {}
        loadProfiles();
        loadGlobal();
    }

    // ========== GPU Identity ==========

    /** Compute the GPU identity key from a GPU name string.
     *  Sanitizes the name for use as a JSON key. */
    public static String computeGpuKey(String gpuName) {
        if (gpuName == null || gpuName.isBlank()) return "unknown-0";
        // Sanitize: lowercase, replace non-alphanumeric with dash, collapse dashes
        return gpuName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("-+", "-");
    }

    /** Called after the Vulkan device is created. Auto-selects the last-active profile. */
    public void onDeviceReady(String gpuName) {
        this.activeGpuKey = computeGpuKey(gpuName);
        List<Profile> profiles = profilesByGpu.computeIfAbsent(activeGpuKey, k -> new ArrayList<>());

        // Find last-active
        Profile lastActive = null;
        for (Profile p : profiles) {
            if (p.lastActive) { lastActive = p; break; }
        }

        if (lastActive != null) {
            this.activeProfile = lastActive;
            LOGGER.info("Kaiten: loaded profile '{}' for GPU '{}'", lastActive.name, activeGpuKey);
        } else if (!profiles.isEmpty()) {
            // No last-active marker — pick first and mark it
            this.activeProfile = profiles.get(0);
            this.activeProfile.lastActive = true;
            LOGGER.info("Kaiten: auto-selected first profile '{}' for GPU '{}'", activeProfile.name, activeGpuKey);
        } else {
            // No profiles at all — seed defaults
            this.activeProfile = Profile.createDefault(activeGpuKey);
            profiles.add(activeProfile);
            this.activeProfile.lastActive = true;
            LOGGER.info("Kaiten: created default profile '{}' for GPU '{}'", activeProfile.name, activeGpuKey);
        }

        // Apply the active profile's settings to the live system.
        applyActiveProfile();
    }

    // ========== Profile Management ==========

    public Profile getActiveProfile() { return activeProfile; }
    public String getActiveGpuKey() { return activeGpuKey; }
    public List<Profile> getProfiles(String gpuKey) {
        return profilesByGpu.getOrDefault(gpuKey, Collections.emptyList());
    }
    public Map<String, List<Profile>> getAllProfiles() { return Collections.unmodifiableMap(profilesByGpu); }

    public void switchToProfile(Profile p) {
        if (activeProfile != null) activeProfile.lastActive = false;
        p.lastActive = true;
        this.activeProfile = p;
        applyActiveProfile();
        saveProfiles();
    }

    public Profile createProfile(String name) {
        Profile p = (activeProfile != null)
                ? new Profile(activeProfile)  // duplicate current
                : Profile.createDefault(activeGpuKey);
        p.name = name;
        p.gpuKey = activeGpuKey;
        profilesByGpu.get(activeGpuKey).add(p);
        saveProfiles();
        return p;
    }

    public void deleteProfile(Profile p) {
        List<Profile> list = profilesByGpu.get(p.gpuKey);
        if (list == null || list.size() <= 1) return; // keep at least one
        list.remove(p);
        if (p == activeProfile) {
            activeProfile = list.get(0);
            activeProfile.lastActive = true;
            applyActiveProfile();
        }
        saveProfiles();
    }

    public void renameProfile(Profile p, String newName) {
        p.name = newName;
        saveProfiles();
    }

    // ========== Persistence ==========

    private void loadProfiles() {
        Path file = configDir.resolve("kaiten-profiles.json");
        if (!Files.exists(file)) return;
        try {
            String raw = Files.readString(file);
            JsonObject root = GSON.fromJson(raw, JsonObject.class);
            int version = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 0;
            if (version != SCHEMA_VERSION) {
                LOGGER.warn("Kaiten profile schema version mismatch (file={}, expected={}) — using defaults", version, SCHEMA_VERSION);
                return;
            }
            JsonObject gpus = root.getAsJsonObject("gpus");
            for (Map.Entry<String, JsonElement> e : gpus.entrySet()) {
                JsonArray arr = e.getValue().getAsJsonArray();
                List<Profile> list = new ArrayList<>();
                for (JsonElement el : arr) {
                    try {
                        list.add(GSON.fromJson(el, Profile.class));
                    } catch (JsonParseException ex) {
                        LOGGER.warn("Kaiten: skipping corrupt profile entry for GPU '{}': {}", e.getKey(), ex.getMessage());
                    }
                }
                if (!list.isEmpty()) profilesByGpu.put(e.getKey(), list);
            }
            LOGGER.info("Kaiten: loaded {} profiles across {} GPU(s)", profilesByGpu.values().stream().mapToInt(List::size).sum(), profilesByGpu.size());
        } catch (Exception ex) {
            LOGGER.warn("Kaiten: failed to load profiles, starting fresh. Reason: {}", ex.getMessage());
            // Don't crash — fall back to defaults.
        }
    }

    public void saveProfiles() {
        if (configDir == null) return;
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject gpus = new JsonObject();
        for (Map.Entry<String, List<Profile>> e : profilesByGpu.entrySet()) {
            JsonArray arr = new JsonArray();
            for (Profile p : e.getValue()) arr.add(GSON.toJsonTree(p));
            gpus.add(e.getKey(), arr);
        }
        root.add("gpus", gpus);
        atomicWrite(configDir.resolve("kaiten-profiles.json"), GSON.toJson(root));
    }

    private void loadGlobal() {
        Path file = configDir.resolve("kaiten-global.json");
        if (!Files.exists(file)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root.has("updateCheck")) updateCheck = root.get("updateCheck").getAsBoolean();
            if (root.has("logVerbosity")) logVerbosity = root.get("logVerbosity").getAsInt();
            if (root.has("hudOverlay")) hudOverlay = root.get("hudOverlay").getAsBoolean();
        } catch (Exception ex) {
            LOGGER.warn("Kaiten: failed to load global config — using defaults");
        }
    }

    public void saveGlobal() {
        if (configDir == null) return;
        JsonObject root = new JsonObject();
        root.addProperty("updateCheck", updateCheck);
        root.addProperty("logVerbosity", logVerbosity);
        root.addProperty("hudOverlay", hudOverlay);
        atomicWrite(configDir.resolve("kaiten-global.json"), GSON.toJson(root));
    }

    private static void atomicWrite(Path target, String json) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, json);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            LOGGER.error("Kaiten: failed to write config {}: {}", target, ex.getMessage());
        }
    }

    // ========== Apply ==========

    /** Push the active profile's settings into the live DLSS state. */
    private void applyActiveProfile() {
        if (activeProfile == null) return;
        try {
            String backend = activeProfile.backend != null ? activeProfile.backend : "dlss";

            // SR / FSR
            net.kaiten.DlssSuperResolution.enabled = activeProfile.dlssEnabled && "dlss".equals(backend);
            net.kaiten.KaitenFSR.enabled = activeProfile.dlssEnabled && "fsr".equals(backend);

            // Update upscaling render state (render resolution, low-res framebuffer)
            try {
                net.kaiten.KaitenRenderState.update(
                        net.minecraft.client.Minecraft.getInstance().getWindow().getWidth(),
                        net.minecraft.client.Minecraft.getInstance().getWindow().getHeight(),
                        activeProfile.dlssMode, backend);
            } catch (Throwable ignored) {}

            // FG
            if (activeProfile.fgEnabled && NativeBridge.frameGenSupported && NativeBridge.frameGenConfigured) {
                net.kaiten.DlssFrameGeneration.enabled = true;
                net.kaiten.DlssFrameGeneration.setMultiplier(activeProfile.fgMultiplier);
            } else {
                net.kaiten.DlssFrameGeneration.enabled = false;
                net.kaiten.DlssFrameGeneration.disable();
            }

            // Reflex
            NativeBridge.setupReflex(activeProfile.reflexMode);

            // Debug
            net.kaiten.DlssDebugOverlay.enabled = activeProfile.debugOverlay;

            LOGGER.info("Kaiten: applied profile '{}' (SR={}, FG={}x{}, Reflex={}, backend={})",
                    activeProfile.name, activeProfile.dlssEnabled,
                    activeProfile.fgEnabled ? activeProfile.fgMultiplier + 1 : 0,
                    activeProfile.reflexMode, backend);
        } catch (Throwable t) {
            LOGGER.warn("Kaiten: failed to apply profile: {}", t.toString());
        }
    }

    // ========== Profile Data Class ==========

    public static class Profile {
        public String name = "Default";
        public String gpuKey = "";
        public boolean lastActive = false;

        // DLSS Super Resolution
        public boolean dlssEnabled = true;
        public int dlssMode = NativeBridge.DLSS_DLAA;   // sl::DLSSMode
        public float sharpness = 0.35f;

        // Frame Generation
        public boolean fgEnabled = true;
        public int fgMultiplier = 1;   // 1=2x, 2=3x, 3=4x

        // Reflex
        public int reflexMode = NativeBridge.REFLEX_LOW_LATENCY;

        // Debug
        public boolean debugOverlay = false;

        // Backend
        public String backend = "dlss";   // "dlss" or "fsr"

        public Profile() {}

        /** Copy constructor (for duplicating). */
        public Profile(Profile other) {
            this.name = other.name + " (copy)";
            this.gpuKey = other.gpuKey;
            this.dlssEnabled = other.dlssEnabled;
            this.dlssMode = other.dlssMode;
            this.sharpness = other.sharpness;
            this.fgEnabled = other.fgEnabled;
            this.fgMultiplier = other.fgMultiplier;
            this.reflexMode = other.reflexMode;
            this.debugOverlay = other.debugOverlay;
            this.backend = other.backend;
        }

        /** Seed a sensible default profile for a given GPU. */
        public static Profile createDefault(String gpuKey) {
            Profile p = new Profile();
            p.name = "Default";
            p.gpuKey = gpuKey;
            p.dlssEnabled = NativeBridge.dlssSupported;
            p.fgEnabled = NativeBridge.frameGenSupported;
            p.fgMultiplier = 1;  // safe 2x default
            return p;
        }

        /** Active multiplier as a display string. */
        public String multiplierLabel() {
            if (!fgEnabled) return "Off";
            return (fgMultiplier + 1) + "x";
        }
    }
}
