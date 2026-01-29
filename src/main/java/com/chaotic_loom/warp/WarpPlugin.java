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
import org.gradle.language.jvm.tasks.ProcessResources;

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

        // 1. Setup Repositories
        project.getRepositories().maven(repo -> {
            repo.setUrl("https://maven.fabricmc.net/");
        });
        project.getRepositories().mavenCentral();

        try {
            // 2. Download Loom and Dependencies
            Configuration config = project.getConfigurations().detachedConfiguration(
                    project.getDependencies().create(LOOM_DEPENDENCY)
            );
            config.setTransitive(true);
            Set<File> files = config.resolve();

            URL[] urls = files.stream()
                    .map(file -> {
                        try { return file.toURI().toURL(); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .toArray(URL[]::new);

            // 3. Determine the Parent ClassLoader
            // CRITICAL FIX: We must use the classloader that loaded WarpPlugin.
            // This ensures the 'Plugin' interface matches what Gradle expects.
            ClassLoader parentLoader = WarpPlugin.class.getClassLoader();
            Class<?> pluginClass = null;

            // 4. Try to Inject into the Current Loader (The "Perfect" Fix)
            // If we can add the URLs to the current loader, Gradle treats the classes as "known/managed".
            try {
                if (parentLoader instanceof URLClassLoader) {
                    java.lang.reflect.Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                    method.setAccessible(true);
                    for (URL url : urls) {
                        method.invoke(parentLoader, url);
                    }
                    // Load directly from our own loader
                    pluginClass = parentLoader.loadClass("net.fabricmc.loom.bootstrap.LoomGradlePluginBootstrap");
                    System.out.println("Orchestrator: Successfully injected Loom into the plugin ClassLoader.");
                }
            } catch (Throwable t) {
                // Java 16+ might block this due to module encapsulation.
                System.out.println("Orchestrator: Injection failed (Java 16+ restrictions), falling back to isolated loader.");
            }

            // 5. Fallback: Create a Child ClassLoader (The "Compatible" Fix)
            if (pluginClass == null) {
                // We use WarpPlugin's loader as parent to fix "Not a valid plugin" error.
                URLClassLoader loader = new URLClassLoader(urls, parentLoader);
                pluginClass = loader.loadClass("net.fabricmc.loom.bootstrap.LoomGradlePluginBootstrap");
            }

            // 6. Apply the Plugin
            // We apply the class object directly.
            project.getPluginManager().apply(pluginClass);

            System.out.println("Orchestrator: Loom applied. Configuring dependencies...");

            // 7. Add Minecraft Dependencies
            project.getDependencies().add("minecraft", "com.mojang:minecraft:1.20.1");
            project.getDependencies().add("mappings", "net.fabricmc:yarn:1.20.1+build.2:v2");
            project.getDependencies().add("modImplementation", "net.fabricmc:fabric-loader:0.16.10");

            // Define the variables to replace
            Map<String, Object> replacements = new HashMap<>();

            // Core Identity
            replacements.put("mod_id", "warptest");
            replacements.put("mod_name", "Warp Test");
            replacements.put("version", "1.0.0");

            // Metadata (The missing ones causing the crash)
            replacements.put("description", "A test mod for Warp.");
            replacements.put("mod_author", "Me");
            replacements.put("license", "MIT");
            replacements.put("java_version", "17");

            // Versions
            replacements.put("minecraft_version", "1.20.1");
            // Some templates use specific loader version keys too, adding them just in case:
            replacements.put("fabric_loader_version", "0.16.10");
            replacements.put("fabric_version", "0.91.0+1.20.1"); // Fabric API version if needed

            // Configure the ProcessResources task
            project.getTasks().named("processResources", ProcessResources.class, task -> {
                task.getInputs().properties(replacements);

                task.filesMatching("fabric.mod.json", file -> {
                    file.expand(replacements);
                });
            });
        } catch (Exception e) {
            throw new RuntimeException("Orchestrator failed to load Fabric Loom.", e);
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