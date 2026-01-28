# Warp - Multi-Loader Minecraft Mod Gradle Plugin

Warp is a Gradle plugin that manages multi-loader Minecraft mod development, handling all the complex Gradle configuration so you can focus on coding your mod.

## Quick Start

### 1. Root `build.gradle`

```groovy
plugins {
    id 'com.chaotic_loom.warp' version '0.1.0'
}

warp {
    minecraftVersion = "1.20.1"
    modId = "my_mod"
    modGroup = "com.example"
    modVersion = "1.0.0"
    
    // Optional
    modName = "My Awesome Mod"
    modAuthor = "YourName"
    description = "An awesome mod"
    license = "MIT"
}
```

### 2. `settings.gradle`

```groovy
...

['common', 'fabric', 'forge', 'neoforge'].each { name ->
    if (file(name).exists()) {
        include(name)
    }
}
```

### 3. Subproject `build.gradle` (e.g., `fabric/build.gradle`)

```groovy
dependencies {
    // Add any loader-specific dependencies here
}
```

That's it! Warp handles everything else.
