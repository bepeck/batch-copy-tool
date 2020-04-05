package bepeck.bkt

import java.io.File

sealed class FsItem(val file: File) : Comparable<FsItem> {

    override fun compareTo(other: FsItem): Int {
        return file.path.compareTo(other.file.path)
    }

    override fun toString(): String {
        return "${this.javaClass.simpleName}(file=$file)"
    }

    class FileResolved(file: File) : FsItem(file)

    class DirectoryFailedToList(file: File) : FsItem(file)

    companion object {
        /**
         * This function collects leaves of file system tree starting from proposed root.
         * The result includes resolved files and directories which files are failed to list.
         *
         * @param file file to start collecting.
         * @return sequence of the collected items.
         */
        fun collectFsItems(file: File): Sequence<FsItem> {
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
                            collectFsItems(dirFile)
                        }.forEach { dirFileContent ->
                            yieldAll(dirFileContent)
                        }
                    }
                } else {
                    println(" file")
                    yield(FileResolved(file))
                }
            }
        }
    }
}