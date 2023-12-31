package main.utils

import codogen.generateKtProject
import frontend.resolver.*
import frontend.util.CurrentOS
import frontend.util.div
import frontend.util.getOSType
import inlineReplSystem.inlineReplSystem
import main.PathManager
import java.io.*


fun String.runCommand(workingDir: File, withOutputCapture: Boolean = false) {
    val p = ProcessBuilder(*this.split(" ").toTypedArray())
        .directory(workingDir)

    if (withOutputCapture) {
        p.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
    }

    val process = p.start()

    val closeChildThread: Thread = object : Thread() {
        override fun run() {
//            println("DESTROY")
            process.destroy()
        }
    }

    Runtime.getRuntime().addShutdownHook(closeChildThread)


//    var output = ""
//    val inputStream = BufferedReader(InputStreamReader(process.inputStream))
//    while ( process.isAlive) {
//        inputStream.readLine()?.also { output = it } //!= null ||
//        println(output)
//    }

    ///

    process.waitFor()//.waitFor(15, TimeUnit.SECONDS)
//    if (stillExist) process.destroy()
//    inputStream.close()


}

class Compiler(
    private val pathToProjectRoot: String,
    private val inlineReplPath: File,
    private val compilationTarget: CompilationTarget,
    private val compilationMode: CompilationMode,
    private val mainNivaFileName: String,
    private val resolver: Resolver
) {
    private fun cmd(compileOnlyNoRun: Boolean = false) = (if (!compileOnlyNoRun)
        when (compilationTarget) { // native run is always debug
            CompilationTarget.jvm -> "run"
            CompilationTarget.linux -> "runLinuxX64DebugExecutableLinuxX64"
            CompilationTarget.macos -> "runMacosArm64DebugExecutableMacosArm64"
        } else
        when (compilationTarget) {
            CompilationTarget.jvm -> "distZip"
            CompilationTarget.linux -> compilationMode.toCompileOnlyTask(compilationTarget)
            CompilationTarget.macos -> compilationMode.toCompileOnlyTask(compilationTarget)
        }) + " -Pkotlin.experimental.tryK2=true"


    fun run(compileOnlyNoRun: Boolean = false, singleFile: Boolean = false) {
        // remove repl log file since it will be recreated
        val removeReplFile = {
            if (inlineReplPath.exists()) {
                inlineReplPath.delete()
            }
        }
        removeReplFile()

        val file = File(pathToProjectRoot)
        if (!file.exists()) {
            throw Exception("Infro project doesn't exists, run compile script from niva repo")
        }

        if (compilationMode == CompilationMode.release && compilationTarget == CompilationTarget.jvm) {
            warning("Release mode is useless with jvm target")
        }

        val cmd = cmd(compileOnlyNoRun)
        when (getOSType()) {
            CurrentOS.WINDOWS -> "cmd.exe /c gradlew.bat -q $cmd".runCommand(file, true)
            CurrentOS.LINUX -> "./gradlew -q $cmd".runCommand(file, true)
            CurrentOS.MAC -> "./gradlew -q $cmd".runCommand(file, true)
        }

        if (inlineReplPath.exists()) {
            if (compilationTarget == CompilationTarget.jvm) {
                inlineReplSystem(inlineReplPath)
                removeReplFile()
            } else {
                warning("inline repl currently supported only in jvm target")
            }
        }
        if (compileOnlyNoRun) {
            when (compilationTarget) {
                CompilationTarget.jvm -> {
                    val zipName = File("./${mainNivaFileName}.zip")
                    val pathToNativeExe =
                        pathToProjectRoot / "build" / "distributions" / "infroProject-SNAPSHOT-1.0.zip"
                    File(pathToNativeExe).copyTo(zipName, true)
                }

                CompilationTarget.linux -> {
                    val execName = File("./$mainNivaFileName")
                    val pathToNativeExe = compilationMode.toBinaryPath(compilationTarget, pathToProjectRoot)
                    File(pathToNativeExe).copyTo(execName, true)
                    execName.setExecutable(true)
                }

                CompilationTarget.macos -> {}
            }
        }
    }

    fun infoPrint(onlyUserDefined: Boolean, specialPkg: String?) {
        if (specialPkg != null) {
            println("info for package: $specialPkg")
            val pkgInko = generatePkgInfo(resolver, specialPkg)
            println(pkgInko)
            return
        }
        val mdInfo = generateInfo(resolver, onlyUserDefined)
        println(mdInfo)
    }
}


