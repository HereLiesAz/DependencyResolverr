/*
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Cosmic IDE. If not, see <https://www.gnu.org/licenses/>.
 */

package org.cosmic.ide.dependency.resolver

import org.cosmic.ide.dependency.resolver.api.Artifact
import org.cosmic.ide.dependency.resolver.api.Resolver
import org.cosmic.ide.dependency.resolver.api.ProjectObjectModel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.collections.ArrayDeque

class MavenResolver : Resolver {
    override suspend fun resolve(projectDir: File): List<Artifact> {
        val pomFile = File(projectDir, "pom.xml")
        if (!pomFile.exists()) {
            return emptyList()
        }

        val pom = xmlDeserializer.readValue(pomFile, ProjectObjectModel::class.java)
        val rootArtifact = Artifact(requireNotNull(pom.groupId), pom.artifactId, requireNotNull(pom.version))

        val resolved = ConcurrentHashMap<Pair<String, String>, Pair<Artifact, ConcurrentLinkedDeque<Artifact>>>()
        val managedDependencies = ConcurrentLinkedDeque<Artifact>()

        // Manually resolve the direct dependencies from the project's pom.xml
        val directDependencies = pom.resolveDependencies(resolved, managedDependencies)
        rootArtifact.dependencies = directDependencies.toList()

        // Now, for each direct dependency, resolve its entire dependency tree
        directDependencies.parallelForEach { dependency ->
            resolveDependencyTree(dependency, resolved, managedDependencies)
        }

        // Collect all unique dependencies and resolve version conflicts
        val allDependencies = getAllDependencies(rootArtifact).toList()
        return allDependencies
    }

    private fun getAllDependencies(artifact: Artifact): Set<Artifact> {
        val allResolvedArtifactsInGraph = mutableSetOf<Artifact>()
        val queue = ArrayDeque<Artifact>()

        // Start BFS from the direct dependencies of the current artifact
        artifact.dependencies?.forEach { queue.add(it) }

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
        for (art in allResolvedArtifactsInGraph) {
            val key = Pair(art.groupId, art.artifactId)
            val existing = newestArtifactsMap[key]
            if (existing == null || io.github.g00fy2.versioncompare.Version(art.version).isHigherThan(existing.version)) {
                newestArtifactsMap[key] = art
            }
        }
        return newestArtifactsMap.values.toSet()
    }

    private suspend fun resolve(
        artifact: Artifact,
        resolved: ConcurrentHashMap<Pair<String, String>, Pair<Artifact, ConcurrentLinkedDeque<Artifact>>>,
        managedDependencies: ConcurrentLinkedDeque<Artifact>
    ) {
        if (artifact.dependencies != null) {
            eventReciever.onSkippingResolution(artifact)
            return
        }

        val key = Pair(artifact.groupId, artifact.artifactId)
        val cachedEntry = resolved[key]

        if (cachedEntry != null) {
            val cachedArtifactInstance = cachedEntry.first
            val cachedDependencies = cachedEntry.second

            if (io.github.g00fy2.versioncompare.Version(artifact.version).isLowerThan(cachedArtifactInstance.version)) {
                artifact.dependencies = emptyList()
                eventReciever.onSkippingResolution(artifact)
                eventReciever.logger.info("Skipping $artifact - older than cached version ${cachedArtifactInstance.version}")
                return
            } else if (artifact.version == cachedArtifactInstance.version) {
                artifact.dependencies = cachedDependencies.toList() // Reuse dependencies
                eventReciever.onSkippingResolution(artifact)
                eventReciever.logger.info("Skipping $artifact - same as cached version, reusing dependencies")
                return
            }
            eventReciever.logger.info("Proceeding with $artifact - newer than cached version ${cachedArtifactInstance.version}")
        }

        if (artifact.repository == null) {
            initHost(artifact)
            if (artifact.repository == null) {
                artifact.dependencies = emptyList()
                resolved[key] = Pair(artifact, ConcurrentLinkedDeque()) // Cache unresolvable state
                throw IllegalStateException("Repository is not declared for ${artifact.groupId}:${artifact.artifactId}:${artifact.version} and could not be initialized.")
            }
        }

        val pomFile = artifact.getPOM()
        if (pomFile == null) {
            artifact.dependencies = emptyList()
            resolved[key] = Pair(artifact, ConcurrentLinkedDeque()) // Cache no POM/deps state
            eventReciever.onInvalidPOM(artifact)
            return
        }

        val directDependencies = pomFile.resolveDependencies(resolved, managedDependencies)
        artifact.dependencies = directDependencies.toList()
        if (artifact.dependencies?.isEmpty() == true) {
            eventReciever.onDependenciesNotFound(artifact)
        }
        resolved[key] = Pair(artifact, directDependencies) // Update cache with this version
        eventReciever.onResolutionComplete(artifact)
    }

    private suspend fun resolveDependencyTree(
        artifact: Artifact,
        resolved: ConcurrentHashMap<Pair<String, String>, Pair<Artifact, ConcurrentLinkedDeque<Artifact>>> = ConcurrentHashMap(),
        managedDependencies: ConcurrentLinkedDeque<Artifact> = ConcurrentLinkedDeque(),
        resolutionStack: MutableList<Pair<String, String>> = mutableListOf()
    ) {
        val currentKey = Pair(artifact.groupId, artifact.artifactId)
        if (resolutionStack.contains(currentKey)) {
            eventReciever.logger.warning("Cycle detected in dependency graph: ${resolutionStack.joinToString(" -> ")} -> $currentKey. Skipping resolution for $artifact to break the cycle.")
            artifact.dependencies = emptyList()
            return
        }

        resolutionStack.add(currentKey)

        val queue = ArrayDeque<Artifact>()
        queue.add(artifact)

        val visitedInThisCall = mutableSetOf<Artifact>()
        visitedInThisCall.add(artifact)

        while (queue.isNotEmpty()) {
            val currentLevelArtifacts = mutableListOf<Artifact>()
            while(queue.isNotEmpty()) {
                currentLevelArtifacts.add(queue.removeFirst())
            }

            currentLevelArtifacts.filter { it.dependencies == null }.parallelForEach { art ->
                resolve(art, resolved, managedDependencies)
            }

            for (art in currentLevelArtifacts) {
                art.dependencies?.forEach { dependency ->
                    if (visitedInThisCall.add(dependency)) {
                        queue.add(dependency)
                    }
                }
            }
        }
        resolutionStack.remove(currentKey)
    }
}
