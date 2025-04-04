package org.jetbrains.kotlin.gradle

import org.gradle.api.logging.LogLevel
import org.gradle.testkit.runner.BuildResult
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.testbase.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.assertEquals

@DisplayName("Kapt incremental compilation")
@OtherGradlePluginTests
open class KaptIncrementalIT : KGPBaseTest() {
    companion object {
        private val EXAMPLE_ANNOTATION_REGEX = "@(field:)?example.ExampleAnnotation".toRegex()
        const val PROJECT_NAME = "kaptIncrementalCompilationProject"
        const val KAPT3_STUBS_PATH = "build/tmp/kapt3/stubs/main"
    }

    private val annotatedElements =
        arrayOf("A", "funA", "valA", "funUtil", "valUtil", "B", "funB", "valB", "useB", "funGetsInputParams")

    override fun TestProject.customizeProject() {
        forceK1Kapt()
    }

    override val defaultBuildOptions = super.defaultBuildOptions.copy(
        incremental = true,
        kaptOptions = BuildOptions.KaptOptions(incrementalKapt = true)
    )

    protected open fun KGPBaseTest.kaptProject(
        gradleVersion: GradleVersion,
        buildOptions: BuildOptions = defaultBuildOptions,
        buildJdk: File? = null,
        test: TestProject.() -> Unit
    ): TestProject = project(
        PROJECT_NAME,
        gradleVersion,
        buildOptions = buildOptions,
        buildJdk = buildJdk,
        test = test
    )

