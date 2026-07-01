package net.kaiten.config;

import net.kaiten.NativeBridge;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.ModSettingsRegistry;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Kaiten DLSS settings registered as a proper tab inside VulkanMod's
 * Video Settings screen (OptionPage system, no separate screen needed).
 */
public final class KaitenOptions {

    private KaitenOptions() {}

    public static void register() {
        var profile = KaitenConfig.INSTANCE.getActiveProfile();
        if (profile == null) return; // not yet initialized

        ModSettingsEntry entry = new ModSettingsEntry(
                Component.literal("Kaiten DLSS"),
                () -> net.minecraft.resources.Identifier.fromNamespaceAndPath("vulkanmod", "vlogo_transparent.png"),
                KaitenOptions::getOptionPages,
                () -> {
                    // Apply changes on "Apply" button press
                    var p = KaitenConfig.INSTANCE.getActiveProfile();
                    if (p != null) {
                        String backend = p.backend != null ? p.backend : "dlss";
                        net.kaiten.DlssSuperResolution.enabled = p.dlssEnabled && "dlss".equals(backend);
                        net.kaiten.KaitenFSR.enabled = p.dlssEnabled && "fsr".equals(backend);
                        // Update render resolution for DLSS/FSR upscaling
                        try {
                            var w = net.minecraft.client.Minecraft.getInstance().getWindow();
                            net.kaiten.KaitenRenderState.update(w.getWidth(), w.getHeight(), p.dlssMode, backend);
                        } catch (Throwable ignored) {}
                        if (p.fgEnabled) {
                            net.kaiten.DlssFrameGeneration.enabled = true;
                            net.kaiten.DlssFrameGeneration.setMultiplier(p.fgMultiplier);
                        } else {
                            net.kaiten.DlssFrameGeneration.enabled = false;
                        }
                        NativeBridge.setupReflex(p.reflexMode);
                        net.kaiten.DlssDebugOverlay.enabled = p.debugOverlay;
                    }
                    KaitenConfig.INSTANCE.saveProfiles();
                }
        );
        ModSettingsRegistry.INSTANCE.addModEntry(entry);
    }

    private static List<OptionPage> getOptionPages() {
        var p = KaitenConfig.INSTANCE.getActiveProfile();
        if (p == null) return List.of();

        List<OptionPage> pages = new ArrayList<>();

        // Page 1: DLSS
        pages.add(new OptionPage("DLSS", dlssOpts(p)));

        // Page 2: Frame Gen
        pages.add(new OptionPage("Frame Gen", fgOpts(p)));

        // Page 3: Reflex + Debug
        pages.add(new OptionPage("Reflex & Debug", reflexOpts(p)));

        // Page 4: GPU Info
        pages.add(new OptionPage("GPU", gpuOpts()));

        return pages;
    }

    // ---- DLSS Super Resolution ----

    private static OptionBlock[] dlssOpts(KaitenConfig.Profile p) {
        var srToggle = new SwitchOption(
                Component.literal("DLSS/FSR Super Resolution"),
                value -> { p.dlssEnabled = value; },
                () -> p.dlssEnabled)
                .setTooltip(v -> Component.literal("AI-powered upscaling and anti-aliasing (DLAA at native res)"));

        var backendOption = new CyclingOption<>(
                Component.literal("Backend"),
                new String[]{"DLSS", "FSR 1.0"},
                value -> {
                    p.backend = "DLSS".equals(value) ? "dlss" : "fsr";
                },
                () -> "dlss".equals(p.backend) ? "DLSS" : "FSR 1.0")
                .setTranslator(Component::literal)
                .setTooltip(v -> Component.literal("DLSS (NVIDIA only) or FSR 1.0 (any GPU)"));

        var modeOption = new CyclingOption<>(
                Component.literal("Quality Preset"),
                new String[]{"Ultra Performance", "Performance", "Balanced", "Quality", "Ultra Quality", "DLAA"},
                value -> {
                    p.dlssMode = switch (value) {
                        case "Ultra Performance" -> NativeBridge.DLSS_ULTRA_PERF;
                        case "Performance" -> NativeBridge.DLSS_PERF;
                        case "Balanced" -> NativeBridge.DLSS_BALANCED;
                        case "Quality" -> NativeBridge.DLSS_QUALITY;
                        case "Ultra Quality" -> NativeBridge.DLSS_ULTRA_QUALITY;
                        default -> NativeBridge.DLSS_DLAA;
                    };
                },
                () -> switch (p.dlssMode) {
                    case NativeBridge.DLSS_ULTRA_PERF -> "Ultra Performance";
                    case NativeBridge.DLSS_PERF -> "Performance";
                    case NativeBridge.DLSS_BALANCED -> "Balanced";
                    case NativeBridge.DLSS_QUALITY -> "Quality";
                    case NativeBridge.DLSS_ULTRA_QUALITY -> "Ultra Quality";
                    default -> "DLAA";
                })
                .setTranslator(Component::literal)
                .setTooltip(v -> Component.literal("Higher quality = better image, lower FPS boost"));

        return new OptionBlock[]{
                new OptionBlock("Super Resolution", new Option<?>[]{srToggle, backendOption, modeOption})
        };
    }

