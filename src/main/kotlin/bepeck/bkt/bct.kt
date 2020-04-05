package bepeck.bkt

import bepeck.bkt.CopyProgress.Done
import bepeck.bkt.CopyProgress.Failed
import bepeck.bkt.CopyProgress.InProgress
import bepeck.bkt.FsItem.Companion.collectFsItems
import bepeck.bkt.FsItem.FileResolved
import java.io.File
import java.io.PrintWriter
import java.lang.System.currentTimeMillis
import java.lang.System.nanoTime
import java.nio.file.Files.createDirectories
import java.nio.file.Files.deleteIfExists
import java.nio.file.Files.exists
import java.nio.file.Files.newInputStream
import java.nio.file.Files.newOutputStream
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    val fileSrc = File("H:\\").toPath()

    val fileDst = File("C:\\Users\\bepeck\\Desktop\\Новая папка (4)").toPath()

    copy(fileSrc, fileDst)
}

private fun copy(dirSrc: Path, dirDst: Path) {
    val items = collectFsItems(dirSrc.toFile()).toList()

    printSrcCollectionResult(dirDst.resolve("src_file.txt"), items)

    val resolvedFiles = items.filterIsInstance<FileResolved>()

    val dirDstTmp = dirDst.resolve("${currentTimeMillis()}_${nanoTime()}_tmp")

    val errorSuffix = ".error"

    val toCopyFiles = resolvedFiles.map { resolvedFile ->
        resolvedFile.toToCopy(dirSrc, dirDst, dirDstTmp, errorSuffix)
    }

    if (errorFilesIntersectsWithFilesToCopy(toCopyFiles)) {
        println("there is error report file with the same name as copied file")
        return
    }

    toCopyFiles.forEach { toCopy ->
        toCopy.toCopySrcFile.apply {
            check(exists()) { "source does not exist: $name" }
            check(isFile) { "source is not file: $name" }
        }

        toCopy.toCopyDstFile.apply {
            if (exists()) {
                check(isFile) {
                    "destination is not a file: $name"
                }
            }
        }

        toCopy.toCopyDstTmpFile.apply {
            if (exists()) {
                check(isFile) {
                    "temporary destination is not a file: $name"
                }
            }
        }

        toCopy.toCopyDstErrorFile.apply {
            if (exists()) {
                check(isFile) {
                    "error report destination is not a file: $name"
                }
            }
        }
    }

    toCopyFiles.forEach { toCopy ->
        if (toCopy.toCopyDstFile.exists()) {
            println("${toCopy.toCopyDstFile} - exists, skipped")
        } else {
            val prevState = if (toCopy.toCopyDstErrorFile.exists()) {
                " (was failed)"
            } else {
                ""
            }

            println("${toCopy.toCopyDstFile} - try copy${prevState}")

            createParents(toCopy.toCopyDst)
            createParents(toCopy.toCopyDstError)
            createParents(toCopy.toCopyDstTmp)
            deleteIfExists(toCopy.toCopyDstTmp)

            tryCopy(toCopy)
        }
    }
}

private fun errorFilesIntersectsWithFilesToCopy(toCopyList: List<ToCopy>): Boolean {
    val files = toCopyList.map { it.toCopyDst }.toSet()
    val filesError = toCopyList.map { it.toCopyDstError }.toSet()
    return files.intersect(filesError).isNotEmpty()
}

private fun FileResolved.toToCopy(srcRoot: Path, dstRoot: Path, dirDstRootTmp: Path, errorSuffix: String): ToCopy {
    val toCopySrc = file.toPath()

    val toCopyRelativized: Path = srcRoot.relativize(toCopySrc)

    val toCopyDst: Path = dstRoot.resolve(toCopyRelativized)

    val toCopyDstTmp: Path = dirDstRootTmp.resolve(toCopyRelativized)

    val toCopyDstError = toCopyDst.run {
        parent.resolve(toFile().name + errorSuffix)
    }

    return ToCopy(
            toCopySrc = toCopySrc,
            toCopyDst = toCopyDst,
            toCopyDstTmp = toCopyDstTmp,
            toCopyDstError = toCopyDstError
    )
}