    @DisplayName("After adding new line compilation is incremental")
    @GradleTest
    fun testAddNewLine(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("clean", "build")
            val editedFile = javaSourcesDir().resolve("bar/useB.kt")
            editedFile.modify { "\n$it" }
            build("build", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertTasksExecuted(":kaptGenerateStubsKotlin", ":compileKotlin")
                assertTasksUpToDate(":kaptKotlin")
                assertTasksUpToDate(":compileJava")
                assertCompiledKotlinSourcesHandleKapt(listOf(projectPath.relativize(editedFile)))
            }
        }
    }

    @DisplayName("On rebuild without changes tasks should be UP-TO-DATE")
    @GradleTest
    fun testBasic(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            enablePassedTestLogging()
            build("build") {
                checkGenerated(kaptGeneratedToPath, *annotatedElements)
                checkNotGenerated(kaptGeneratedToPath, "notAnnotatedFun")
                assertOutputContains("ATest > testValA")
            }

            build("build") {
                assertTasksUpToDate(
                    ":compileKotlin",
                    ":compileJava"
                )

                assertTasksUpToDate(
                    ":kaptKotlin",
                    ":kaptGenerateStubsKotlin"
                )
            }
        }
    }

    @DisplayName("Successfully rebuild after compilation error")
    @GradleTest
    fun testCompileError(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("assemble")

            val bKt = javaSourcesDir().resolve("bar/B.kt")
            val errorKt = bKt.resolveSibling("error.kt")
            errorKt.writeText("fun foo() { ElementExpectedError }") //kapt will process this successfully as it ignores bodies

            buildAndFail("assemble")

            errorKt.deleteIfExists()
            bKt.modify { "$it\n" }
            build("assemble", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertCompiledKotlinSourcesHandleKapt(listOf(projectPath.relativize(bKt)))
            }
        }
    }

    @DisplayName("Successfully rebuild after KAPT stubs generation error")
    @GradleTest
    open fun testKaptError(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("assemble")

            val bKt = javaSourcesDir().resolve("bar/B.kt")
            val errorKt = bKt.resolveSibling("error.kt")
            errorKt.writeText("<COMPILE_ERROR_MARKER>") //this is a declaration problem so KAPT will fail on it

            buildAndFail("assemble")

            errorKt.deleteIfExists()
            bKt.modify { "$it\n" }
            build("assemble", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertCompiledKotlinSourcesHandleKapt(listOf(projectPath.relativize(bKt)))
            }
        }
    }

    @DisplayName("Change in the function body without changing the signature")
    @GradleTest
    fun testChangeFunctionBodyWithoutChangingSignature(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            enablePassedTestLogging()
            build("build") {
                checkGenerated(kaptGeneratedToPath, *annotatedElements)
                checkNotGenerated(kaptGeneratedToPath, "notAnnotatedFun")
                assertOutputContains("ATest > testValA")
            }

            val utilKt = javaSourcesDir().resolve("baz/util.kt")
            utilKt.modify { oldContent ->
                assert(oldContent.contains("2 * 2 == 4"))
                oldContent.replace("2 * 2 == 4", "2 * 2 == 5")
            }

            build("assemble") {
                assertTasksExecuted(":kaptGenerateStubsKotlin")
                assertTasksUpToDate(":kaptKotlin")
            }
        }
    }

    @DisplayName("Change in a function signature is handled incrementally")
    @GradleTest
    fun testChangeFunctionSignature(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            enablePassedTestLogging()
            build("build") {
                checkGenerated(kaptGeneratedToPath, *annotatedElements)
                checkNotGenerated(kaptGeneratedToPath, "notAnnotatedFun")
            }

            val utilKt = javaSourcesDir().resolve("baz/util.kt")
            utilKt.modify {
                it.replace("funGetsInputParams()", "funGetsInputParams(input: String, output: ArrayList<String>)")
                    .replace("val input = \"This is a non-test string\"", "")
                    .replace("val output = ArrayList<String>()", "")
            }

            build("assemble", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertKapt3FullyExecuted()
                assertCompiledKotlinSourcesHandleKapt(listOf(projectPath.relativize(utilKt)))
            }
        }
    }

    @Disabled("KT-70176")
    @DisplayName("Rename refactoring of an annotated element is handled incrementally")
    @GradleTest
    fun testRenameRefactoringOfAnAnnotatedElement(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("assemble")

            val bKt = javaSourcesDir().resolve("bar/B.kt")
            val useB = javaSourcesDir().resolve("bar/useB.kt")
            val useBbyJava = javaSourcesDir().resolve("foo/JavaClass.java")
            bKt.modify { it.replace("valB", "valNewB") }
            useB.modify { it.replace("fun useB(b: B) = b.valB", "fun useB(b: B) = b.valNewB") }
            useBbyJava.modify { it.replace("b.getValB()", "b.getValNewB()") }

            build("assemble", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertKapt3FullyExecuted()
                assertCompiledKotlinSourcesHandleKapt(relativeToProject(listOf(bKt, useB)))
            }
        }
    }

    @DisplayName("Adding new annotated element")
    @GradleTest
    fun testAddAnnotatedElement(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("assemble")

            val utilKt = javaSourcesDir().resolve("baz/util.kt")
            utilKt.modify { oldContent ->
                """
                $oldContent

                @example.ExampleAnnotation
                fun newUtilFun() {}
                """.trimIndent()
            }

            build("assemble", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertKapt3FullyExecuted()

                assertCompiledKotlinSourcesHandleKapt(listOf(projectPath.relativize(utilKt)))
                checkGenerated(kaptGeneratedToPath, *(annotatedElements + arrayOf("newUtilFun")))
            }
        }
    }

    @DisplayName("Adding new annotation triggers kapt run")
    @GradleTest
    fun testAddAnnotation(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("assemble")

            val utilKt = javaSourcesDir().resolve("baz/util.kt")
            utilKt.modify {
                it.replace("fun notAnnotatedFun", "@example.ExampleAnnotation fun notAnnotatedFun")
            }

            build("assemble", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertKapt3FullyExecuted()
                assertCompiledKotlinSources(listOf(projectPath.relativize(utilKt)), output)
                checkGenerated(kaptGeneratedToPath, *(annotatedElements + arrayOf("notAnnotatedFun")))
            }
        }
    }

    @DisplayName("Kapt run is incremental after source file was removed")
    @GradleTest
    fun testRemoveSourceFile(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            val kapt3IncDataPath = "build/tmp/kapt3/incrementalData/main"
            val kapt3StubsPath = "build/tmp/kapt3/stubs/main"

            build("assemble") {
                assertKapt3FullyExecuted()

                assertFileInProjectExists("$kapt3IncDataPath/bar/B.class")
                assertFileInProjectExists("$kapt3IncDataPath/bar/UseBKt.class")
                assertFileInProjectExists("$kapt3StubsPath/bar/B.java")
                assertFileInProjectExists("$kapt3StubsPath/bar/B.kapt_metadata")
                assertFileInProjectExists("$kapt3StubsPath/bar/UseBKt.java")
                assertFileInProjectExists("$kapt3StubsPath/bar/UseBKt.kapt_metadata")
            }

            with(javaSourcesDir()) {
                resolve("bar/B.kt").deleteIfExists()
                resolve("bar/useB.kt").deleteIfExists()
            }

            buildAndFail("assemble") {
                assertFileInProjectNotExists("$kapt3IncDataPath/bar/B.class")
                assertFileInProjectNotExists("$kapt3IncDataPath/bar/UseBKt.class")
                assertFileInProjectNotExists("$kapt3StubsPath/bar/B.java")
                assertFileInProjectNotExists("$kapt3StubsPath/bar/B.kaptMetadata")
                assertFileInProjectNotExists("$kapt3StubsPath/bar/UseBKt.java")
                assertFileInProjectNotExists("$kapt3StubsPath/bar/UseBKt.kaptMetadata")
            }

            javaSourcesDir().resolve("foo/JavaClass.java").deleteIfExists()

            build("assemble", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertKapt3FullyExecuted()
                assertCompiledKotlinSourcesHandleKapt(javaSourcesDir("main")
                        .allKotlinSources.map { projectPath.relativize(it) })
                val affectedElements = arrayOf("B", "funB", "valB", "useB")
                checkGenerated(kaptGeneratedToPath, *(annotatedElements.toSet() - affectedElements).toTypedArray())
                checkNotGenerated(kaptGeneratedToPath, *affectedElements)
            }
        }
    }

    @DisplayName("Incremental kapt run is correct after removing all Kotlin sources")
    @GradleTest
    open fun testRemoveAllKotlinSources(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("assemble") {
                assertFileInProjectExists("$KAPT3_STUBS_PATH/bar/UseBKt.java")
            }

            with(projectPath) {
                resolve("src/").deleteRecursively()
                resolve("src/main/java/bar").createDirectories()
                resolve("src/main/java/bar/MyClass.java").writeText(
                    """
                    package bar;
                    public class MyClass {}
                    """.trimIndent()
                )
            }

            build("assemble") {
                // Make sure all generated stubs are removed (except for NonExistentClass).
                assertEquals(
                    listOf(projectPath.resolve("$KAPT3_STUBS_PATH/error/NonExistentClass.java").toRealPath().toString()),
                    projectPath
                        .resolve(KAPT3_STUBS_PATH)
                        .toFile()
                        .walk()
                        .filter { it.extension == "java" }
                        .map { it.absolutePath }
                        .toList()
                )
                // Make sure all compiled kt files are cleaned up.
                assertEquals(
                    emptyList(),
                    projectPath
                        .resolve("build/classes/kotlin")
                        .toFile()
                        .walk()
                        .filter { it.extension == "class" }
                        .toList()
                )
            }
        }
    }

    @DisplayName("On all annotations remove kapt and compile runs incrementally")
    @GradleTest
    @Disabled("KT-70176")
    fun testRemoveAnnotations(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("assemble")

            val bKt = javaSourcesDir().resolve("bar/B.kt")
            bKt.modify { it.replace(EXAMPLE_ANNOTATION_REGEX, "") }
            val affectedElements = arrayOf("B", "funB", "valB")

            build("assemble", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertKapt3FullyExecuted()

                val useBKt = javaSourcesDir().resolve("bar/useB.kt")
                assertCompiledKotlinSources(
                    relativeToProject(listOf(bKt, useBKt)),
                    getOutputForTask(":kaptGenerateStubsKotlin"),
                    errorMessageSuffix = " in task 'kaptGenerateStubsKotlin'"
                )

                assertCompiledKotlinSources(
                    relativeToProject(listOf(bKt, useBKt)),
                    getOutputForTask(":compileKotlin"),
                    errorMessageSuffix = " in task 'compileKotlin'"
                )

                checkGenerated(
                    kaptGeneratedToPath,
                    *(annotatedElements.toSet() - affectedElements).toTypedArray()
                )
                checkNotGenerated(kaptGeneratedToPath, *affectedElements)
            }
        }
    }

    @DisplayName("Changing annotated property type is handled correctly")
    @GradleTest
    fun testChangeAnnotatedPropertyType(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("assemble")

            val bKt = javaSourcesDir().resolve("bar/B.kt")
            val useBKt = javaSourcesDir().resolve("bar/useB.kt")
            bKt.modify { it.replace("val valB = \"text\"", "val valB = 4") }

            build("assemble", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertKapt3FullyExecuted()
                assertCompiledKotlinSourcesHandleKapt(listOf(bKt, useBKt).map { projectPath.relativize(it) })
                checkGenerated(kaptGeneratedToPath, *annotatedElements)
            }
        }
    }

    @DisplayName("Changes in inline delegate are handled incrementally")
    @GradleTest
    fun testChangeInlineDelegate(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("assemble")

            val usageFile = javaSourcesDir().resolve("delegate/Usage.kt")
            val delegateFile = javaSourcesDir().resolve("delegate/Delegate.kt")
            delegateFile.modify {
                it.replace("Delegate()", "Delegate<T>()")
                    .replace("getValue(thisRef: Any?", "getValue(thisRef: T")
                    .replace("setValue(thisRef: Any?", "setValue(thisRef: T?")
            }
            usageFile.modify { it.replace("Delegate()", "Delegate<Usage>()") }

            build("assemble", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertTasksExecuted(":kaptGenerateStubsKotlin", ":compileKotlin")
                assertCompiledKotlinSourcesHandleKapt(relativeToProject(listOf(usageFile, delegateFile)))
            }
        }
    }

    @DisplayName("Changes in test sources are processed incrementally")
    @GradleTest
    fun testChangeTestSources(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("build")

            val testFile = javaSourcesDir("test").resolve("foo/KTest.kt")
            testFile.modify { it.replace("assertEquals(6, add(3, 3))", "assertEquals(13, add(3, 10))") }

            build("build", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertTasksExecuted(":kaptGenerateStubsTestKotlin", ":compileTestKotlin")
                assertCompiledKotlinTestSourcesAreHandledByKapt(relativeToProject(listOf(testFile)))
            }
        }
    }

    @DisplayName("Changes to main sources with annotated elements are handled in tests incrementally")
    @GradleTest
    fun testSourceChangesAreReflectedInTests(gradleVersion: GradleVersion) {
        kaptProject(gradleVersion) {
            build("build")

            val fileToEdit = javaSourcesDir().resolve("jvmName/math.kt")
            val testFileToRecompile = javaSourcesDir("test").resolve("foo/KTest.kt")
            fileToEdit.modify { it.replace("@file:JvmName(\"Math\")", "@file:JvmName(\"Mathematics\")") }

            build("build", buildOptions = buildOptions.copy(logLevel = LogLevel.DEBUG)) {
                assertTasksExecuted(":kaptGenerateStubsTestKotlin", ":compileTestKotlin")
                assertCompiledKotlinTestSourcesAreHandledByKapt(relativeToProject(listOf(testFileToRecompile)))
            }
        }
    }

    private fun BuildResult.assertKapt3FullyExecuted() {
        assertTasksExecuted(":kaptKotlin", ":kaptGenerateStubsKotlin")
    }

    private fun TestProject.checkGenerated(
        generateToPath: Path,
        vararg annotatedElementNames: String
    ) {
        getGeneratedFileNames(*annotatedElementNames).forEach {
            assertFileExistsInTree(generateToPath, it)
        }
    }

    private fun TestProject.checkNotGenerated(
        generateToPath: Path,
        vararg annotatedElementNames: String
    ) {
        getGeneratedFileNames(*annotatedElementNames).forEach {
            assertFileNotExistsInTree(generateToPath, it)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun getGeneratedFileNames(vararg annotatedElementNames: String) =
        annotatedElementNames
            .map { name ->
                name.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                } + "Generated.java"
            }

    val TestProject.kaptGeneratedToPath get() = projectPath.resolve("build/generated/source/kapt")
}

@DisplayName("Kapt incremental compilation with disabled precise compilation outputs backup")
class KaptIncrementalWithoutPreciseBackupIT : KaptIncrementalIT() {
    override val defaultBuildOptions =
        super.defaultBuildOptions.copy(usePreciseOutputsBackup = false, keepIncrementalCompilationCachesInMemory = false)
}