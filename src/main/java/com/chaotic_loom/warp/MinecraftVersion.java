package com.chaotic_loom.warp;

import org.jetbrains.annotations.NotNull;

public class MinecraftVersion implements Comparable<MinecraftVersion> {
    private final int[] parts;
    private final boolean isYearBased;

    public MinecraftVersion(String versionStr) {
        String[] split = versionStr.split("\\.");
        this.parts = new int[split.length];

        for (int i = 0; i < split.length; i++) {
            this.parts[i] = Integer.parseInt(split[i]);
        }

        // Minecraft's transition: If the first number is >= 25,
        // it's the new Year.Update system.
        this.isYearBased = this.parts[0] >= 25;
    }

    @Override
    public int compareTo(@NotNull MinecraftVersion other) {
        // Handle the era transition first
        if (this.isYearBased && !other.isYearBased) return 1;  // This is newer
        if (!this.isYearBased && other.isYearBased) return -1; // This is older

        // Compare individual segments
        int maxLength = Math.max(this.parts.length, other.parts.length);
        for (int i = 0; i < maxLength; i++) {
            int v1 = i < this.parts.length ? this.parts[i] : 0;
            int v2 = i < other.parts.length ? other.parts[i] : 0;

            if (v1 != v2) {
                return v1 > v2 ? 1 : -1;
            }
        }
        return 0; // They are equal
    }

    @Override
    public String toString() {
        return String.join(".", java.util.Arrays.stream(parts)
                .mapToObj(String::valueOf).toArray(String[]::new));
    }
}