fun compileProjFromFile(
    pm: PathManager,
    singleFile: Boolean
): Resolver {
    val pathToNivaMainFile = pm.pathToNivaMainFile
    val pathWhereToGenerateKt = pm.pathWhereToGenerateKtAmper
    val pathToGradle = pm.pathToGradle
    val pathToAmper = pm.pathToAmper

    fun listFilesRecursively(directory: File, ext: String, ext2: String): List<File> {
        val fileList = mutableListOf<File>()
        val filesAndDirs = directory.listFiles()
        if (filesAndDirs != null) {
            for (file in filesAndDirs) {
                if (file.isFile && (file.extension == ext || file.extension == ext2)) {
                    fileList.add(file)
                } else if (file.isDirectory) {
                    fileList.addAll(listFilesRecursively(file, ext, ext2))
                }
            }
        }
        return fileList
    }


    val mainFile = File(pathToNivaMainFile)
    val nivaProjectFolder = mainFile.absoluteFile.parentFile
    val otherFilesPaths =
        if (!singleFile)
            listFilesRecursively(nivaProjectFolder, "niva", "scala").filter { it.name != mainFile.name }
        else
            listOf()

//    val allFiles = listOf( mainFile.absoluteFile.toString()) + otherFilesPaths.map { it.absoluteFile.toString() }

//    println("Compiling: $allFiles")
    // we have main file, and all other files, so we can create resolver now
    val resolver = Resolver(
        projectName = "common",
//        mainFile = mainFile,
        otherFilesPaths = otherFilesPaths,
        statements = mutableListOf()
    )

    resolver.resolve(mainFile)
    val defaultProject = resolver.projects["common"]!!

    resolver.generator.generateKtProject(
        pathWhereToGenerateKt,
        pathToGradle,
        pathToAmper,
        defaultProject,
        resolver.topLevelStatements,
        resolver.compilationTarget
    )
    // printing all >?
    resolver.printInfoFromCode()
    return resolver
}


fun addStd(mainCode: String, compilationTarget: CompilationTarget): String {
    val inlineReplPath = File("inline_repl.txt").absolutePath


    val quote = "\"\"\""

    val jvmSpecific = if (compilationTarget == CompilationTarget.jvm)
        """import java.io.BufferedWriter
        import java.io.FileWriter
        import java.io.IOException

        fun <T> inlineRepl(x: T, pathToNivaFileAndLine: String, count: Int): T {
            val q = x.toString()
            // x/y/z.niva:6 5
            val content = pathToNivaFileAndLine + "|||" + q + "***" + count

            try {
                val writer = BufferedWriter(FileWriter(INLINE_REPL, true))
                writer.append(content)
                writer.newLine()
                writer.close()
            } catch (e: IOException) {
                println("File error" + e.message)
            }

            return x
        }
    """.trimIndent() else "fun <T> inlineRepl(x: T, pathToNivaFileAndLine: String, count: Int) {}"

    val nivaStd = """
        // STD
        $jvmSpecific

        class Error {
            companion object
        }
        fun Error.Companion.throwWithMessage(message: String): Nothing {
            throw kotlin.Exception(message)
        }

        inline fun Any?.echo() = println(this)
        inline fun Any?.echonnl() = print(this)
        
        inline fun <T, R> T?.unpack(block: (T) -> R) {
            if (this != null)
                block(this)
        }
        
        inline fun <T : Any, R : Any> letIfAllNotNull(vararg arguments: T?, block: (List<T>) -> R): R? {
            return if (arguments.all { it != null }) {
                block(arguments.toList() as List<T>)
            } else null
        }


        const val INLINE_REPL = $quote$inlineReplPath$quote

        inline fun IntRange.forEach(action: (Int) -> Unit) {
            for (element in this) action(element)
        }

        // for cycle
        inline fun Int.toDo(to: Int, `do`: (Int) -> Unit) {
            for (element in this.rangeTo(to)) `do`(element)
        }

        inline fun Int.untilDo(until: Int, `do`: (Int) -> Unit) {
            for (element in this.rangeUntil(until)) `do`(element)
        }

        inline fun Int.downToDo(down: Int, `do`: (Int) -> Unit) {
            for (element in this.downTo(down)) `do`(element)
        }

        // while cycles
        typealias WhileIf = () -> Boolean

        inline fun <T> WhileIf.whileTrue(x: () -> T) {
            while (this()) {
                x()
            }
        }

        inline fun <T> WhileIf.whileFalse(x: () -> T) {
            while (!this()) {
                x()
            }
        }
        
        inline fun <T> Boolean.ifTrue(x: () -> T) {
            if (this) {
                x()
            }
        }
        
        inline fun <T> Boolean.ifFalse(x: () -> T) {
            if (!this) {
                x()
            }
        }
        
        inline fun <T> Boolean.ifTrueIfFalse(x: () -> T, y: () -> T) {
            if (this) {
                x()
            } else y()
        }
        
        inline fun <T> Boolean.ifFalseIfTrue(x: () -> T, y: () -> T) {
            if (!this) {
                x()
            } else y()
        }

        

        operator fun <K, V> MutableMap<out K, V>.plus(map: MutableMap<out K, V>): MutableMap<K, V> =
            LinkedHashMap(this).apply { putAll(map) }



        inline fun Boolean.isFalse() = !this
        inline fun Boolean.isTrue() = this

        // end of STD

    """.trimIndent()

    return buildString {
        append(nivaStd, "\n")
        append(mainCode)
    }
}


fun putInMainKotlinCode(code: String) = buildString {
//    try {
//        listOf(1).get(232)
//    } catch (e: Exception) {
//        println(e.message)
//        println("-----------")
//        val q = e.stackTrace
//        println(e.stackTraceToString())
//    }

    append("fun main() {\n")
    append("try {\n")

    append(code, "\n")

    append(
        """
        } catch (e: Exception) {
        println("----------")
        println(e.message)
        println("----------")
        println(e.stackTraceToString())
    }
    """.trimIndent()
    )


    append("}\n")
}
