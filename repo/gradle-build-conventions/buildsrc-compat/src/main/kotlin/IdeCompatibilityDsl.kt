/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import org.gradle.api.Project

interface CompatibilityPredicate {
    fun matches(ide: Ide): Boolean

    operator fun invoke(): Boolean = matches(IdeVersionConfigurator.currentIde)

    operator fun invoke(block: () -> Unit): Unit {
        if (matches(IdeVersionConfigurator.currentIde)) {
            block()
        }
    }
}

val CompatibilityPredicate.not: CompatibilityPredicate get() = object : CompatibilityPredicate {
    override fun matches(ide: Ide) = !this@not.matches(ide)
}

fun CompatibilityPredicate.or(other: CompatibilityPredicate): CompatibilityPredicate = object : CompatibilityPredicate {
    override fun matches(ide: Ide) = this@or.matches(ide) || other.matches(ide)
}

enum class Platform : CompatibilityPredicate {
    P241;

    val version: Int = name.drop(1).toInt()

    val displayVersion: String = "20${name.drop(1).dropLast(1)}.${name.last()}"

    override fun matches(ide: Ide) = ide.platform == this

    companion object {
        operator fun get(version: Int): Platform {
            return Platform.values().firstOrNull { it.version == version }
                ?: error("Can't find platform $version")
        }
    }
}

enum class Ide(val platform: Platform) : CompatibilityPredicate {
    IJ241(Platform.P241);

    val kind = Kind.values().first { it.shortName == name.take(2) }
    val version = name.dropWhile { !it.isDigit() }.toInt()

    val displayVersion: String = when (kind) {
        Kind.IntelliJ -> "IJ${platform.displayVersion}"
        Kind.AndroidStudio -> "Studio${name.substringAfter("AS").toCharArray().joinToString(separator = ".")}"
    }

    override fun matches(ide: Ide) = ide == this

    enum class Kind(val shortName: String) {
        AndroidStudio("AS"), IntelliJ("IJ")
    }

    companion object {
        val IJ: CompatibilityPredicate = IdeKindPredicate(Kind.IntelliJ)
        val AS: CompatibilityPredicate = IdeKindPredicate(Kind.AndroidStudio)
    }
}

val Platform.orHigher get() = object : CompatibilityPredicate {
    override fun matches(ide: Ide) = ide.platform.version >= version
}

val Platform.orLower get() = object : CompatibilityPredicate {
    override fun matches(ide: Ide) = ide.platform.version <= version
}

val Ide.orHigher get() = object : CompatibilityPredicate {
    override fun matches(ide: Ide) = ide.kind == kind && ide.version >= version
}

val Ide.orLower get() = object : CompatibilityPredicate {
    override fun matches(ide: Ide) = ide.kind == kind && ide.version <= version
}

object IdeVersionConfigurator {
    lateinit var currentIde: Ide

    fun setCurrentIde(project: Project) {
        val platformVersion = project.rootProject.extensions.extraProperties["versions.platform"].toString()
        val ideName = if (platformVersion.startsWith("AS")) platformVersion else "IJ$platformVersion"
        currentIde = Ide.valueOf(ideName)
    }
}

private class IdeKindPredicate(val kind: Ide.Kind) : CompatibilityPredicate {
    override fun matches(ide: Ide) = ide.kind == kind
}