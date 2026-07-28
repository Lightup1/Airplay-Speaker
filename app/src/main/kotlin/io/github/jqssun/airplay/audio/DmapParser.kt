package io.github.jqssun.airplay.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parses DMAP (iTunes metadata) binary TLV format into a tag->value map. */
object DmapParser {

    // tags whose payload is an integer (1/2/4/8 bytes)
    private val INT_TAGS = setOf(
        "astm", // duration ms
        "astn", // track number
        "asdk", // data kind
        "asts", // ???
        "miid", // item id
        "mcti", // container item id
        "mper", // persistent id
        "asai", // album id
        "asri", // artist id
        "asci", // composer id
        "asgi", // genre id
    )

    // tags that are containers (contain nested dmap items)
    private val CONTAINER_TAGS = setOf("mlit", "mcon", "mlcl", "ceQR", "ceQS")

    fun parse(data: ByteArray): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        while (buf.remaining() >= 8) {
            val tagBytes = ByteArray(4)
            buf.get(tagBytes)
            val tag = String(tagBytes, Charsets.US_ASCII)
            val len = buf.int
            if (len < 0 || len > buf.remaining()) break
            val payload = ByteArray(len)
            buf.get(payload)
            result[tag] = when {
                tag in CONTAINER_TAGS -> parse(payload)
                tag in INT_TAGS -> _readInt(payload)
                else -> String(payload, Charsets.UTF_8)
            }
        }
        return result
    }

    /**
     * Extracts the song rows returned by DACP's playqueue-contents endpoint.
     *
     * Queue responses contain repeated `mlit` containers, which cannot be
     * represented by [parse]'s metadata map without overwriting siblings.
     * Walking only known DMAP containers keeps malformed text payloads from
     * being mistaken for nested binary data.
     */
    fun parseQueueItems(data: ByteArray): List<TrackInfo> {
        val result = mutableListOf<TrackInfo>()

        fun walk(payload: ByteArray) {
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            while (buf.remaining() >= 8) {
                val tagBytes = ByteArray(4)
                buf.get(tagBytes)
                val tag = String(tagBytes, Charsets.US_ASCII)
                val len = buf.int
                if (len < 0 || len > buf.remaining()) break
                val value = ByteArray(len)
                buf.get(value)

                if (tag == "mlit") {
                    val item = parse(value)
                    val title = item["ceQn"] as? String ?: ""
                    val artist = item["ceQr"] as? String ?: ""
                    val album = item["ceQa"] as? String ?: ""
                    if (title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()) {
                        result += TrackInfo(
                            title = title,
                            artist = artist,
                            album = album,
                            genre = item["ceQg"] as? String ?: "",
                            durationMs = item["astm"] as? Long ?: 0L,
                        )
                    }
                } else if (tag in CONTAINER_TAGS) {
                    walk(value)
                }
            }
        }

        walk(data)
        return result
    }

    /**
     * Returns the sender-provided source of the main Up Next section.
     *
     * Apple DACP uses `ceQh` for labels such as "from Favourites". Generic
     * sources ("from Music", "Up Next") are deliberately ignored so the UI
     * can fall back to the current album instead of inventing a playlist.
     */
    fun parseQueueName(data: ByteArray): String? {
        var result: String? = null

        fun usefulName(raw: String?): String? {
            val candidate = raw
                ?.trim()
                ?.replace(Regex("^from\\s+", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.trim('"', '\'', '“', '”')
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return candidate.takeUnless {
                it.lowercase() in GENERIC_QUEUE_NAMES
            }
        }

        fun walk(payload: ByteArray) {
            if (result != null) return
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            while (buf.remaining() >= 8 && result == null) {
                val tagBytes = ByteArray(4)
                buf.get(tagBytes)
                val tag = String(tagBytes, Charsets.US_ASCII)
                val len = buf.int
                if (len < 0 || len > buf.remaining()) break
                val value = ByteArray(len)
                buf.get(value)

                if (tag == "mlit") {
                    val section = parse(value)
                    if ((section["ceQk"] as? String).equals("main", ignoreCase = true)) {
                        result = usefulName(section["ceQh"] as? String)
                            ?: usefulName(section["ceQl"] as? String)
                    }
                } else if (tag in CONTAINER_TAGS) {
                    walk(value)
                }
            }
        }

        walk(data)
        return result
    }

    private fun _readInt(b: ByteArray): Long {
        var v = 0L
        for (byte in b) v = (v shl 8) or (byte.toLong() and 0xFF)
        return v
    }

    private val GENERIC_QUEUE_NAMES = setOf(
        "music",
        "apple music",
        "library",
        "queue",
        "up next",
        "now playing",
        "previously played",
    )
}
