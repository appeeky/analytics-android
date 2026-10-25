package com.appeeky.analytics

import android.content.Context
import java.io.File

/**
 * State and the event queue as files under the app's no-backup directory, so
 * Auto Backup never restores an install id onto another device: a restored or
 * new device counts as a new install.
 */
internal class AndroidStorage(context: Context) : KeyValueStorage {
    private val dir = File(context.noBackupFilesDir, "appeeky-analytics")

    private fun store(key: String) = FileStore(File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"))

    override fun getItem(key: String): String? = store(key).read()

    override fun setItem(key: String, value: String) = store(key).write(value)
}

/** One text file written atomically: a temporary file renamed over the old one. */
internal class FileStore(private val file: File) {
    fun read(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    fun write(value: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(value, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("could not replace ${file.name}")
        }
    }
}
