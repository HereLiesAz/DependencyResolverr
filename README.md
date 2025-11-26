# DependencyResolverr
[![](https://jitpack.io/v/HereLiesAz/DependencyResolverr.svg)](https://jitpack.io/#HereLiesAz/DependencyResolverr)

A powerful and lightweight dependency resolution library for Maven and Gradle projects.

This library provides a simple yet flexible API to resolve and download artifacts and their transitive dependencies from various repositories, including Maven Central, Google Maven, and Jitpack. It's designed to be a lightweight alternative to more complex dependency management tools, making it ideal for use in build tools, plugins, and other development utilities.

## Features

*   **Support for Maven and Gradle:** Resolve dependencies from both `pom.xml` and `build.gradle`/`build.gradle.kts` files.
*   **Transitive Dependency Resolution:** Automatically resolves all transitive dependencies for a given artifact, saving you the hassle of manually specifying each one.
*   **Version Conflict Resolution:** Intelligently handles version conflicts by selecting the newest compatible version of each dependency.
*   **Cycle Detection:** Protects against infinite loops by detecting and breaking cyclical dependencies in the dependency graph.
*   **Extensible API:** A clean and modern API that's easy to use and extend.

```
dependencies {
	        implementation("com.github.HereLiesAz:DependencyResolverr:Tag")
	}
```

## Usage

### Resolving Dependencies

The library provides a single entry point for resolving dependencies from both Maven and Gradle projects. Simply provide the project directory, and the library will automatically detect the project type and resolve the dependencies.

```kotlin
import org.cosmic.ide.dependency.resolver.resolveDependencies
import java.io.File

suspend fun main() {
    val projectDir = File(".")
    val dependencies = resolveDependencies(projectDir)
    dependencies.forEach { println(it) }
}
```

### Downloading Artifacts

Once you've resolved the dependencies, you can easily download them to a specified directory using the `downloadArtifacts` utility function.

```kotlin
import org.cosmic.ide.dependency.resolver.downloadArtifacts
import org.cosmic.ide.dependency.resolver.resolveDependencies
import java.io.File

suspend fun main() {
    val projectDir = File(".")
    val outputDir = File("libs")

    val dependencies = resolveDependencies(projectDir)
    downloadArtifacts(outputDir, dependencies)
}
```

## API Overview

*   `resolveDependencies(projectDir: File): List<Artifact>`: The main entry point for resolving dependencies. It takes a project directory as input and returns a list of resolved `Artifact` objects.
*   `downloadArtifacts(output: File, artifacts: List<Artifact>)`: A utility function for downloading a list of artifacts to a specified directory.
*   `Artifact`: A data class that represents a resolved dependency, containing information such as the group ID, artifact ID, version, and repository.

## Deprecated API

The old API is still available for backward compatibility, but it is recommended that new projects use the new `resolveDependencies` function. The old API is marked as deprecated and may be removed in a future release.

### Old API Usage

```kotlin
import org.cosmic.ide.dependency.resolver.getArtifact
import java.io.File

suspend fun main() {
    val artifact = getArtifact("com.google.guava", "guava", "31.1-jre")
    artifact?.resolveDependencyTree()
    val dependencies = artifact?.getAllDependencies()
    val dir = File("test")
    dir.deleteRecursively()
    dir.mkdir()
    artifact?.downloadArtifact(dir)
}
```

## API Overview

*   `resolveDependencies(projectDir: File): List<Artifact>`: The main entry point for resolving dependencies. It takes a project directory as input and returns a list of resolved `Artifact` objects.
*   `downloadArtifacts(output: File, artifacts: List<Artifact>)`: A utility function for downloading a list of artifacts to a specified directory.
*   `Artifact`: A data class that represents a resolved dependency, containing information such as the group ID, artifact ID, version, and repository.
