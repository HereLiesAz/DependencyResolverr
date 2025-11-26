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

data class Artifact(
    val groupId: String,
    val artifactId: String,
    var version: String = "",
    var repository: Repository? = null,
    var extension: String = "jar"
) {
    var mavenMetadata: MavenMetadata? = null
    var dependencies: List<Artifact>? = null
    var pom: ProjectObjectModel? = null

    fun showDependencyTree(depth: Int = 0) {
        println("    ".repeat(depth) + this)
        dependencies?.forEach { dep ->
            dep.showDependencyTree(depth + 1)
        }
    }

    fun downloadTo(output: File) {
        if (repository == null) {
            throw IllegalStateException("Repository is not declared for $groupId:$artifactId:$version during downloadTo.")
        }
        output.parentFile?.mkdirs() 
        output.createNewFile()
        val dependencyUrl = "${repository!!.getURL()}/${
            groupId.replace(
                ".", "/"
            )
        }/$artifactId/$version/$artifactId-$version.$extension"
        eventReciever.onDownloadStart(this)
        val request = Request.Builder().url(dependencyUrl).build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code ${'$'}{response.code} for $dependencyUrl")
                response.body.byteStream().use { input ->
                    output.outputStream().use { input.copyTo(it) }
                }
            }
            eventReciever.onDownloadEnd(this)
        } catch (e: Exception) {
            eventReciever.onDownloadError(this, e)
        }
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
        return "$groupId:$artifactId:$version"
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + artifactId.hashCode()
        result = 31 * result + version.hashCode() // Version included for uniqueness in sets like visitedInThisCall
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Artifact

        if (groupId != other.groupId) return false
        if (artifactId != other.artifactId) return false
        if (version != other.version) return false // Version included

        return true
    }
}
