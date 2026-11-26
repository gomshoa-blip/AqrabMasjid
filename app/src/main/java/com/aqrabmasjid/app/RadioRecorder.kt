package com.aqrabmasjid.app

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.FileOutputStream

/**
 * Keeps the last 3 minutes of the radio in memory while it plays.
 * "Save" writes them to a file on the phone. Only the last 5 files are kept.
 * Nothing is sent to our site: the files stay on the user's phone.
 */
object RadioRecorder {
    const val KEEP_MS = 3 * 60_000L
    const val MAX_FILES = 5

    private val chunks = ArrayDeque<Pair<Long, ByteArray>>()
    @Volatile private var startedAt = 0L
    @Volatile var ext = "mp3"

    /** Station in the memory now; another station starts a new memory. */
    @Volatile var station: String? = null
        private set

    @Synchronized fun clear() { chunks.clear(); startedAt = 0L }

    @Synchronized fun begin(stationId: String) {
        if (station != stationId) { clear(); station = stationId }
    }

    @Synchronized fun add(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val now = System.currentTimeMillis()
        if (startedAt == 0L) startedAt = now
        chunks.addLast(now to b.copyOfRange(off, off + len))
        while (chunks.isNotEmpty() && now - chunks.first().first > KEEP_MS) chunks.removeFirst()
    }

    /** How much is ready to save, in milliseconds (up to 3 minutes). */
    @Synchronized fun bufferedMs(): Long {
        if (chunks.isEmpty()) return 0
        val now = System.currentTimeMillis()
        return minOf(KEEP_MS, now - maxOf(startedAt, now - KEEP_MS))
    }

    fun dir(c: Context) = File(c.filesDir, "radio_rec").apply { mkdirs() }

    /** Saved clips, newest first. */
    fun list(c: Context): List<File> =
        (dir(c).listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()).sortedByDescending { it.lastModified() }

    /** Save the last 3 minutes. Returns the new file, or null if there is nothing yet. */
    fun save(c: Context, stationId: String): File? {
        val data: ByteArray
        val secs: Long
        synchronized(this) {
            if (chunks.isEmpty()) return null
            secs = bufferedMs() / 1000
            val total = chunks.sumOf { it.second.size }
            data = ByteArray(total)
            var p = 0
            for ((_, b) in chunks) { System.arraycopy(b, 0, data, p, b.size); p += b.size }
        }
        // Start at the first audio frame so every player can read the file
        var start = 0
        while (start < data.size - 1 && !((data[start].toInt() and 0xFF) == 0xFF && (data[start + 1].toInt() and 0xE0) == 0xE0)) start++
        if (start >= data.size - 1) start = 0
        val f = File(dir(c), "rec_${System.currentTimeMillis()}_${secs}_$stationId.$ext")
        FileOutputStream(f).use { it.write(data, start, data.size - start) }
        list(c).drop(MAX_FILES).forEach { it.delete() }
        return f
    }

    /** Length in seconds, read from the file name. */
    fun seconds(f: File): Long = f.nameWithoutExtension.split("_").getOrNull(2)?.toLongOrNull() ?: 0
    fun stationId(f: File): String = f.nameWithoutExtension.split("_").getOrNull(3) ?: ""
}

/**
 * Passes the radio stream to the player and copies the sound to [RadioRecorder].
 * Some stations put song titles (ICY metadata) inside the stream; those bytes are skipped.
 */
@OptIn(markerClass = [UnstableApi::class])
class RecordingDataSource(private val up: DataSource) : DataSource {
    private var metaint = 0
    private var toAudio = 0
    private var metaLeft = 0
    private var state = 0 // 0 = audio, 1 = length byte, 2 = metadata

    override fun addTransferListener(l: TransferListener) = up.addTransferListener(l)

    override fun open(spec: DataSpec): Long {
        val n = up.open(spec)
        val h = up.responseHeaders
        fun header(name: String) = h.entries.firstOrNull { it.key?.equals(name, true) == true }?.value?.firstOrNull()
        metaint = header("icy-metaint")?.trim()?.toIntOrNull() ?: 0
        toAudio = metaint
        state = 0
        val type = header("content-type")?.lowercase() ?: ""
        RadioRecorder.ext = if (type.contains("aac")) "aac" else "mp3"
        return n
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val r = up.read(buffer, offset, length)
        if (r > 0) try { feed(buffer, offset, r) } catch (_: Exception) {}
        return r
    }

    private fun feed(b: ByteArray, off: Int, len: Int) {
        if (metaint <= 0) { RadioRecorder.add(b, off, len); return }
        var i = off
        val end = off + len
        while (i < end) {
            when (state) {
                0 -> {
                    val n = minOf(toAudio, end - i)
                    RadioRecorder.add(b, i, n)
                    i += n; toAudio -= n
                    if (toAudio == 0) state = 1
                }
                1 -> {
                    metaLeft = (b[i].toInt() and 0xFF) * 16
                    i++
                    if (metaLeft > 0) state = 2 else { state = 0; toAudio = metaint }
                }
                else -> {
                    val n = minOf(metaLeft, end - i)
                    i += n; metaLeft -= n
                    if (metaLeft == 0) { state = 0; toAudio = metaint }
                }
            }
        }
    }

    override fun getUri(): Uri? = up.uri
    override fun getResponseHeaders(): Map<String, List<String>> = up.responseHeaders
    override fun close() = up.close()
}
