package io.github.jqssun.airplay.renderer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import io.github.jqssun.airplay.bridge.NativeBridge
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.pow

internal fun resolveTrackGain(
    senderVolume: Float,
    appVolume: Float,
): Float = (senderVolume * appVolume).coerceIn(0f, 1f)

class AudioRenderer {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var currentCt = -1
    private var currentPcmFormat: Triple<Int, Int, Int>? = null
    private var outputChannels = 2
    private var failedCt = -1
    private var swAlacHandle = 0L  // software ALAC decoder handle
    private val spatialEnhancer = StereoSpatialEnhancer()
    @Volatile var swAlacEnabled = true
    @Volatile var audioBufferMultiplier = 4
    @Volatile var realtimeDecoderPriority = true
    @Volatile var losslessOutput = true
    @Volatile var spatialEnhancementEnabled = false; private set
    @Volatile var volume = 1.0f; private set
    @Volatile var appVolume = 1.0f; private set
    @Volatile var codecLabel = ""; private set
    @Volatile var outputPathLabel = ""; private set
    private var baseOutputPathLabel = ""
    @Volatile private var pendingReset = false

    fun markSessionEnded() { pendingReset = true }

    fun flushPcm() {
        track?.let {
            try {
                it.pause()
                it.flush()
                it.play()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Unable to flush PCM output", e)
            }
        }
    }

    fun feedPcm(data: ByteArray, bits: Int, channels: Int, sampleRate: Int) {
        if (pendingReset) {
            pendingReset = false
            stop()
        }
        val format = Triple(bits, channels, sampleRate)
        if (currentCt != CT_PCM || currentPcmFormat != format || track == null) {
            stop()
            if (bits != 16 || channels !in 1..2 || sampleRate <= 0) {
                Log.e(TAG, "Unsupported PCM format: $bits-bit, $channels ch, $sampleRate Hz")
                return
            }
            currentCt = CT_PCM
            currentPcmFormat = format
            codecLabel = "ALAC lossless · ${bits}-bit / ${formatSampleRate(sampleRate)}"
            _ensureAudioTrack(sampleRate, channels)
        }
        writePcm(data)
    }

