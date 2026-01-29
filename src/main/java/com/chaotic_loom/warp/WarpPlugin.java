package com.chaotic_loom.warp;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.artifacts.Configuration;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Method;
import java.util.*;

public class WarpPlugin implements Plugin<Settings> {
    private static final Logger LOGGER = Logging.getLogger(WarpPlugin.class);
    private static final String LOOM_DEPENDENCY = "net.fabricmc:fabric-loom:1.9-SNAPSHOT";

    @Override
    public void apply(Settings settings) {
        configurePluginResolution(settings);

        WarpExtension extension = settings.getExtensions().create("warp", WarpExtension.class);

        settings.getGradle().settingsEvaluated(s -> {
            extension.applyDefaults();

            String mc = extension.getMinecraftVersion().getOrElse("");
            if (mc.isEmpty()) return;

            MinecraftVersion current = new MinecraftVersion(mc);
            Map<String, String> tokens = createTokens(extension);
            boolean hasConfig = tokens != null;

            s.include("common");
            if (hasConfig) ModuleGenerator.generate(s.getRootDir(), "common", tokens);

            boolean enableFabric = extension.getFabricVersion().isPresent();
            boolean enableForge = extension.getForgeVersion().isPresent();
            boolean enableNeoForge = extension.getNeoForgeVersion().isPresent() && current.compareTo(new MinecraftVersion("1.20.1")) >= 0;

            ModuleGenerator.manageModule(settings, "fabric", enableFabric, hasConfig, tokens);
            ModuleGenerator.manageModule(settings, "forge", enableForge, hasConfig, tokens);
            ModuleGenerator.manageModule(settings, "neoforge", enableNeoForge, hasConfig, tokens);
        });

        settings.getGradle().beforeProject(project -> {
            if (project.getName().equals("common")) {
                configureCommon(project, extension);
            }
            if (project.getName().equals("fabric")) {
                configureFabricModule(project, extension);
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
        settings.getPluginManagement().getRepositories().maven(repo -> {
            repo.setName("Parchment");
            repo.setUrl("https://maven.parchmentmc.org");
        });
    }

    // --- SHARED LOOM INJECTION LOGIC ---
    private void injectLoom(Project project) {
        System.out.println("Orchestrator: Injecting Fabric Loom into " + project.getName() + "...");

        project.getRepositories().maven(repo -> repo.setUrl("https://maven.fabricmc.net/"));
        project.getRepositories().maven(repo -> repo.setUrl("https://maven.parchmentmc.org"));
        project.getRepositories().mavenCentral();

        try {
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

            ClassLoader parentLoader = WarpPlugin.class.getClassLoader();
            Class<?> pluginClass = null;

            try {
                if (parentLoader instanceof URLClassLoader) {
                    Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                    method.setAccessible(true);
                    for (URL url : urls) method.invoke(parentLoader, url);
                    pluginClass = parentLoader.loadClass("net.fabricmc.loom.bootstrap.LoomGradlePluginBootstrap");
                }
            } catch (Throwable t) {
                // Fallback handled below
            }

            if (pluginClass == null) {
                URLClassLoader loader = new URLClassLoader(urls, parentLoader);
                pluginClass = loader.loadClass("net.fabricmc.loom.bootstrap.LoomGradlePluginBootstrap");
            }

            project.getPluginManager().apply(pluginClass);

        } catch (Exception e) {
            throw new RuntimeException("Orchestrator failed to load Fabric Loom for " + project.getName(), e);
        }
    }

    // --- MODULE CONFIGURATIONS ---

    private void configureCommon(Project project, WarpExtension extension) {
        injectLoom(project);

        project.getDependencies().add("minecraft", "com.mojang:minecraft:" + extension.getMinecraftVersion().get());

        applyParchmentMappings(project, extension);

        project.getDependencies().add("implementation", "com.google.code.findbugs:jsr305:3.0.2");

        System.out.println("Orchestrator: Configured 'common' with Minecraft & Parchment.");
    }

    private void configureFabricModule(Project project, WarpExtension extension) {
        project.getLogger().lifecycle("Orchestrator: Injecting Fabric Loom into " + project.getName() + "...");

        // 1. Apply Fabric Loom
        injectLoom(project);

        // 2. Add Minecraft Dependency
        project.getDependencies().add("minecraft", "com.mojang:minecraft:" + extension.getMinecraftVersion().get());

        // 3. Apply Mappings (Mojang + Parchment)
        applyParchmentMappings(project, extension);

        // 4. Add Fabric Loader
        project.getDependencies().add("modImplementation", "net.fabricmc:fabric-loader:" + extension.getFabricLoaderVersion().get());

        // 5. Depend on Common Project
        Project common = project.getRootProject().findProject(":common");
        if (common != null) {
            Map<String, String> depConfig = new HashMap<>();
            depConfig.put("path", ":common");
            depConfig.put("configuration", "namedElements");

            project.getDependencies().add("implementation", project.getDependencies().project(depConfig));
        }

        // 6. Configure Resource Processing (RE-ADDED THIS LINE)
        configureResourceProcessing(project, extension);
    }

    // --- HELPER METHODS ---

    private void applyParchmentMappings(Project project, WarpExtension extension) {
        String mcVersion = extension.getMinecraftVersion().get();
        String parchmentRaw = extension.getParchmentVersion().getOrElse("2023.09.03");

        // Handle "1.20.1:2023.09.03" -> "2023.09.03"
        String parchmentDate = parchmentRaw.contains(":")
                ? parchmentRaw.substring(parchmentRaw.lastIndexOf(":") + 1)
                : parchmentRaw;

        String parchmentDep = "org.parchmentmc.data:parchment-" + mcVersion + ":" + parchmentDate + "@zip";

        try {
            Object loom = project.getExtensions().getByName("loom");

            // Define the Action
            Action<Object> layerAction = spec -> {
                try {
                    Class<?> specClass = spec.getClass();

                    // 1. Invoke officialMojangMappings()
                    Method officialMethod = specClass.getMethod("officialMojangMappings");
                    officialMethod.invoke(spec);

                    // 2. Invoke parchment(String)
                    Arrays.stream(specClass.getMethods())
                            .filter(m -> m.getName().equals("parchment") && m.getParameterCount() == 1)
                            .findFirst()
                            .ifPresent(m -> {
                                try { m.invoke(spec, parchmentDep); }
                                catch (Exception e) { throw new RuntimeException(e); }
                            });

                } catch (Exception e) {
                    throw new RuntimeException("Reflection failed inside loom.layered", e);
                }
            };

            // Invoke loom.layered(Action)
            Method layeredMethod = Arrays.stream(loom.getClass().getMethods())
                    .filter(m -> m.getName().equals("layered") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == Action.class)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Could not find loom.layered(Action) method"));

            Object layeredDependency = layeredMethod.invoke(loom, layerAction);
            project.getDependencies().add("mappings", layeredDependency);

        } catch (Exception e) {
            throw new RuntimeException("Failed to apply Parchment mappings", e);
        }
    }

    private void configureResourceProcessing(Project project, WarpExtension extension) {
        Map<String, Object> replacements = new HashMap<>();

        replacements.put("mod_id", extension.getModId().get());
        replacements.put("mod_name", extension.getModName().getOrElse("Warp Mod"));
        replacements.put("version", extension.getModVersion().getOrElse("1.0.0"));
        replacements.put("group", extension.getModGroup().get());
        replacements.put("java_version", "17");

        replacements.put("description", extension.getDescription().getOrElse(""));

        String author = extension.getModAuthor().getOrElse("");
        replacements.put("author", author);
        replacements.put("mod_author", author);

        replacements.put("license", extension.getLicense().getOrElse(""));

        replacements.put("minecraft_version", extension.getMinecraftVersion().get());
        replacements.put("fabric_loader_version", extension.getFabricLoaderVersion().getOrElse("0.16.9"));

        project.getTasks().named("processResources", ProcessResources.class, task -> {
            task.getInputs().properties(replacements);
            task.filesMatching(Arrays.asList("fabric.mod.json", "*.mixins.json"), file -> {
                file.expand(replacements);
            });
        });
    }

    private Map<String, String> createTokens(WarpExtension ext) {
        String group = ext.getModGroup().getOrNull();
        String modId = ext.getModId().getOrNull();
        if (group == null || modId == null) return null;
        Map<String, String> tokens = new HashMap<>();
        tokens.put("MOD_ID", modId);
        tokens.put("GROUP", group);
        tokens.put("PACKAGE", group + "." + modId);
        return tokens;
    }

    private void configureRootProject(Project rootProject, WarpExtension extension) {
        rootProject.subprojects(sub -> {
            if (isWarpModule(sub.getName())) {
                sub.getPluginManager().apply("java");
            }
        });
    }

    private boolean isWarpModule(String name) {
        return name.equals("common") || name.equals("fabric") || name.equals("forge") || name.equals("neoforge");
    }
}