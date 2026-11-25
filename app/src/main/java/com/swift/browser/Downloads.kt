package com.swift.browser

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class DownloadItem(val id: Int, val name: String, val mime: String) {
    var progress by mutableFloatStateOf(0f)
    var bytes by mutableLongStateOf(0L)
    var done by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var uri: Uri? = null
}

/** Saves downloads to the public Downloads folder through MediaStore (no storage permission needed, Android 10+). */
class Downloads(private val app: SwiftApp) {
    val items = mutableStateListOf<DownloadItem>()
    private val exec = Executors.newCachedThreadPool()
    private var seq = 1

    private fun header(h: Map<String, String>, key: String) = h.entries.firstOrNull { it.key.equals(key, true) }?.value

    fun save(r: WebResponse) {
        val mime = header(r.headers, "content-type")?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "application/octet-stream"
        val name = URLUtil.guessFileName(r.uri, header(r.headers, "content-disposition"), mime)
        val len = header(r.headers, "content-length")?.toLongOrNull() ?: -1L
        val body = r.body
        val item = DownloadItem(seq++, name, mime).also { items.add(0, it) }
        if (body == null) { item.failed = true; return }
        app.browser.host?.toast("Downloading $name")
        exec.execute { copy(item, body, len) }
    }

    /** For "save image" from the long-press menu (plain HTTP fetch, no page cookies). */
    fun saveUrl(url: String) {
        exec.execute {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                val mime = c.contentType?.substringBefore(';')?.trim() ?: "application/octet-stream"
                val item = DownloadItem(seq++, URLUtil.guessFileName(url, c.getHeaderField("Content-Disposition"), mime), mime)
                app.browser.post { items.add(0, item) }
                copy(item, c.inputStream, c.contentLengthLong)
            } catch (_: Exception) { }
        }
    }

    private fun copy(item: DownloadItem, input: InputStream, len: Long) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.name)
                put(MediaStore.Downloads.MIME_TYPE, item.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val res = app.contentResolver
            val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("cannot create file")
            res.openOutputStream(uri)?.use { out ->
                input.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        item.bytes = total
                        if (len > 0) item.progress = (total.toFloat() / len).coerceAtMost(1f)
                    }
                }
            } ?: throw IOException("cannot open file")
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            res.update(uri, cv, null, null)
            item.uri = uri
            item.progress = 1f
            item.done = true
            app.browser.post { app.browser.host?.toast("Saved ${item.name}") }
        } catch (e: Exception) {
            item.failed = true
        }
    }
}
