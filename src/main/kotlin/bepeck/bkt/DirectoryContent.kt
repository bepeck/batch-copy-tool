package bepeck.bkt

import bepeck.bkt.DirectoryContent.DirectoryFailedToList
import bepeck.bkt.DirectoryContent.ResolvedFile
import java.io.File

fun collectDirectoryContent(file: File): Sequence<DirectoryContent> {
    return sequence {
        print("$file: ")
        if (file.isDirectory) {
            print(" directory")
            val dirFiles = file.listFiles()
            if (dirFiles == null) {
                println(" failed to list")
                yield(DirectoryFailedToList(file))
            } else {
                println("")
                dirFiles.map { dirFile ->
                    collectDirectoryContent(dirFile)
                }.forEach { dirFileContent ->
                    yieldAll(dirFileContent)
                }
            }
        } else {
            println(" file")
            yield(ResolvedFile(file))
        }
    }
}

sealed class DirectoryContent(val file: File) : Comparable<DirectoryContent> {
    override fun compareTo(other: DirectoryContent): Int {
        return file.path.compareTo(other.file.path)
    }

    override fun toString(): String {
        return "${this.javaClass.simpleName}(file=$file)"
    }

    class ResolvedFile(file: File) : DirectoryContent(file)
    class DirectoryFailedToList(file: File) : DirectoryContent(file)
}