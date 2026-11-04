package com.pod4me.playback

import androidx.media3.extractor.MpegAudioUtil
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Preserve compressed MP3/ADTS frames, without transcoding or cutting through a frame. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class RadioFrames(input: InputStream) {
    private val input = BufferedInputStream(input)
    data class Frame(val bytes: ByteArray, val durationUs: Long, val extension: String)

    fun next(): Frame {
        var skipped = 0
        while (skipped++ < 256 * 1024) {
            input.mark(10)
            val header = input.readNBytes(4)
            if (header.size < 4) throw EOFException("The radio stream ended")
            val bits = header.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 255) }
            val mpeg = MpegAudioUtil.Header()
            if (mpeg.setForHeaderData(bits)) {
                val rest = input.readNBytes(mpeg.frameSize - 4)
                if (rest.size != mpeg.frameSize - 4) throw EOFException("Incomplete radio frame")
                return Frame(header + rest, mpeg.samplesPerFrame * 1_000_000L / mpeg.sampleRate, "mp3")
            }
            if ((header[0].toInt() and 255) == 255 && (header[1].toInt() and 0xf6) == 0xf0) {
                val more = input.readNBytes(3)
                if (more.size < 3) throw EOFException("Incomplete AAC header")
                val rate = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
                    .getOrNull((header[2].toInt() shr 2) and 15)
                val size = ((header[3].toInt() and 3) shl 11) or ((more[0].toInt() and 255) shl 3) or ((more[1].toInt() and 255) shr 5)
                if (rate != null && size >= if ((header[1].toInt() and 1) == 1) 7 else 9) {
                    val rest = input.readNBytes(size - 7)
                    if (rest.size != size - 7) throw EOFException("Incomplete AAC frame")
                    return Frame(header + more + rest, 1024L * ((more[2].toInt() and 3) + 1) * 1_000_000 / rate, "aac")
                }
            }
            input.reset()
            input.read()
        }
        throw IOException("This radio is not an MP3 or AAC stream")
    }
}

/** Some Icecast servers send metadata even when Icy-MetaData: 0 was requested. */
internal class IcyAudioInput(input: InputStream, private val interval: Int) : FilterInputStream(input) {
    private var remaining = interval
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0) {
            val size = `in`.read()
            if (size < 0) return -1
            if (`in`.readNBytes(size * 16).size != size * 16) throw EOFException("Incomplete radio metadata")
            remaining = interval
        }
        val count = `in`.read(bytes, offset, minOf(length, remaining))
        if (count > 0) remaining -= count
        return count
    }
}
