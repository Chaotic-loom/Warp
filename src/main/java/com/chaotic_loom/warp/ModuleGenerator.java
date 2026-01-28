package com.chaotic_loom.warp;

import org.gradle.api.Project;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.CodeSource;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ModuleGenerator {
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([A-Z0-9_]+)\\s*\\}\\}");

    public static void generate(Project rootProject, String moduleName, Map<String, String> tokens) {
        File moduleDir = rootProject.file(moduleName);
        if (moduleDir.exists()) return; // Safety: Never overwrite existing user modules

        rootProject.getLogger().lifecycle("Warp: Scaffolding missing module '" + moduleName + "'...");
        moduleDir.mkdirs();

        try {
            copyTemplates(rootProject, moduleName, tokens);
        } catch (Exception e) {
            throw new RuntimeException("Failed to scaffold module: " + moduleName, e);
        }

        rootProject.getLogger().lifecycle("Warp: Created " + moduleName + ". PLEASE RE-SYNC GRADLE!");
    }

    private static void copyTemplates(Project project, String moduleName, Map<String, String> tokens) throws IOException {
        // Find the JAR location of this plugin
        CodeSource src = ModuleGenerator.class.getProtectionDomain().getCodeSource();
        if (src == null) return;

        URL jar = src.getLocation();
        ZipInputStream zip = new ZipInputStream(jar.openStream());

        String prefix = "templates/" + moduleName + "/";

        while (true) {
            ZipEntry e = zip.getNextEntry();
            if (e == null) break;

            String path = e.getName();

            // Files inside templates/<moduleName>/
            if (path.startsWith(prefix) && !e.isDirectory()) {

                // Calculate Relative Path (remove "templates/fabric/")
                String relativePath = path.substring(prefix.length());

                // Handle Dynamic Paths (__package__ -> com/chaotic_loom/warp)
                if (relativePath.contains("__package__")) {
                    String packagePath = tokens.get("GROUP").replace(".", "/");
                    relativePath = relativePath.replace("__package__", packagePath);
                }
                if (relativePath.contains("__mod_id__")) {
                    relativePath = relativePath.replace("__mod_id__", tokens.get("MOD_ID"));
                }

                // Handle File Extension (remove .txt)
                if (relativePath.endsWith(".txt")) {
                    relativePath = relativePath.substring(0, relativePath.length() - 4);
                }

                // Create Target File
                File targetFile = new File(project.file(moduleName), relativePath);
                targetFile.getParentFile().mkdirs();

                // Read, Replace, Write
                processAndWriteFile(zip, targetFile, tokens);
            }
        }
    }

    private static void processAndWriteFile(InputStream sourceStream, File target, Map<String, String> tokens) throws IOException {
        // Read the template
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int nRead;
        while ((nRead = sourceStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        String content = buffer.toString(StandardCharsets.UTF_8);

        // Replace {{ VAR }} using Regex
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1); // The text inside {{ }}
            String value = tokens.getOrDefault(key, matcher.group(0)); // Default to original if missing
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);

        // Write to disk
        Files.writeString(target.toPath(), sb.toString());
    }
}