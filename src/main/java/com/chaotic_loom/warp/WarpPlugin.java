package com.chaotic_loom.warp;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class WarpPlugin implements Plugin<Settings> {
    private static final Logger LOGGER = Logging.getLogger(WarpPlugin.class);

    @Override
    public void apply(Settings settings) {
        // Create the Extension
        WarpExtension extension = settings.getExtensions().create("warp", WarpExtension.class);

        settings.getGradle().settingsEvaluated(s -> {
            extension.applyDefaults();

            String mc = extension.getMinecraftVersion().getOrElse("");
            if (mc.isEmpty())
                return;

            Map<String, String> tokens = createTokens(extension);
            boolean hasConfig = tokens != null;

            s.include("common");
            if (hasConfig) {
                ModuleGenerator.generate(s.getRootDir(), LOGGER, "common", tokens);
            } else {
                LOGGER.lifecycle("Warp: Skipping file generation for 'common', modId or modGroup not set.");
            }

            boolean enableFabric = extension.getFabricVersion().isPresent();
            boolean enableForge = extension.getForgeVersion().isPresent();

            MinecraftVersion current = new MinecraftVersion(mc);
            boolean enableNeoForge = extension.getNeoForgeVersion().isPresent()
                    && current.compareTo(new MinecraftVersion("1.20.1")) >= 0;

            if (enableFabric) {
                s.include("fabric");
                if (hasConfig)
                    ModuleGenerator.generate(s.getRootDir(), LOGGER, "fabric", tokens);
            }
            if (enableForge) {
                s.include("forge");
                if (hasConfig)
                    ModuleGenerator.generate(s.getRootDir(), LOGGER, "forge", tokens);
            }
            if (enableNeoForge) {
                s.include("neoforge");
                if (hasConfig)
                    ModuleGenerator.generate(s.getRootDir(), LOGGER, "neoforge", tokens);
            }
        });

        settings.getGradle().projectsLoaded(gradle -> {
            Project rootProject = gradle.getRootProject();
            rootProject.getExtensions().add("warp", extension);
            configureRootProject(rootProject, extension);
        });
    }

    private Map<String, String> createTokens(WarpExtension ext) {
        String group = ext.getModGroup().getOrNull();
        String modId = ext.getModId().getOrNull();

        if (group == null || modId == null)
            return null;

        Map<String, String> tokens = new HashMap<>();
        tokens.put("MOD_ID", modId);
        tokens.put("GROUP", group);
        tokens.put("PACKAGE", group);

        return tokens;
    }

    private void configureRootProject(Project rootProject, WarpExtension extension) {
        // Configure subprojects
        rootProject.subprojects(sub -> {
            String name = sub.getName();

            // Only configure modules managed by Warp
            if (isWarpModule(name)) {
                // Apply Java Plugin so src/main/java is recognized
                sub.getPluginManager().apply("java");

                // Set up basic dependencies
                sub.afterEvaluate(p -> {
                    // Make platform modules depend on Common
                    if (!name.equals("common")) {
                        Project common = rootProject.findProject(":common");
                        if (common != null) {
                            sub.getDependencies().add("implementation", common);
                        }
                    }
                });
            }
        });
    }

    private boolean isWarpModule(String name) {
        return name.equals("common") || name.equals("fabric") || name.equals("forge") || name.equals("neoforge");
    }
}