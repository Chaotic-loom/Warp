package com.chaotic_loom.warp;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.tasks.Jar;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import java.io.File;
import java.util.*;

public class WarpPlugin implements Plugin<Project> {
    @Override
    public void apply(Project rootProject) {
        // Create the Extension
        WarpExtension extension = rootProject.getExtensions().create("warp", WarpExtension.class);

        // Configure subprojects
        // This runs during the configuration phase. If the folders exist and are
        // in settings.gradle, this will turn them into Java projects.
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

        // Files Generation
        rootProject.afterEvaluate(p -> {
            extension.applyDefaults();
            generateFiles(p, extension);
        });
    }

    private boolean isWarpModule(String name) {
        return name.equals("common") || name.equals("fabric") || name.equals("forge") || name.equals("neoforge");
    }

    private void generateFiles(Project root, WarpExtension ext) {
        String mc = ext.getMinecraftVersion().getOrElse("");
        String group = ext.getModGroup().getOrNull();
        String modId = ext.getModId().getOrNull();

        if (group == null || modId == null)
            return; // Wait for user config

        Map<String, String> tokens = new HashMap<>();
        tokens.put("MOD_ID", modId);
        tokens.put("GROUP", group);
        tokens.put("PACKAGE", group);

        ModuleGenerator.generate(root, "common", tokens);

        boolean enableFabric = ext.getFabricVersion().isPresent();
        boolean enableForge = ext.getForgeVersion().isPresent();

        MinecraftVersion current = new MinecraftVersion(mc);
        boolean enableNeoForge = ext.getNeoForgeVersion().isPresent()
                && current.compareTo(new MinecraftVersion("1.20.1")) >= 0;

        manageModule(root, "fabric", enableFabric, tokens);
        manageModule(root, "forge", enableForge, tokens);
        manageModule(root, "neoforge", enableNeoForge, tokens);
    }

    private void manageModule(Project root, String moduleName, boolean shouldExist, Map<String, String> tokens) {
        File moduleDir = root.file(moduleName);
        if (shouldExist) {
            if (!moduleDir.exists())
                ModuleGenerator.generate(root, moduleName, tokens);
        } else {
            Project sub = root.findProject(":" + moduleName);
            if (sub != null) {
                root.getLogger().lifecycle("Warp: Disabling module '" + moduleName + "'");
                sub.getTasks().configureEach(task -> task.setEnabled(false));
            }
        }
    }
}