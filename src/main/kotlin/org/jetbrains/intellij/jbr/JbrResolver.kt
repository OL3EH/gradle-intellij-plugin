package org.jetbrains.intellij.jbr

import org.gradle.api.Incubating
import org.gradle.internal.jvm.Jvm
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.intellij.IntelliJPluginConstants
import org.jetbrains.intellij.Version
import org.jetbrains.intellij.debug
import org.jetbrains.intellij.ifNull
import org.jetbrains.intellij.utils.ArchiveUtils
import org.jetbrains.intellij.utils.DependenciesDownloader
import org.jetbrains.intellij.utils.create
import org.jetbrains.intellij.utils.ivyRepository
import org.jetbrains.intellij.warn
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.nio.file.Path
import java.util.Properties
import javax.inject.Inject

@Incubating
open class JbrResolver @Inject constructor(
    private val jreRepository: String,
    private val isOffline: Boolean,
    private val archiveUtils: ArchiveUtils,
    private val dependenciesDownloader: DependenciesDownloader,
    private val context: String?,
) {

    private val operatingSystem = OperatingSystem.current()

    fun resolveRuntimeDir(
        runtimeDir: String? = null,
        jbrVersion: String? = null,
        ideDir: File? = null,
        validate: (executable: String) -> Boolean = { true }
    ): String? {
        debug(context, "Resolving runtime directory.")

        val jbrPath = when (OperatingSystem.current().isMacOsX) {
            true -> "jbr/Contents/Home"
            false -> "jbr"
        }

        return listOf(
            {
                runtimeDir?.let { path ->
                    path
                        .let { File(it).resolve(jbrPath).resolve("bin/java").takeIf(File::exists)?.canonicalPath }
                        .also { debug(context, "Runtime specified with runtimeDir='$path' resolved as: $it") }
                        .ifNull { warn(context, "Cannot resolve runtime with runtimeDir='$path'") }
                }
            },
            {
                jbrVersion?.let { version ->
                    resolve(version)
                        ?.javaExecutable
                        .also { debug(context, "Runtime specified with jbrVersion='$version' resolved as: $it") }
                        .ifNull { warn(context, "Cannot resolve runtime with jbrVersion='$version'") }
                }
            },
            {
                ideDir?.let { file ->
                    file
                        .let { file.resolve(jbrPath).resolve("bin/java").takeIf(File::exists)?.canonicalPath }
                        .also { debug(context, "Runtime specified with ideDir='$file' resolved as: $it") }
                }
            },
            {
                ideDir?.let { file ->
                    getBuiltinJbrVersion(file)
                        ?.let { version ->
                            resolve(version)
                                ?.javaExecutable
                                .also { debug(context, "Runtime specified with ideDir='$file', version='version' resolved as: $it") }
                                .ifNull { warn(context, "Cannot resolve runtime with ideDir='$file', version='version'") }
                        }
                        .ifNull { warn(context, "Cannot resolve runtime with ideDir='$file'") }
                }
            },
            {
                Jvm.current()
                    .javaExecutable
                    .canonicalPath
                    .also { debug(context, "Using current JVM: $it") }
            },
        )
            .asSequence()
            .mapNotNull { it()?.takeIf(validate) }
            .firstOrNull()
            ?.also { debug(context, "Resolved JVM Runtime directory: $it") }
    }

    fun resolve(version: String?): Jbr? {
        if (version.isNullOrEmpty()) {
            return null
        }
        val jbrArtifact = JbrArtifact.from(
            ("8".takeIf { version.startsWith('u') } ?: "") + version,
            operatingSystem,
        )

        return getJavaArchive(jbrArtifact)?.let {
            val javaDir = File(it.path.replaceAfter(jbrArtifact.name, "")).resolve("extracted")
            archiveUtils.extract(it, javaDir, context)
            fromDir(javaDir, version)
        }
    }

    private fun fromDir(javaDir: File, version: String): Jbr? {
        val javaExecutable = findJavaExecutable(javaDir)
        if (javaExecutable == null) {
            warn(context, "Cannot find java executable in: $javaDir")
            return null
        }
        return Jbr(version, javaDir, javaExecutable.toFile().absolutePath)
    }

    private fun getJavaArchive(jbrArtifact: JbrArtifact): File? {
        if (isOffline) {
            warn(context, "Cannot download JetBrains Java Runtime '${jbrArtifact.name}'. Gradle runs in offline mode.")
            return null
        }

        val url = jreRepository.takeIf { it.isNotEmpty() } ?: jbrArtifact.repositoryUrl

        return try {
            dependenciesDownloader.downloadFromRepository(context, {
                create(
                    group = "com.jetbrains",
                    name = "jbre",
                    version = jbrArtifact.name,
                    extension = "tar.gz",
                )
            }, {
                ivyRepository(url, "[revision].tar.gz")
            }).first()
        } catch (e: Exception) {
            warn(context, "Cannot download JetBrains Java Runtime '${jbrArtifact.name}'", e)
            null
        }
    }

    private fun findJavaExecutable(javaHome: File): Path? {
        val root = getJbrRoot(javaHome)
        val jre = File(root, "jre")
        val java = File(
            jre.takeIf { it.exists() } ?: root,
            "bin/java" + (".exe".takeIf { operatingSystem.isWindows } ?: "")
        )
        return java.toPath().takeIf { java.exists() }
    }

    private fun getJbrRoot(javaHome: File): File {
        val jbr = javaHome.listFiles()?.firstOrNull { it.name == "jbr" || it.name == "jbrsdk" }
        if (jbr != null && jbr.exists()) {
            return when (operatingSystem.isMacOsX) {
                true -> File(jbr, "Contents/Home")
                false -> jbr
            }
        }
        return File(javaHome, when (operatingSystem.isMacOsX) {
            true -> "jdk/Contents/Home"
            false -> ""
        })
    }

    private class JbrArtifact(val name: String, val repositoryUrl: String) {

        companion object {
            fun from(version: String, operatingSystem: OperatingSystem): JbrArtifact {
                var prefix = getPrefix(version)
                val lastIndexOfB = version.lastIndexOf('b')
                val majorVersion = when (lastIndexOfB > -1) {
                    true -> version.substring(prefix.length, lastIndexOfB)
                    false -> version.substring(prefix.length)
                }
                val buildNumberString = when (lastIndexOfB > -1) {
                    true -> version.substring(lastIndexOfB + 1)
                    else -> ""
                }
                val buildNumber = Version.parse(buildNumberString)
                val isJava8 = majorVersion.startsWith('8')
                val repositoryUrl = IntelliJPluginConstants.DEFAULT_JBR_REPOSITORY

                val oldFormat = prefix == "jbrex" || isJava8 && buildNumber < Version.parse("1483.24")
                if (oldFormat) {
                    return JbrArtifact("jbrex${majorVersion}b${buildNumberString}_${platform(operatingSystem)}_${arch(false)}",
                        repositoryUrl)
                }

                if (prefix.isEmpty()) {
                    prefix = when {
                        isJava8 -> "jbrx-"
                        buildNumber < Version.parse("1319.6") -> "jbr-"
                        else -> "jbr_jcef-"
                    }
                }
                return JbrArtifact("$prefix${majorVersion}-${platform(operatingSystem)}-${arch(isJava8)}-b${buildNumberString}",
                    repositoryUrl)
            }

            private fun getPrefix(version: String) = when {
                version.startsWith("jbrsdk-") -> "jbrsdk-"
                version.startsWith("jbr_jcef-") -> "jbr_jcef-"
                version.startsWith("jbr-") -> "jbr-"
                version.startsWith("jbrx-") -> "jbrx-"
                version.startsWith("jbrex8") -> "jbrex"
                else -> ""
            }

            private fun platform(operatingSystem: OperatingSystem) = when {
                operatingSystem.isWindows -> "windows"
                operatingSystem.isMacOsX -> "osx"
                else -> "linux"
            }

            private fun arch(newFormat: Boolean): String {
                val arch = System.getProperty("os.arch")
                if ("aarch64" == arch || "arm64" == arch) {
                    return "aarch64"
                }
                if ("x86_64" == arch || "amd64" == arch) {
                    return "x64"
                }
                val name = System.getProperty("os.name")
                if (name.contains("Windows") && System.getenv("ProgramFiles(x86)") != null) {
                    return "x64"
                }
                return when (newFormat) {
                    true -> "i586"
                    false -> "x86"
                }
            }
        }
    }

    private fun getBuiltinJbrVersion(ideDirectory: File): String? {
        val dependenciesFile = File(ideDirectory, "dependencies.txt")
        if (dependenciesFile.exists()) {
            val properties = Properties()
            val reader = FileReader(dependenciesFile)
            try {
                properties.load(reader)
                return properties.getProperty("jdkBuild")
            } catch (ignore: IOException) {
            } finally {
                reader.close()
            }
        }
        return null
    }
}
