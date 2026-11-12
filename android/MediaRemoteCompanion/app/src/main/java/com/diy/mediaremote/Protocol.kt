package com.diy.mediaremote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.text.Normalizer
import java.util.UUID

/** Must match the "Now Playing" service in firmware/MediaRemote/MediaRemote.ino */
object Protocol {
    val SERVICE: UUID = UUID.fromString("8f1e0001-7c2a-4d55-9b3e-2a6c5e7d9f10")
    val TITLE: UUID = UUID.fromString("8f1e0002-7c2a-4d55-9b3e-2a6c5e7d9f10")
    val ARTIST: UUID = UUID.fromString("8f1e0003-7c2a-4d55-9b3e-2a6c5e7d9f10")
    val STATE: UUID = UUID.fromString("8f1e0004-7c2a-4d55-9b3e-2a6c5e7d9f10")
    val COMMAND: UUID = UUID.fromString("8f1e0005-7c2a-4d55-9b3e-2a6c5e7d9f10")
    val ART: UUID = UUID.fromString("8f1e0006-7c2a-4d55-9b3e-2a6c5e7d9f10")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_SEEK_BACK = 1
    const val CMD_SEEK_FWD = 2

    const val TEXT_MAX = 120

    // Album art: 64 x 64 RGB565 little-endian, sent as 64 chunks of 128 bytes:
    //   [artId][offset lo][offset hi][128 bytes]   or   [artId][0xFF][0xFF] = no art
    const val ART_SIZE = 64
    const val ART_BYTES = ART_SIZE * ART_SIZE * 2
    const val ART_CHUNK = 128
    const val ART_CHUNKS = ART_BYTES / ART_CHUNK
    const val ART_PACKET = 3 + ART_CHUNK          // needs an MTU of at least 134

    /** Centre-crop to a square, scale to 64 x 64 and convert to RGB565 (little-endian). */
    fun artToRgb565(src: Bitmap): ByteArray {
        val side = minOf(src.width, src.height)
        val crop = Rect((src.width - side) / 2, (src.height - side) / 2,
            (src.width + side) / 2, (src.height + side) / 2)
        val out = Bitmap.createBitmap(ART_SIZE, ART_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, crop, Rect(0, 0, ART_SIZE, ART_SIZE),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val px = IntArray(ART_SIZE * ART_SIZE)
        out.getPixels(px, 0, ART_SIZE, 0, 0, ART_SIZE, ART_SIZE)
        out.recycle()
        val b = ByteArray(ART_BYTES)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val bl = c and 0xFF
            val v = ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or (bl shr 3)
            b[2 * i] = (v and 0xFF).toByte()
            b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    fun artChunk(id: Int, art: ByteArray, index: Int): ByteArray {
        val off = index * ART_CHUNK
        val p = ByteArray(ART_PACKET)
        p[0] = id.toByte(); p[1] = (off and 0xFF).toByte(); p[2] = ((off shr 8) and 0xFF).toByte()
        System.arraycopy(art, off, p, 3, ART_CHUNK)
        return p
    }

    fun artNone(id: Int) = byteArrayOf(id.toByte(), 0xFF.toByte(), 0xFF.toByte())
    const val SEEK_STEP_MS = 10_000L

    private val replacements = mapOf(
        '‘' to "'", '’' to "'", '“' to "\"", '”' to "\"",
        '–' to "-", '—' to "-", '…' to "...", 'ß' to "ss",
        'æ' to "ae", 'Æ' to "AE", 'ø' to "o", 'Ø' to "O",
        'ł' to "l", 'Ł' to "L", ' ' to " ", '×' to "x",
    )

    /** The remote's fonts are ASCII only: "Beyoncé – Halo" -> "Beyonce - Halo". */
    fun toAscii(text: String?, maxBytes: Int): ByteArray {
        if (text.isNullOrEmpty()) return ByteArray(0)
        val sb = StringBuilder()
        for (ch in text) sb.append(replacements[ch] ?: ch.toString())
        val stripped = Normalizer.normalize(sb, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val out = StringBuilder()
        for (ch in stripped) {
            out.append(if (ch.code in 0x20..0x7E) ch else '?')
            if (out.length >= maxBytes) break
        }
        return out.toString().trim().toByteArray(Charsets.US_ASCII)
    }

    /** 12-byte little-endian state packet. */
    fun state(
        playing: Boolean, timeValid: Boolean, positionMs: Long, durationMs: Long,
        volume: Int, volumeMax: Int,
    ): ByteArray {
        val b = ByteArray(12)
        b[0] = ((if (playing) 1 else 0) or (if (timeValid) 2 else 0)).toByte()
        putU32(b, 1, positionMs)
        putU32(b, 5, durationMs)
        b[9] = volume.coerceIn(0, 255).toByte()
        b[10] = volumeMax.coerceIn(0, 255).toByte()
        return b
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        val x = v.coerceIn(0L, 0xFFFF_FFFFL)
        b[off] = (x and 0xFF).toByte()
        b[off + 1] = ((x shr 8) and 0xFF).toByte()
        b[off + 2] = ((x shr 16) and 0xFF).toByte()
        b[off + 3] = ((x shr 24) and 0xFF).toByte()
    }
}