    fun feedAudio(data: ByteArray, ct: Int, ntpTimeNs: Long) {
        if (pendingReset) {
            pendingReset = false
            stop()
        }
        if (ct != currentCt || (codec == null && swAlacHandle == 0L)) {
            if (ct == failedCt) {
                if (!_ensureSoftwareAlac(ct)) return
            } else {
                stop()
                start(ct)
            }
        }

        // software alac path
        if (swAlacHandle != 0L && ct == CT_ALAC) {
            _feedSoftwareAlac(data)
            return
        }

        // MediaCodec path
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(5000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
            }
            drainOutput()
        } catch (_: IllegalStateException) {
            codec = null
            failedCt = ct
            if (_ensureSoftwareAlac(ct)) _feedSoftwareAlac(data)
        }
    }

    /** Try to start software ALAC if applicable. Returns true if SW decoder is ready. */
    private fun _ensureSoftwareAlac(ct: Int): Boolean {
        if (ct != CT_ALAC || !swAlacEnabled) return false
        if (swAlacHandle != 0L) return true
        _startSoftwareAlac()
        return swAlacHandle != 0L
    }

    private fun _feedSoftwareAlac(data: ByteArray) {
        val pcm = NativeBridge.nativeAlacDecode(swAlacHandle, data) ?: return
        writePcm(pcm)
    }

    private fun _startSoftwareAlac() {
        Log.i(TAG, "Starting software ALAC decoder")
        swAlacHandle = NativeBridge.nativeAlacInit(352, 2, 16, 40, 10, 14)
        if (swAlacHandle == 0L) {
            Log.e(TAG, "Failed to init software ALAC decoder")
            return
        }
        currentCt = CT_ALAC
        codecLabel = "ALAC lossless · 16-bit / 44.1 kHz (SW)"
        _ensureAudioTrack()
    }

    private fun start(ct: Int) {
        currentCt = ct
        codecLabel = when (ct) {
            CT_ALAC -> "ALAC lossless · 16-bit / 44.1 kHz"
            CT_AAC_LC -> "AAC-LC · lossy"
            CT_AAC_ELD -> "AAC-ELD · lossy"
            else -> "?"
        }
        val format = when (ct) {
            CT_AAC_ELD -> {
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, 39)
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)))
                }
            }
            CT_AAC_LC -> {
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
                }
            }
            CT_ALAC -> {
                MediaFormat.createAudioFormat("audio/alac", 44100, 2).apply {
                    setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
                    setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                    val csd = byteArrayOf(
                        0x00, 0x00, 0x00, 0x24,
                        0x61, 0x6C, 0x61, 0x63,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x01, 0x60,
                        0x00, 0x10, 0x28, 0x0A, 0x0E, 0x02,
                        0x00, 0xFF.toByte(),
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0xAC.toByte(), 0x44,
                    )
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                }
            }
            else -> {
                Log.w(TAG, "Unknown audio codec type: $ct")
                return
            }
        }

        if (realtimeDecoderPriority) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        try {
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also {
                it.configure(format, null, null, 0)
                it.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init audio codec for ct=$ct", e)
            codec = null
            failedCt = ct
            if (ct == CT_ALAC && swAlacEnabled) {
                _startSoftwareAlac()
                return
            }
            return
        }

        _ensureAudioTrack()
        Log.i(TAG, "Audio started: ct=$ct")
    }

    private fun _ensureAudioTrack(sampleRate: Int = 44100, channels: Int = 2) {
        if (track != null) return
        outputChannels = channels
        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channelMask)
            .build()
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufSize <= 0) {
            Log.e(TAG, "Unsupported PCM output: $sampleRate Hz, $channels ch (minBuffer=$bufSize)")
            return
        }
        val frameSize = channels * Short.SIZE_BYTES
        val requestedSize = max(bufSize * audioBufferMultiplier.coerceIn(4, 8), frameSize)
        val alignedSize = requestedSize - (requestedSize % frameSize)
        val newTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(alignedSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create PCM AudioTrack", e)
            return
        }
        if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "PCM AudioTrack was not initialized")
            newTrack.release()
            return
        }

        val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val direct = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            AudioTrack.isDirectPlaybackSupported(fmt, attrs)
        baseOutputPathLabel = when {
            direct && nativeRate == sampleRate -> "PCM · $sampleRate Hz · direct supported"
            nativeRate == sampleRate -> "PCM mixer · native $nativeRate Hz"
            else -> "PCM mixer · $sampleRate → $nativeRate Hz"
        }
        refreshOutputPathLabel()
        Log.i(
            TAG,
            "PCM output: requested=$sampleRate Hz/$channels ch/16-bit, " +
                "native=$nativeRate Hz, directSupported=$direct, buffer=$alignedSize, " +
                "losslessOutput=$losslessOutput",
        )

        track = newTrack.also {
            it.setVolume(effectiveTrackVolume())
            it.play()
        }
    }

    private fun drainOutput() {
        val c = codec ?: return
        if (track == null) return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx >= 0) {
                val buf = c.getOutputBuffer(idx) ?: break
                val pcm = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(pcm)
                writePcm(pcm)
                c.releaseOutputBuffer(idx, false)
            } else {
                break
            }
        }
    }

    /**
     * AudioTrack may legally accept fewer bytes than requested, even for a
     * streaming track. Keep writing until the complete decoded ALAC frame has
     * reached the PCM sink so packet boundaries cannot turn into audio loss.
     */
    private fun writePcm(data: ByteArray) {
        val output = track ?: return
        if (spatialEnhancementEnabled) {
            spatialEnhancer.processInPlace(data, outputChannels)
        }
        var offset = 0
        while (offset < data.size) {
            val written = output.write(
                data,
                offset,
                data.size - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (written <= 0) {
                Log.e(TAG, "PCM write failed at $offset/${data.size}: $written")
                pendingReset = true
                return
            }
            offset += written
        }
    }

    fun setVolume(vol: Float) {
        // max: 0; min: -30; mute: -144
        volume = when {
            vol <= -144f -> 0f
            vol >= 0f -> 1f
            else -> 10f.pow(vol / 20f)
        }
        track?.setVolume(effectiveTrackVolume())
    }

    /**
     * Controls only this receiver's AudioTrack. When both the AirPlay sender
     * and the app are at 100%, decoded PCM stays at unity gain. Lowering
     * either control intentionally attenuates this stream without touching
     * Android TV's system mixer volume.
     */
    fun setAppVolume(value: Float) {
        appVolume = value.coerceIn(0f, 1f)
        track?.setVolume(effectiveTrackVolume())
    }

    /**
     * Widens stereo PCM inside this app. This is intentionally separate from
     * Dolby Atmos: it does not invent object metadata or alter system audio.
     */
    fun setSpatialEnhancementEnabled(enabled: Boolean) {
        spatialEnhancementEnabled = enabled
        refreshOutputPathLabel()
        Log.i(TAG, "Spatial / surround enhancement: ${if (enabled) "on" else "off"}")
    }

    private fun refreshOutputPathLabel() {
        outputPathLabel = if (
            spatialEnhancementEnabled && baseOutputPathLabel.isNotBlank()
        ) {
            "$baseOutputPathLabel · Spatial stereo"
        } else {
            baseOutputPathLabel
        }
    }

    private fun effectiveTrackVolume(): Float =
        resolveTrackGain(volume, appVolume)

    fun stop() {
        track?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        track = null
        codec?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        codec = null
        if (swAlacHandle != 0L) {
            NativeBridge.nativeAlacDestroy(swAlacHandle)
            swAlacHandle = 0L
        }
        currentCt = -1
        currentPcmFormat = null
        outputChannels = 2
        failedCt = -1
        codecLabel = ""
        baseOutputPathLabel = ""
        outputPathLabel = ""
    }

    fun release() = stop()

    companion object {
        private const val TAG = "AudioRenderer"
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
        const val CT_PCM = 0

        private fun formatSampleRate(sampleRate: Int): String =
            if (sampleRate % 1000 == 0) {
                "${sampleRate / 1000} kHz"
            } else {
                "${sampleRate / 1000.0} kHz"
            }
    }
}
