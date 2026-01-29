package com.chaotic_loom.warp;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;

import org.gradle.api.artifacts.Configuration;
import java.io.File;
import java.util.Set;

public class WarpPlugin implements Plugin<Settings> {
    private static final Logger LOGGER = Logging.getLogger(WarpPlugin.class);
    private static final String LOOM_DEPENDENCY = "net.fabricmc:fabric-loom:1.9-SNAPSHOT";

    @Override
    public void apply(Settings settings) {
        configurePluginResolution(settings);

        // Create the Extension
        WarpExtension extension = settings.getExtensions().create("warp", WarpExtension.class);

        settings.getGradle().settingsEvaluated(s -> {
            extension.applyDefaults();

            String mc = extension.getMinecraftVersion().getOrElse("");
            if (mc.isEmpty())
                return;

            MinecraftVersion current = new MinecraftVersion(mc);

            Map<String, String> tokens = createTokens(extension);
            boolean hasConfig = tokens != null;

            s.include("common");
            if (hasConfig) {
                ModuleGenerator.generate(s.getRootDir(), "common", tokens);
            } else {
                LOGGER.lifecycle("Warp: Skipping file generation for 'common', modId or modGroup not set.");
            }

            boolean enableFabric = extension.getFabricVersion().isPresent();
            boolean enableForge = extension.getForgeVersion().isPresent();
            boolean enableNeoForge = extension.getNeoForgeVersion().isPresent() && current.compareTo(new MinecraftVersion("1.20.1")) >= 0;

            ModuleGenerator.manageModule(settings, "fabric", enableFabric, hasConfig, tokens);
            ModuleGenerator.manageModule(settings, "forge", enableForge, hasConfig, tokens);
            ModuleGenerator.manageModule(settings, "neoforge", enableNeoForge, hasConfig, tokens);
        });

        settings.getGradle().beforeProject(project -> {
            LOGGER.lifecycle("Project found: " + project.getName());
            if (project.getName().equals("fabric")) {
                configureFabricModule(project);
            }
        });

        settings.getGradle().projectsLoaded(gradle -> {
            Project root = gradle.getRootProject();
            root.getExtensions().add("warp", extension);
            configureRootProject(root, extension);
        });
    }

    private void configurePluginResolution(Settings settings) {
        settings.getPluginManagement().getRepositories().maven(repo -> {
            repo.setName("Fabric");
            repo.setUrl("https://maven.fabricmc.net/");
        });
    }

    private void configureFabricModule(Project project) {
        System.out.println("Orchestrator: Injecting Fabric Loom into " + project.getName() + "...");

        // 2. Define where to download Loom from (for the Project context)
        project.getRepositories().maven(repo -> {
            repo.setUrl("https://maven.fabricmc.net/");
        });
        project.getRepositories().mavenCentral();

        try {
            // 3. Create a Detached Configuration to resolve the artifact
            // This downloads the plugin JAR + dependencies without polluting the project
            Configuration config = project.getConfigurations().detachedConfiguration(
                    project.getDependencies().create(LOOM_DEPENDENCY)
            );
            config.setTransitive(true); // Must download dependencies (Gson, Commons IO, etc.)

            // 4. Resolve the files
            Set<File> files = config.resolve();

            // 5. Create a clean ClassLoader
            // Parent is 'buildscript' loader so Loom can see Gradle classes
            URL[] urls = files.stream()
                    .map(file -> {
                        try { return file.toURI().toURL(); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .toArray(URL[]::new);

            URLClassLoader loader = new URLClassLoader(urls, project.getBuildscript().getClassLoader());

            // 6. Load the BOOTSTRAP class
            // Loom uses a bootstrap class to initialize.
            // The class 'net.fabricmc.loom.LoomGradlePlugin' is internal.
            Class<?> pluginClass = loader.loadClass("net.fabricmc.loom.bootstrap.LoomGradlePluginBootstrap");

            // 7. Apply the plugin
            project.getPluginManager().apply(pluginClass);

            System.out.println("Orchestrator: Loom applied. Configuring dependencies...");

            // 8. CRITICAL: Add the required Minecraft dependencies
            // Since Loom is now applied, the "minecraft", "mappings", etc. configurations exist.

            // Add Minecraft (Adjust version as needed or read from extension)
            project.getDependencies().add("minecraft", "com.mojang:minecraft:1.20.1");

            // Add Mappings
            project.getDependencies().add("mappings", "net.fabricmc:yarn:1.20.1+build.2:v2");

            // Add Fabric Loader
            project.getDependencies().add("modImplementation", "net.fabricmc:fabric-loader:0.16.9");

        } catch (Exception e) {
            throw new RuntimeException("Orchestrator failed to load Fabric Loom. \n" +
                    "If you are on Gradle 8.14+, ensure you are using a compatible Loom version (1.9-SNAPSHOT+).", e);
        }
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