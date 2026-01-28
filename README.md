# Warp - Multi-Loader Minecraft Mod Gradle Plugin

Warp is a Gradle plugin that manages multi-loader Minecraft mod development, handling all the complex Gradle configuration so you can focus on coding your mod.

## Quick Start

### `settings.gradle`

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id 'com.chaotic_loom.warp' version '0.1.0'
}

warp {
    minecraftVersion = "1.20.1"
    modId = "example"
    modGroup = "me.my_name"
}

...
```

That's it! Warp handles everything else.
