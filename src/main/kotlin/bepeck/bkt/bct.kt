package bepeck.bkt

import bepeck.bkt.FsItem.FileResolved
import bepeck.bkt.Reading.Failed
import bepeck.bkt.Reading.Partially
import bepeck.bkt.Reading.Totally
import java.io.File
import java.io.PrintWriter
import java.lang.System.currentTimeMillis
import java.lang.System.nanoTime
import java.nio.file.Files
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
//    val fileSrc = File("G:\\Photography\\!!!My\\!!!Разобрать\\18.07.17 Фотосессия Лена Елагин").toPath()
    val fileDst = File("C:\\Users\\bepeck\\Desktop\\Новая папка (4)").toPath()

    copy(fileSrc, fileDst)
}

private fun copy(dirSrc: Path, dirDst: Path) {
    val content = FsItem.collectFsItems(dirSrc.toFile()).toList()

    printSrcCollectionResult(dirDst.resolve("src_file.txt"), content)

    val resolvedFiles = content.filterIsInstance<FileResolved>()

    val dirDstTmp = dirDst.resolve("${currentTimeMillis()}_${nanoTime()}_tmp")

    resolvedFiles.forEach {
        tryCopy(dirSrc, dirDst, dirDstTmp, it.file.toPath())
    }
}

private fun tryCopy(dirSrc: Path, dirDst: Path, dirDstTmp: Path, toCopySrc: Path) {
    val toCopyRelativized = dirSrc.relativize(toCopySrc)

    val toCopyDst = dirDst.resolve(toCopyRelativized)

    val toCopyDstFile = toCopyDst.toFile()
    if (toCopyDstFile.exists() && toCopyDstFile.isFile) {
        println("$toCopyDst - exists, skipped")
        return
    }

    createParents(toCopyDst)

    val toCopyDstTmp = dirDstTmp.resolve(toCopyRelativized)
    createParents(toCopyDstTmp)
    deleteIfExists(toCopyDstTmp)

    try {
        val failedFile = toCopyDst.run {
            parent.resolve(toFile().name + ".error")
        }.toFile()

        val prevState = if (failedFile.exists()) {
            "was failed"
        } else {
            ""
        }

        println("$toCopySrc - try copy ($prevState)")

        Files.createFile(toCopyDstTmp)

        val readings = LinkedBlockingQueue<Reading>()

        //TODO дописка

        Thread(Runnable {
            val inputStream = try {
                newInputStream(toCopySrc)
            } catch (e: Exception) {
                readings.add(Failed("can't get input stream" + e.message))
                return@Runnable
            }
            val newOutputStream = try {
                newOutputStream(toCopyDstTmp)
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
                                readings.add(Totally())
                                break
                            }

                            readings.add(Partially())

                            output.write(buffer, 0, read)
                        }
                    } catch (e: java.lang.Exception) {
                        readings.add(Failed(e.message))
                    }
                }
            }
        }).apply {
            start()

            var lastReadingTime = currentTimeMillis()
            val progressReportDelayMs = 5000L
            val progressStoppedTimeoutMs = 30000L

            while (true) {
                val reading = readings.poll(progressReportDelayMs, TimeUnit.MILLISECONDS)
                if (reading == null) {
                    if (currentTimeMillis() - lastReadingTime > progressStoppedTimeoutMs) {
                        println("$toCopySrc - too long reading")
                        failedFile.writeText("timeout")
                        interrupt()
                        break
                    } else {
                        println("x")
                        continue
                    }
                }
                if (reading is Totally) {
                    println("$toCopySrc - success")

                    toCopyDstTmp.toFile().renameTo(toCopyDst.toFile())
                    failedFile.delete()

                    break
                }
                if (reading is Failed) {
                    println("$toCopySrc - fail: ${reading.message}")
                    failedFile.writeText(reading.message ?: "unknown error")
                    break
                }
                if (reading is Partially) {
                    if (reading.time - lastReadingTime > progressReportDelayMs) {
                        println(".")
                    }
                }
                lastReadingTime = reading.time
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        deleteIfExists(toCopyDstTmp)
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
    newOutputStream(log).use { out ->
        val writer = PrintWriter(out)
        srcContent.forEach { directoryContent ->
            writer.println(directoryContent)
        }
        writer.flush()
    }
}

sealed class Reading(val time: Long) {
    class Partially(time: Long = currentTimeMillis()) : Reading(time)
    class Totally(time: Long = currentTimeMillis()) : Reading(time)
    class Failed(val message: String?, time: Long = currentTimeMillis()) : Reading(time)
}