    // ---- Frame Generation ----

    private static OptionBlock[] fgOpts(KaitenConfig.Profile p) {
        var fgToggle = new SwitchOption(
                Component.literal("Frame Generation"),
                value -> {
                    p.fgEnabled = value;
                    if (value) {
                        net.kaiten.DlssFrameGeneration.enabled = true;
                        net.kaiten.DlssFrameGeneration.setMultiplier(p.fgMultiplier);
                    } else {
                        net.kaiten.DlssFrameGeneration.enabled = false;
                        net.kaiten.DlssFrameGeneration.disable();
                    }
                },
                () -> p.fgEnabled)
                .setTooltip(v -> Component.literal("AI frame interpolation - doubles/triples FPS" +
                        (NativeBridge.frameGenSupported ? "" : " (requires RTX 40+)")));

        // Dynamic multiplier options based on GPU capability
        int max = NativeBridge.frameGenMaxMultiplier;
        String[] multLabels = new String[Math.min(max, 3)];
        for (int i = 0; i < multLabels.length; i++) multLabels[i] = (i + 2) + "x";

        var multOption = new CyclingOption<>(
                Component.literal("Multiplier"),
                multLabels,
                value -> {
                    for (int i = 0; i < multLabels.length; i++) {
                        if (multLabels[i].equals(value)) { p.fgMultiplier = i + 1; break; }
                    }
                },
                () -> (p.fgMultiplier >= 1 && p.fgMultiplier - 1 < multLabels.length)
                        ? multLabels[p.fgMultiplier - 1] : "2x")
                .setTranslator(Component::literal)
                .setTooltip(v -> Component.literal("2x = double FPS, 3x/4x = MFG (Blackwell only). Max: " + (max + 1) + "x"));

        return new OptionBlock[]{
                new OptionBlock("Frame Generation", new Option<?>[]{fgToggle, multOption})
        };
    }

    // ---- Reflex + Debug ----

    private static OptionBlock[] reflexOpts(KaitenConfig.Profile p) {
        var reflexOption = new CyclingOption<>(
                Component.literal("Reflex Mode"),
                new String[]{"Off", "On", "On + Boost"},
                value -> {
                    p.reflexMode = switch (value) {
                        case "On" -> NativeBridge.REFLEX_LOW_LATENCY;
                        case "On + Boost" -> NativeBridge.REFLEX_LOW_LATENCY_BOOST;
                        default -> NativeBridge.REFLEX_OFF;
                    };
                    NativeBridge.setupReflex(p.reflexMode);
                },
                () -> switch (p.reflexMode) {
                    case NativeBridge.REFLEX_LOW_LATENCY -> "On";
                    case NativeBridge.REFLEX_LOW_LATENCY_BOOST -> "On + Boost";
                    default -> "Off";
                })
                .setTranslator(Component::literal)
                .setTooltip(v -> Component.literal("Reduces input latency. On+Boost for lowest latency"));

        var debugToggle = new SwitchOption(
                Component.literal("Debug Overlay"),
                value -> {
                    p.debugOverlay = value;
                    net.kaiten.DlssDebugOverlay.enabled = value;
                },
                () -> p.debugOverlay)
                .setTooltip(v -> Component.literal("Visualize motion vectors (green = moving, grey = still)"));

        return new OptionBlock[]{
                new OptionBlock("Reflex", new Option<?>[]{reflexOption}),
                new OptionBlock("Debug", new Option<?>[]{debugToggle})
        };
    }

    // ---- GPU Info (read-only-ish) ----

    private static OptionBlock[] gpuOpts() {
        String gpu = "Unknown";
        try {
            var d = net.vulkanmod.vulkan.device.DeviceManager.device;
            if (d != null) gpu = d.deviceName;
        } catch (Throwable ignored) {}

        // Use SwitchOption as read-only display (always disabled, shows state)
        var srSupported = new SwitchOption(
                Component.literal("DLSS SR"),
                v -> {},
                () -> NativeBridge.dlssSupported)
                .setTooltip(v -> Component.literal("Requires NVIDIA Turing (RTX 20) or newer"));

        var fgSupported = new SwitchOption(
                Component.literal("DLSS Frame Gen"),
                v -> {},
                () -> NativeBridge.frameGenSupported)
                .setTooltip(v -> Component.literal("Requires NVIDIA Ada (RTX 40) or newer"));

        var mfgSupported = new SwitchOption(
                Component.literal("Multi-Frame Gen"),
                v -> {},
                () -> NativeBridge.frameGenMaxMultiplier >= 2)
                .setTooltip(v -> Component.literal("3x/4x requires Blackwell (RTX 50). Max: "
                        + (NativeBridge.frameGenMaxMultiplier + 1) + "x"));

        var reflexSupported = new SwitchOption(
                Component.literal("Reflex"),
                v -> {},
                () -> NativeBridge.reflexSupported)
                .setTooltip(v -> Component.literal("NVIDIA Reflex low-latency technology"));

        return new OptionBlock[]{
                new OptionBlock("GPU: " + gpu, new Option<?>[]{
                        srSupported, fgSupported, mfgSupported, reflexSupported
                })
        };
    }
}
