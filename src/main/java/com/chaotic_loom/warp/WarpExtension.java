package com.chaotic_loom.warp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.gradle.api.provider.Property;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Map;

public abstract class WarpExtension {
    public abstract Property<String> getMinecraftVersion();

    public abstract Property<String> getFabricVersion();
    public abstract Property<String> getForgeVersion();
    public abstract Property<String> getNeoForgeVersion();
    public abstract Property<String> getParchmentVersion();

    public abstract Property<String> getModId();
    public abstract Property<String> getModGroup();
    public abstract Property<String> getModVersion();
    public abstract Property<String> getModName();

    // Metadata
    public abstract Property<String> getLicense();
    public abstract Property<String> getCredits();
    public abstract Property<String> getModAuthor();
    public abstract Property<String> getDescription();

    // Version ranges
    public abstract Property<String> getMinecraftVersionRange();
    public abstract Property<String> getForgeLoaderVersionRange();
    public abstract Property<String> getNeoForgeLoaderVersionRange();
    public abstract Property<String> getFabricLoaderVersion();
    public abstract Property<String> getJavaVersion();

    public void applyDefaults() {
        String mc = getMinecraftVersion().getOrElse("");
        if (mc.isEmpty())
            return;

        Gson gson = new Gson();
        try (Reader reader = new InputStreamReader(getClass().getResourceAsStream("/versions.json"))) {
            Map<String, Map<String, String>> data = gson.fromJson(reader, new TypeToken<Map<String, Map<String, String>>>() {}.getType());
            Map<String, String> defaults = data.get(mc);

            if (defaults != null) {
                // Loader versions
                applyIfMissing(getFabricVersion(), defaults.get("fabric"));
                applyIfMissing(getForgeVersion(), defaults.get("forge"));
                applyIfMissing(getNeoForgeVersion(), defaults.get("neoforge"));

                // Mappings
                applyIfMissing(getParchmentVersion(), defaults.get("parchment"));

                // Loader-specific versions
                applyIfMissing(getFabricLoaderVersion(), defaults.get("fabric_loader"));

                // Version ranges
                applyIfMissing(getMinecraftVersionRange(), defaults.get("minecraft_range"));
                applyIfMissing(getForgeLoaderVersionRange(), defaults.get("forge_loader_range"));
                applyIfMissing(getNeoForgeLoaderVersionRange(), defaults.get("neoforge_loader_range"));

                // Java version
                applyIfMissing(getJavaVersion(), defaults.get("java_version"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Warp: Failed to load default version data.", e);
        }
    }

    private void applyIfMissing(Property<String> property, String defaultValue) {
        if (!property.isPresent() && defaultValue != null) {
            property.set(defaultValue);
        }
    }
}