package ch.steigis.dinghy.provider

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Backs a file descriptor for content that is not on this device.
 *
 * Reads are served by range requests against the engine's localhost streaming
 * server, which pulls the covering blocks from peers on demand. That is what
 * lets an unrelated app open a file through the system picker without the file
 * ever having been downloaded.
 */
class RemoteFileCallback(
    private val url: String,
    private val size: Long,
) : ProxyFileDescriptorCallback() {

    /**
     * Readers ask for small chunks -- often 4 KiB at a time -- and one HTTP
     * request each would be pathological, so reads are served from a window
     * fetched around the requested offset.
     */
    private var windowStart = -1L
    private var window: ByteArray? = null

    override fun onGetSize(): Long = size

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (offset >= this.size) return 0
        val wanted = minOf(size.toLong(), this.size - offset).toInt()

        try {
            var copied = 0
            while (copied < wanted) {
                val at = offset + copied
                val buffer = windowFor(at)
                val within = (at - windowStart).toInt()
                val available = buffer.size - within
                if (available <= 0) break
                val take = minOf(available, wanted - copied)
                System.arraycopy(buffer, within, data, copied, take)
                copied += take
            }
            return copied
        } catch (e: IOException) {
            Log.w(TAG, "read failed at $offset", e)
            // The reader sees an I/O error rather than silent truncation, which
            // would look like a corrupt file.
            throw ErrnoException("read", OsConstants.EIO, e)
        }
    }

    private fun windowFor(offset: Long): ByteArray {
        val cached = window
        if (cached != null && offset >= windowStart && offset < windowStart + cached.size) {
            return cached
        }
        val start = offset
        val end = minOf(start + WINDOW_BYTES, size) - 1

        // Blocks come from another device over a link that may have just come
        // up, so a first attempt can fail while the peer connection settles.
        // Failing the whole read for that would surface as a corrupt file.
        var lastError: IOException? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                val fetched = fetchRange(start, end)
                if (fetched.isNotEmpty()) {
                    windowStart = start
                    window = fetched
                    return fetched
                }
                lastError = IOException("empty response for $start-$end")
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "fetch attempt ${attempt + 1} failed for $start-$end: ${e.message}")
            }
            Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
        }
        throw lastError ?: IOException("could not read $start-$end")
    }

    private fun fetchRange(start: Long, endInclusive: Long): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.setRequestProperty("Range", "bytes=$start-$endInclusive")
            // The engine's server is local and uncompressed; asking for gzip
            // only invites a mismatch between declared and actual lengths.
            connection.setRequestProperty("Accept-Encoding", "identity")
            // Each read is a fresh connection. Pooled sockets to a server that
            // closes idle connections surface as "unexpected end of stream".
            connection.setRequestProperty("Connection", "close")
            connection.useCaches = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            val code = connection.responseCode
            Log.d(
                TAG,
                "req bytes=$start-$endInclusive -> code=$code len=${connection.contentLengthLong} " +
                    "range=${connection.getHeaderField("Content-Range")} " +
                    "enc=${connection.contentEncoding} url=$url",
            )
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                throw IOException("streaming server returned $code")
            }

            val expected = (endInclusive - start + 1).toInt()
            val buffer = ByteArray(expected)
            var filled = 0
            connection.inputStream.use { input ->
                while (filled < expected) {
                    val n = input.read(buffer, filled, expected - filled)
                    if (n < 0) break
                    filled += n
                }
            }
            Log.d(TAG, "fetched $start-$endInclusive code=$code got=$filled/$expected")
            if (filled == expected) buffer else buffer.copyOf(filled)
        } finally {
            connection.disconnect()
        }
    }

    override fun onRelease() {
        window = null
    }

    private companion object {
        const val TAG = "RemoteFile"
        const val WINDOW_BYTES = 1L shl 20 // 1 MiB
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 400L
    }
}
