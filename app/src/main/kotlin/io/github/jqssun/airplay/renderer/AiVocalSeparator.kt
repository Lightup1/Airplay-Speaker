package io.github.jqssun.airplay.renderer

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Buffered real-time wrapper around the fixed-window MDX vocal model.
 *
 * AirPlay delivers tiny PCM packets while MDX needs about 5.9 seconds of
 * future context. Input, inference and AudioTrack delivery therefore run on
 * separate threads. If the first measured chunk is slower than the 5.25 s
 * output stride, the pipeline keeps ordering intact but switches the rest of
 * the session to the allocation-free DSP reducer.
 */
internal class AiVocalSeparator(
    context: Context,
    private val output: (pcm: ByteArray, aiApplied: Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val inputQueue = LinkedBlockingQueue<InputPacket>()
    private val outputQueue = LinkedBlockingQueue<PcmBlock>(MAX_OUTPUT_BLOCKS)
    private val generation = AtomicLong(1L)
    private val receivedFrames = AtomicLong(0L)
    private val deliveredFrames = AtomicLong(0L)
    private val queuedInputBytes = AtomicLong(0L)
    private val crossfadeWindow = buildMdxCrossfadeWindow()

    @Volatile
    private var running = true

    @Volatile
    private var handlingSession = false

    @Volatile
    private var forceBypass = false

    @Volatile
    var statusLabel: String = "AI ready"
        private set

    private var model: MdxLiteRtModel? = null
    private var chunkProcessor: MdxVocalChunkProcessor? = null

    private val inferenceThread = thread(
        start = true,
        isDaemon = true,
        name = "AirPlay-AI-Separation",
        block = ::inferenceLoop,
    )
    private val outputThread = thread(
        start = true,
        isDaemon = true,
        name = "AirPlay-AI-Output",
        block = ::outputLoop,
    )

    /**
     * Returns true when this packet is owned by the ordered AI pipeline.
     * Once a session enters bypass it remains ordered here until disconnect;
     * sending later packets directly would overtake its buffered audio.
     */
    @Synchronized
    fun offer(
        pcm: ByteArray,
        reduction: Float,
        aiRequested: Boolean,
    ): Boolean {
        if (!running || pcm.size < STEREO_FRAME_BYTES) return false
        if (!aiRequested && !handlingSession) return false
        if (!handlingSession) {
            handlingSession = true
            forceBypass = false
            receivedFrames.set(0L)
            deliveredFrames.set(0L)
            queuedInputBytes.set(0L)
            updateStatus("AI buffering")
        } else if (!aiRequested) {
            requestBypass("AI disabled · Smart Vocal Focus")
        }

        val alignedBytes = pcm.size - pcm.size % STEREO_FRAME_BYTES
        if (alignedBytes <= 0) return true
        val packetData = if (alignedBytes == pcm.size) pcm else pcm.copyOf(alignedBytes)
        val currentGeneration = generation.get()
        receivedFrames.addAndGet((alignedBytes / STEREO_FRAME_BYTES).toLong())
        val queued = queuedInputBytes.addAndGet(alignedBytes.toLong())
        if (queued > MAX_QUEUED_INPUT_BYTES) {
            requestBypass("AI too slow · Smart Vocal Focus")
        }
        inputQueue.offer(
            InputPacket(
                generation = currentGeneration,
                pcm = packetData,
                reduction = reduction.coerceIn(0f, 1f),
                endOfStream = false,
            )
        )
        return true
    }

    @Synchronized
    fun finish() {
        if (!running || !handlingSession) return
        inputQueue.offer(
            InputPacket(
                generation = generation.get(),
                pcm = ByteArray(0),
                reduction = 0f,
                endOfStream = true,
            )
        )
    }

    fun bufferedAudioMs(): Long {
        if (!handlingSession) return 0L
        val pending = (receivedFrames.get() - deliveredFrames.get()).coerceAtLeast(0L)
        return (pending * 1_000L / MdxVocalChunkProcessor.SAMPLE_RATE)
            .coerceAtMost(MAX_REPORTED_BUFFER_MS)
    }

    @Synchronized
    fun reset() {
        generation.incrementAndGet()
        handlingSession = false
        forceBypass = false
        receivedFrames.set(0L)
        deliveredFrames.set(0L)
        queuedInputBytes.set(0L)
        inputQueue.clear()
        outputQueue.clear()
        updateStatus("AI ready")
    }

    private fun inferenceLoop() {
        var state = SessionState(generation.get())
        try {
            while (running) {
                try {
                    val packet = inputQueue.poll(150L, TimeUnit.MILLISECONDS) ?: continue
                    queuedInputBytes.addAndGet(-packet.pcm.size.toLong())
                    if (packet.generation != generation.get()) continue
                    if (state.generation != packet.generation) {
                        state = SessionState(packet.generation)
                    }
                    if (packet.endOfStream) {
                        finishSession(state)
                        continue
                    }
                    if (!state.timeline.append(packet.pcm)) {
                        requestBypass("AI memory guard · Smart Vocal Focus")
                        emitOriginalAvailable(state)
                        if (!state.timeline.append(packet.pcm)) {
                            throw IllegalStateException("Unable to drain the AI audio timeline")
                        }
                    }
                    state.reduction = packet.reduction

                    if (forceBypass) {
                        emitOriginalAvailable(state)
                        continue
                    }
                    if (!ensureProcessor()) {
                        requestBypass("AI unavailable · Smart Vocal Focus")
                        emitOriginalAvailable(state)
                        continue
                    }
                    processReadyChunks(state)
                    if (forceBypass) emitOriginalAvailable(state)
                } catch (_: InterruptedException) {
                    // reset/close wakes the loop; generation checks discard stale work.
                } catch (error: Throwable) {
                    Log.e(TAG, "AI separation pipeline failed; using DSP fallback", error)
                    requestBypass("AI error · Smart Vocal Focus")
                    runCatching { emitOriginalAvailable(state) }
                }
            }
        } finally {
            closeModel()
        }
    }

    private fun ensureProcessor(): Boolean {
        if (chunkProcessor != null) return true
        if (!isAiVocalSeparationSupported()) {
            updateStatus("AI needs a 64-bit TV · Smart Vocal Focus")
            return false
        }
        if (availableMemoryBytes() < MIN_MEMORY_TO_START_BYTES) {
            Log.w(TAG, "Not starting MDX: low available memory")
            return false
        }
        updateStatus("AI model loading")
        return try {
            val newModel = MdxLiteRtModel(appContext, ::updateStatus)
            if (newModel.warmUpMs > MAX_REALTIME_PROCESS_MS) {
                updateStatus(
                    "AI ${newModel.warmUpMs}ms · fallback to Smart Vocal Focus"
                )
                newModel.close()
                return false
            }
            model = newModel
            chunkProcessor = MdxVocalChunkProcessor(newModel)
            updateStatus("AI Vocal Separation · ${newModel.backendLabel}")
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to initialize UVR MDX-Net", error)
            closeModel()
            false
        }
    }

    private fun processReadyChunks(state: SessionState) {
        val processor = chunkProcessor ?: return
        while (!forceBypass && state.generation == generation.get()) {
            val audioStart = state.nextPaddedStart - MdxVocalChunkProcessor.TRIM
            val requiredEnd = state.nextPaddedStart +
                MdxVocalChunkProcessor.CHUNK_SIZE -
                MdxVocalChunkProcessor.TRIM
            if (state.timeline.endFrame < requiredEnd) return

            state.leftChunk.fill(0f)
            state.rightChunk.fill(0f)
            val copyStart = audioStart.coerceAtLeast(0L)
            val destinationOffset = (copyStart - audioStart).toInt()
            state.timeline.copyFloats(
                startFrame = copyStart,
                endFrame = requiredEnd,
                leftDestination = state.leftChunk,
                rightDestination = state.rightChunk,
                destinationOffset = destinationOffset,
            )

            updateStatus("AI separating vocals")
            val started = SystemClock.elapsedRealtime()
            val separated = processor.process(
                leftChunk = state.leftChunk,
                rightChunk = state.rightChunk,
                reduction = state.reduction,
            )
            val elapsed = SystemClock.elapsedRealtime() - started
            if (state.generation != generation.get()) return

            enqueueSeparatedFrames(
                state = state,
                current = separated,
                framesToEmit = MdxVocalChunkProcessor.STRIDE,
                preserveTail = true,
            )
            state.scheduledFrames += MdxVocalChunkProcessor.STRIDE
            state.nextPaddedStart += MdxVocalChunkProcessor.STRIDE
            state.timeline.discardBefore(
                (state.nextPaddedStart - MdxVocalChunkProcessor.TRIM).coerceAtLeast(0L)
            )
            updateStatus(
                "AI Vocal Separation · ${model?.backendLabel ?: "adaptive"} · ${elapsed}ms"
            )
            Log.i(
                TAG,
                "MDX chunk=${elapsed}ms, stride=${STRIDE_DURATION_MS}ms, " +
                    "buffer=${bufferedAudioMs()}ms",
            )
            if (elapsed > MAX_REALTIME_PROCESS_MS) {
                requestBypass("AI ${elapsed}ms · fallback to Smart Vocal Focus")
                closeModel()
            }
        }
    }

    private fun finishSession(state: SessionState) {
        if (forceBypass) {
            emitOriginalAvailable(state)
            return
        }
        if (!ensureProcessor()) {
            requestBypass("AI unavailable · Smart Vocal Focus")
            emitOriginalAvailable(state)
            return
        }
        processReadyChunks(state)
        if (forceBypass) {
            emitOriginalAvailable(state)
            return
        }
        if (state.scheduledFrames >= state.timeline.endFrame) return

        val processor = chunkProcessor ?: return
        val audioStart = state.nextPaddedStart - MdxVocalChunkProcessor.TRIM
        val requiredEnd = state.nextPaddedStart +
            MdxVocalChunkProcessor.CHUNK_SIZE -
            MdxVocalChunkProcessor.TRIM
        state.leftChunk.fill(0f)
        state.rightChunk.fill(0f)
        val copyStart = audioStart
            .coerceAtLeast(0L)
            .coerceAtLeast(state.timeline.baseFrame)
        val copyEnd = requiredEnd.coerceAtMost(state.timeline.endFrame)
        if (copyEnd > copyStart) {
            state.timeline.copyFloats(
                startFrame = copyStart,
                endFrame = copyEnd,
                leftDestination = state.leftChunk,
                rightDestination = state.rightChunk,
                destinationOffset = (copyStart - audioStart).toInt(),
            )
        }

        updateStatus("AI finishing track")
        val started = SystemClock.elapsedRealtime()
        val separated = processor.process(
            leftChunk = state.leftChunk,
            rightChunk = state.rightChunk,
            reduction = state.reduction,
        )
        val elapsed = SystemClock.elapsedRealtime() - started
        if (state.generation != generation.get()) return
        val remainingFrames = (state.timeline.endFrame - state.scheduledFrames)
            .coerceAtMost(MdxVocalChunkProcessor.GENERATED_SIZE.toLong())
            .toInt()
        enqueueSeparatedFrames(
            state = state,
            current = separated,
            framesToEmit = remainingFrames,
            preserveTail = false,
        )
        state.scheduledFrames += remainingFrames
        state.timeline.discardBefore(state.scheduledFrames)
        updateStatus(
            "AI Vocal Separation · ${model?.backendLabel ?: "adaptive"} · ${elapsed}ms"
        )
    }

    private fun enqueueSeparatedFrames(
        state: SessionState,
        current: StereoFloatBlock,
        framesToEmit: Int,
        preserveTail: Boolean,
    ) {
        var offset = 0
        while (offset < framesToEmit) {
            val frames = minOf(
                OUTPUT_BLOCK_FRAMES,
                framesToEmit - offset,
            )
            val bytes = ByteArray(frames * STEREO_FRAME_BYTES)
            for (frame in 0 until frames) {
                val index = offset + frame
                val left: Float
                val right: Float
                if (state.previousTailLeft == null ||
                    index >= MdxVocalChunkProcessor.OVERLAP_SIZE
                ) {
                    left = current.left[index]
                    right = current.right[index]
                } else {
                    val previousIndex = index
                    val previousWeight = crossfadeWindow[
                        MdxVocalChunkProcessor.STRIDE + previousIndex
                    ]
                    val currentWeight = crossfadeWindow[previousIndex]
                    val weight = previousWeight + currentWeight
                    left = (
                        state.previousTailLeft!![previousIndex] * previousWeight +
                            current.left[previousIndex] * currentWeight
                        ) / weight
                    right = (
                        state.previousTailRight!![previousIndex] * previousWeight +
                            current.right[previousIndex] * currentWeight
                        ) / weight
                }
                writePcm16(bytes, frame * STEREO_FRAME_BYTES, left)
                writePcm16(bytes, frame * STEREO_FRAME_BYTES + 2, right)
            }
            putOutput(PcmBlock(state.generation, bytes, frames, aiApplied = true))
            offset += frames
        }

        if (preserveTail) {
            if (state.previousTailLeft == null) {
                state.previousTailLeft = FloatArray(MdxVocalChunkProcessor.OVERLAP_SIZE)
                state.previousTailRight = FloatArray(MdxVocalChunkProcessor.OVERLAP_SIZE)
            }
            for (i in 0 until MdxVocalChunkProcessor.OVERLAP_SIZE) {
                val source = MdxVocalChunkProcessor.STRIDE + i
                state.previousTailLeft!![i] = current.left[source]
                state.previousTailRight!![i] = current.right[source]
            }
        }
    }

    private fun emitOriginalAvailable(state: SessionState) {
        while (state.scheduledFrames < state.timeline.endFrame &&
            state.generation == generation.get()
        ) {
            val frames = minOf(
                OUTPUT_BLOCK_FRAMES.toLong(),
                state.timeline.endFrame - state.scheduledFrames,
            ).toInt()
            val bytes = state.timeline.copyPcm16(state.scheduledFrames, frames)
            putOutput(PcmBlock(state.generation, bytes, frames, aiApplied = false))
            state.scheduledFrames += frames
            state.timeline.discardBefore(state.scheduledFrames)
        }
    }

    private fun putOutput(block: PcmBlock) {
        while (running && block.generation == generation.get()) {
            if (outputQueue.offer(block, 150L, TimeUnit.MILLISECONDS)) return
        }
    }

    private fun outputLoop() {
        while (running) {
            try {
                val block = outputQueue.poll(150L, TimeUnit.MILLISECONDS) ?: continue
                if (block.generation != generation.get()) continue
                output(block.pcm, block.aiApplied)
                if (block.generation == generation.get()) {
                    deliveredFrames.addAndGet(block.frames.toLong())
                }
            } catch (_: InterruptedException) {
                // close/reset
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to deliver AI PCM", error)
            }
        }
    }

    @Synchronized
    private fun requestBypass(message: String) {
        if (!forceBypass) {
            forceBypass = true
            updateStatus(message)
            Log.w(TAG, message)
        }
    }

    private fun updateStatus(value: String) {
        statusLabel = value
        onStatus(value)
    }

    private fun availableMemoryBytes(): Long {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also(manager::getMemoryInfo).availMem
    }

    private fun closeModel() {
        chunkProcessor = null
        model?.let { runCatching { it.close() } }
        model = null
    }

    override fun close() {
        running = false
        reset()
        inferenceThread.interrupt()
        outputThread.interrupt()
        runCatching { inferenceThread.join(1_000L) }
        runCatching { outputThread.join(1_000L) }
    }

    private class SessionState(
        val generation: Long,
        val timeline: ShortStereoTimeline = ShortStereoTimeline(TIMELINE_CAPACITY_FRAMES),
        val leftChunk: FloatArray = FloatArray(MdxVocalChunkProcessor.CHUNK_SIZE),
        val rightChunk: FloatArray = FloatArray(MdxVocalChunkProcessor.CHUNK_SIZE),
        var nextPaddedStart: Long = 0L,
        var scheduledFrames: Long = 0L,
        var reduction: Float = 1f,
        var previousTailLeft: FloatArray? = null,
        var previousTailRight: FloatArray? = null,
    )

    private data class InputPacket(
        val generation: Long,
        val pcm: ByteArray,
        val reduction: Float,
        val endOfStream: Boolean,
    )

    private data class PcmBlock(
        val generation: Long,
        val pcm: ByteArray,
        val frames: Int,
        val aiApplied: Boolean,
    )

    private class ShortStereoTimeline(private val capacity: Int) {
        private val left = ShortArray(capacity)
        private val right = ShortArray(capacity)
        var baseFrame: Long = 0L
            private set
        var endFrame: Long = 0L
            private set

        fun append(pcm: ByteArray): Boolean {
            val frames = pcm.size / STEREO_FRAME_BYTES
            if (endFrame - baseFrame + frames > capacity) return false
            repeat(frames) { frame ->
                val offset = frame * STEREO_FRAME_BYTES
                val absolute = endFrame + frame
                val index = (absolute % capacity).toInt()
                left[index] = readPcm16(pcm, offset)
                right[index] = readPcm16(pcm, offset + 2)
            }
            endFrame += frames
            return true
        }

        fun copyFloats(
            startFrame: Long,
            endFrame: Long,
            leftDestination: FloatArray,
            rightDestination: FloatArray,
            destinationOffset: Int,
        ) {
            require(startFrame >= baseFrame && endFrame <= this.endFrame)
            val frames = (endFrame - startFrame).toInt()
            repeat(frames) { frame ->
                val index = ((startFrame + frame) % capacity).toInt()
                leftDestination[destinationOffset + frame] = left[index] / 32768f
                rightDestination[destinationOffset + frame] = right[index] / 32768f
            }
        }

        fun copyPcm16(startFrame: Long, frames: Int): ByteArray {
            require(startFrame >= baseFrame && startFrame + frames <= endFrame)
            return ByteArray(frames * STEREO_FRAME_BYTES).also { bytes ->
                repeat(frames) { frame ->
                    val index = ((startFrame + frame) % capacity).toInt()
                    writeShort(bytes, frame * STEREO_FRAME_BYTES, left[index])
                    writeShort(bytes, frame * STEREO_FRAME_BYTES + 2, right[index])
                }
            }
        }

        fun discardBefore(frame: Long) {
            baseFrame = frame.coerceIn(baseFrame, endFrame)
        }
    }

    companion object {
        private const val TAG = "AiVocalSeparator"
        private const val STEREO_FRAME_BYTES = 4
        private const val OUTPUT_BLOCK_FRAMES = 4_096
        private const val MAX_OUTPUT_BLOCKS = 128
        private const val TIMELINE_CAPACITY_FRAMES = 1_048_576
        private const val MAX_QUEUED_INPUT_BYTES = 2L * 1_024L * 1_024L
        private const val MIN_MEMORY_TO_START_BYTES = 220L * 1_024L * 1_024L
        private const val MAX_REPORTED_BUFFER_MS = 20_000L
        private const val STRIDE_DURATION_MS =
            MdxVocalChunkProcessor.STRIDE * 1_000L / MdxVocalChunkProcessor.SAMPLE_RATE
        private const val MAX_REALTIME_PROCESS_MS = 5_050L

        private fun readPcm16(data: ByteArray, offset: Int): Short =
            (((data[offset + 1].toInt() and 0xff) shl 8) or
                (data[offset].toInt() and 0xff)).toShort()

        private fun writePcm16(data: ByteArray, offset: Int, value: Float) {
            val pcm = (value.coerceIn(-1f, 0.9999695f) * 32768f)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            writeShort(data, offset, pcm)
        }

        private fun writeShort(data: ByteArray, offset: Int, value: Short) {
            val sample = value.toInt()
            data[offset] = (sample and 0xff).toByte()
            data[offset + 1] = ((sample ushr 8) and 0xff).toByte()
        }
    }
}
