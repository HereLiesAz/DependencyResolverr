/*
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Cosmic IDE. If not, see <https://www.gnu.org/licenses/>.
 */

package org.cosmic.ide.dependency.resolver.api

import io.github.g00fy2.versioncompare.Version
import okhttp3.Request
import org.cosmic.ide.dependency.resolver.eventReciever
import org.cosmic.ide.dependency.resolver.okHttpClient
import org.cosmic.ide.dependency.resolver.resolveDependencies
import org.cosmic.ide.dependency.resolver.xmlDeserializer
import org.cosmic.ide.dependency.resolver.parallelForEach
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.collections.ArrayDeque

/**
 * Represents a Maven artifact.
 *
 * @property groupId The group ID of the artifact.
 * @property artifactId The artifact ID of the artifact.
 * @property version The version of the artifact.
 * @property classifier The classifier of the artifact (e.g., "sources", "javadoc").
 * @property repository The repository where the artifact is located.
 * @property extension The extension of the artifact (e.g., "jar", "pom").
 */
data class Artifact(
    val groupId: String,
    val artifactId: String,
    var version: String = "",
    var classifier: String? = null,
    var repository: Repository? = null,
    var extension: String = "jar"
) {
    var mavenMetadata: MavenMetadata? = null
    var dependencies: List<Artifact>? = null
    var pom: ProjectObjectModel? = null

    @Deprecated("Use downloadArtifacts(output, artifacts) instead", ReplaceWith("downloadArtifacts(output, getAllDependencies())"))
    suspend fun downloadArtifact(output: File) {
        output.mkdirs()
        // Ensure dependencies are resolved and consolidated before downloading
        val allArtifactsToDownload = (getAllDependencies() + this).toSet()

        allArtifactsToDownload.forEach { artifact ->
            val artifactFile = File(output, "${artifact.artifactId}-${artifact.version}.${artifact.extension}")
            if (!artifactFile.exists()) {
                artifact.downloadTo(artifactFile)
            }
        }
    }

    @Deprecated("Use resolveDependencies(projectDir) instead")
    suspend fun getAllDependencies(): Set<Artifact> {
        if (this.dependencies == null) { // Ensure the dependency tree is resolved for the starting artifact
            resolveDependencyTree()
        }

        val allResolvedArtifactsInGraph = mutableSetOf<Artifact>()
        val queue = ArrayDeque<Artifact>()

        // Start BFS from the direct dependencies of the current artifact
        this.dependencies?.forEach { queue.add(it) }

        val visitedForTraversal = mutableSetOf<Artifact>() // Prevents cycles during full graph traversal

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (visitedForTraversal.add(current)) { // Relies on Artifact's equals/hashCode (including version)
                allResolvedArtifactsInGraph.add(current)
                current.dependencies?.forEach { dep ->
                    if (!visitedForTraversal.contains(dep)) {
                        queue.add(dep)
                    }
                }
            }
        }

        // Now, consolidate to the newest version for each groupId:artifactId
        val newestArtifactsMap = mutableMapOf<Pair<String, String>, Artifact>()
        for (artifact in allResolvedArtifactsInGraph) {
            val key = Pair(artifact.groupId, artifact.artifactId)
            val existing = newestArtifactsMap[key]
            if (existing == null || Version(artifact.version).isHigherThan(existing.version)) {
                newestArtifactsMap[key] = artifact
            }
        }
        return newestArtifactsMap.values.toSet()
    }

    fun showDependencyTree(depth: Int = 0) {
        println("    ".repeat(depth) + this)
        dependencies?.forEach { dep ->
            dep.showDependencyTree(depth + 1)
        }
    }

    @Deprecated("Use resolveDependencies(projectDir) instead")
    suspend fun resolve(
        resolved: ConcurrentHashMap<Pair<String, String>, Pair<Artifact, ConcurrentLinkedDeque<Artifact>>>,
        managedDependencies: ConcurrentLinkedDeque<Artifact>
    ) {
        if (this.dependencies != null) {
            eventReciever.onSkippingResolution(this)
            return
        }

        val key = Pair(groupId, artifactId)
        val cachedEntry = resolved[key]

        if (cachedEntry != null) {
            val cachedArtifactInstance = cachedEntry.first
            val cachedDependencies = cachedEntry.second
            
            if (Version(this.version).isLowerThan(cachedArtifactInstance.version)) {
                this.dependencies = emptyList()
                eventReciever.onSkippingResolution(this)
                eventReciever.logger.info("Skipping $this - older than cached version ${cachedArtifactInstance.version}")
                return
            } else if (this.version == cachedArtifactInstance.version) {
                this.dependencies = cachedDependencies.toList() // Reuse dependencies
                eventReciever.onSkippingResolution(this)
                eventReciever.logger.info("Skipping $this - same as cached version, reusing dependencies")
                return
            }
            // If current version is newer (comparisonResult > 0), proceed to resolve it.
            // The cache will be updated with this newer version.
            eventReciever.logger.info("Proceeding with $this - newer than cached version ${cachedArtifactInstance.version}")
        }

        if (repository == null) {
            org.cosmic.ide.dependency.resolver.initHost(this)
            if (repository == null) {
                this.dependencies = emptyList()
                resolved[key] = Pair(this, ConcurrentLinkedDeque()) // Cache unresolvable state
                throw IllegalStateException("Repository is not declared for $groupId:$artifactId:$version and could not be initialized.")
            }
        }
        
        val pomFile = getPOM()
        if (pomFile == null) {
            this.dependencies = emptyList()
            resolved[key] = Pair(this, ConcurrentLinkedDeque()) // Cache no POM/deps state
            eventReciever.onInvalidPOM(this)
            return
        }

        val directDependencies = pomFile.resolveDependencies(resolved, managedDependencies)
        this.dependencies = directDependencies.toList()
        if (this.dependencies?.isEmpty() == true) {
            eventReciever.onDependenciesNotFound(this)
        }
        resolved[key] = Pair(this, directDependencies) // Update cache with this version
        eventReciever.onResolutionComplete(this)
    }

    @Deprecated("Use resolveDependencies(projectDir) instead")
    suspend fun resolveDependencyTree(
        resolved: ConcurrentHashMap<Pair<String, String>, Pair<Artifact, ConcurrentLinkedDeque<Artifact>>> = ConcurrentHashMap(),
        managedDependencies: ConcurrentLinkedDeque<Artifact> = ConcurrentLinkedDeque(),
        resolutionStack: MutableList<Pair<String, String>> = mutableListOf()
    ) {
        val currentKey = Pair(this.groupId, this.artifactId)
        if (resolutionStack.contains(currentKey)) {
            eventReciever.logger.warning("Cycle detected in dependency graph: ${resolutionStack.joinToString(" -> ")} -> $currentKey. Skipping resolution for $this to break the cycle.")
            this.dependencies = emptyList()
            return
        }

        resolutionStack.add(currentKey)

        val queue = ArrayDeque<Artifact>()
        queue.add(this)

        val visitedInThisCall = mutableSetOf<Artifact>()
        visitedInThisCall.add(this)

        while (queue.isNotEmpty()) {
            val currentLevelArtifacts = mutableListOf<Artifact>()
            while(queue.isNotEmpty()) {
                currentLevelArtifacts.add(queue.removeFirst())
            }

            currentLevelArtifacts.filter { it.dependencies == null }.parallelForEach { artifact ->
                artifact.resolve(resolved, managedDependencies)
            }

            for (artifact in currentLevelArtifacts) {
                artifact.dependencies?.forEach { dependency ->
                    if (visitedInThisCall.add(dependency)) {
                        queue.add(dependency)
                    }
                }
            }
        }
        resolutionStack.remove(currentKey)
    }

    fun getDownloadUrl(): String {
        if (repository == null) {
            throw IllegalStateException("Repository is not declared for $groupId:$artifactId:$version during URL construction.")
        }
        val classifierString = if (!classifier.isNullOrEmpty()) "-$classifier" else ""
        return "${repository!!.getURL()}/${
            groupId.replace(
                ".", "/"
            )
        }/$artifactId/$version/$artifactId-$version$classifierString.$extension"
    }

    fun downloadTo(output: File) {
        output.parentFile?.mkdirs()
        output.createNewFile()
        val dependencyUrl = getDownloadUrl()
        eventReciever.onDownloadStart(this)
        val request = Request.Builder().url(dependencyUrl).build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    eventReciever.onDownloadError(this, IOException("Failed to download $dependencyUrl: ${response.code}"))
                    return
                }
                response.body.byteStream().use { input ->
                    output.outputStream().use { input.copyTo(it) }
                }
            }
            eventReciever.onDownloadEnd(this)
        } catch (e: IOException) {
            eventReciever.onDownloadError(this, e)
        }
    }

    /**
     * Gets the local path of the artifact.
     *
     * @param outputDir The directory where the artifact will be downloaded.
     * @return The local path of the artifact.
     */
    fun getLocalPath(outputDir: File): File {
        val classifierString = if (!classifier.isNullOrEmpty()) "-$classifier" else ""
        return File(outputDir, "$artifactId-$version$classifierString.$extension")
    }

    // Note: getPOM is not suspend, but it calls initHost which might do network I/O implicitly.
    // Consider if getPOM should be suspend if initHost itself becomes suspend or makes blocking calls that should be suspend.
    fun getPOM(): ProjectObjectModel? {
        if (pom != null) { 
            return pom
        }
        if (repository == null) {
            org.cosmic.ide.dependency.resolver.initHost(this) // This can do network I/O
            if (repository == null) {
                eventReciever.onInvalidPOM(this)
                return null 
            }
        }
        if (version.isEmpty()) {
            eventReciever.onInvalidPOM(this)
            return null
        }
        val pomUrl = "${repository?.getURL()}/${
            groupId.replace(
                ".", "/"
            )
        }/$artifactId/$version/$artifactId-$version.pom"

        val request = Request.Builder().url(pomUrl).build()
        try {
            val response = okHttpClient.newCall(request).execute() // Blocking network call
            if (!response.isSuccessful) {
                eventReciever.onVersionNotFound(this)
                return null
            }
            this.pom = xmlDeserializer.readValue(
                response.body.byteStream(),
                ProjectObjectModel::class.java
            )
            return this.pom
        } catch (_: SocketException) {
            eventReciever.onVersionNotFound(this)
            return null
        } catch (_: IOException) {
            eventReciever.onInvalidPOM(this)
            return null
        } catch (_: Exception) {
            eventReciever.onInvalidPOM(this)
            return null
        }
    }

    override fun toString(): String {
        val classifierString = if (classifier != null) ":$classifier" else ""
        return "$groupId:$artifactId:$version$classifierString"
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + artifactId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + (classifier?.hashCode() ?: 0)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Artifact

        if (groupId != other.groupId) return false
        if (artifactId != other.artifactId) return false
        if (version != other.version) return false
        if (classifier != other.classifier) return false

        return true
    }
}
