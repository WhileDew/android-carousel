// app/src/main/java/com/example/imageviewer/CacheApi.kt
package com.example.carousel

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object CacheApi {
    private const val CACHE_DIR = "cache_images"

    fun clear(context: Context) {
        val dir = File(context.filesDir, CACHE_DIR)
        if (dir.exists()) dir.deleteRecursively()
    }

    fun getImages(context: Context): List<File> {
        val dir = File(context.filesDir, CACHE_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isFile } ?: emptyList()
    }

    fun saveImages(context: Context, urls: List<String>) {
        val dir = File(context.filesDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()

        urls.forEachIndexed { index, urlStr ->
            val file = File(dir, "img_$index.jpg")
            if (!file.exists()) {
                try {
                    val url = URL(urlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connect()
                    if (conn.responseCode == 200) {
                        val input = conn.inputStream
                        val output = FileOutputStream(file)
                        input.copyTo(output)
                        input.close()
                        output.close()
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
