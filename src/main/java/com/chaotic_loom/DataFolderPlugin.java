package com.chaotic_loom;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import java.io.File;

public class DataFolderPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        // This ensures the folder is created when the project is evaluated
        project.afterEvaluate(p -> {
            File dataFolder = new File(p.getProjectDir(), "data");
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
                p.getLogger().lifecycle("Successfully created the 'data' folder!");
            }
        });
    }
}