private fun tryCopy(toCopy: ToCopy) {
    val (toCopySrc, toCopyDst, toCopyDstTmp, _) = toCopy

    val toCopyDstErrorFile: File = toCopy.toCopyDstErrorFile

    doCopy(toCopySrc, toCopyDstTmp) { getNextProgressMessage ->
        try {
            var lastReadingTime = currentTimeMillis()
            val progressReportDelayMs = 5000L
            val progressStoppedTimeoutMs = 30000L

            while (true) {
                val progress = getNextProgressMessage(progressReportDelayMs)
                if (progress == null) {
                    if (currentTimeMillis() - lastReadingTime > progressStoppedTimeoutMs) {
                        println("$toCopySrc - too long reading")
                        toCopyDstErrorFile.writeText("timeout")
                        break
                    } else {
                        println("x")
                        continue
                    }
                }
                if (progress is Done) {
                    println("$toCopySrc - success")

                    toCopyDstTmp.toFile().renameTo(toCopyDst.toFile())
                    toCopyDstErrorFile.delete()

                    break
                }
                if (progress is Failed) {
                    println("$toCopySrc - fail: ${progress.message}")
                    toCopyDstErrorFile.writeText(progress.message ?: "unknown error")
                    break
                }
                if (progress is InProgress) {
                    if (progress.time - lastReadingTime > progressReportDelayMs) {
                        println(".")
                    }
                }
                lastReadingTime = progress.time
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            deleteIfExists(toCopyDstTmp)
        }
    }
}

private fun doCopy(from: Path, to: Path, progressHandler: ((Long) -> CopyProgress?) -> Unit) {
    val readings = LinkedBlockingQueue<CopyProgress>()
    val thread = Thread(Runnable {
        val inputStream = try {
            newInputStream(from)
        } catch (e: Exception) {
            readings.add(Failed("can't get input stream" + e.message))
            return@Runnable
        }
        val newOutputStream = try {
            newOutputStream(to)
        } catch (e: Exception) {
            readings.add(Failed("can't get output stream" + e.message))
            return@Runnable
        }
        inputStream.use { input ->
            newOutputStream.use { output ->
                try {
                    val buffer = ByteArray(1024)
                    while (true) {
                        val read = input.read(buffer)

                        if (read <= 0) {
                            readings.add(Done())
                            break
                        }

                        readings.add(InProgress())

                        output.write(buffer, 0, read)
                    }
                } catch (e: Exception) {
                    readings.add(Failed(e.message))
                }
            }
        }
    })
    thread.start()
    try {
        progressHandler {
            readings.poll(it, TimeUnit.MILLISECONDS)
        }
    } finally {
        thread.interrupt()
        thread.join()
    }
}

private fun createParents(path: Path) {
    val parent = path.parent
    if (exists(parent)) {
        return
    }
    createDirectories(parent)
}

private fun printSrcCollectionResult(log: Path, srcContent: List<FsItem>) {
    createParents(log)
    newOutputStream(log).use { out ->
        val writer = PrintWriter(out)
        srcContent.forEach { directoryContent ->
            writer.println(directoryContent)
        }
        writer.flush()
    }
}

private sealed class CopyProgress(val time: Long) {
    class InProgress(time: Long = currentTimeMillis()) : CopyProgress(time)
    class Done(time: Long = currentTimeMillis()) : CopyProgress(time)
    class Failed(val message: String?, time: Long = currentTimeMillis()) : CopyProgress(time)
}

private data class ToCopy(
        val toCopySrc: Path,
        val toCopyDst: Path,
        val toCopyDstTmp: Path,
        val toCopyDstError: Path
) {
    val toCopySrcFile: File by lazy { toCopySrc.toFile() }
    val toCopyDstFile: File by lazy { toCopyDst.toFile() }
    val toCopyDstTmpFile: File by lazy { toCopyDstTmp.toFile() }
    val toCopyDstErrorFile: File by lazy { toCopyDstError.toFile() }
}