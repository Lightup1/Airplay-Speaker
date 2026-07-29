package io.github.jqssun.airplay.renderer

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File

internal fun isAiVocalSeparationSupported(): Boolean = Process.is64Bit()

/**
 * LiteRT runner for the compact UVR-MDXNET 9482 vocal model.
 *
 * GPU backends are attempted explicitly before XNNPACK CPU, so a successful
 * session is observable and a driver failure can fall back without crashing
 * the receiver.
 */
internal class MdxLiteRtModel(
    context: Context,
    private val onStatus: (String) -> Unit,
) : MdxSpectrogramModel, AutoCloseable {
    private val appContext = context.applicationContext
    private val environment = run {
        check(isAiVocalSeparationSupported()) {
            "Real-time UVR MDX-Net requires a 64-bit Android TV"
        }
        Environment.create()
    }
    private var compiledModel: CompiledModel? = null
    private var inputs: List<TensorBuffer> = emptyList()
    private var outputs: List<TensorBuffer> = emptyList()

    @Volatile
    var backendLabel: String = "initializing"
        private set

    @Volatile
    var warmUpMs: Long = 0L
        private set

    init {
        val modelPath = materializeModel().absolutePath

        val attempts = listOf(
            Attempt(
                label = "GPU",
                options = CompiledModel.Options(Accelerator.GPU).apply {
                    gpuOptions = CompiledModel.GpuOptions(
                        precision = CompiledModel.GpuOptions.Precision.FP16,
                        backend = CompiledModel.GpuOptions.Backend.AUTOMATIC,
                        bufferStorageType =
                            CompiledModel.GpuOptions.BufferStorageType.BUFFER,
                    )
                },
            ),
            Attempt(
                label = "GPU OpenGL",
                options = CompiledModel.Options(Accelerator.GPU).apply {
                    gpuOptions = CompiledModel.GpuOptions(
                        precision = CompiledModel.GpuOptions.Precision.FP16,
                        backend = CompiledModel.GpuOptions.Backend.OPENGL,
                        bufferStorageType =
                            CompiledModel.GpuOptions.BufferStorageType.BUFFER,
                    )
                },
            ),
            Attempt(
                label = "CPU",
                options = CompiledModel.Options(Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(numThreads = CPU_THREADS)
                },
            ),
        )

        var lastFailure: Throwable? = null
        for (attempt in attempts) {
            onStatus("AI model · trying ${attempt.label}")
            try {
                openAndWarmUp(modelPath, attempt)
                backendLabel = attempt.label
                onStatus("AI Vocal Separation · $backendLabel")
                Log.i(TAG, "UVR MDX-Net ready on $backendLabel (warm-up ${warmUpMs}ms)")
                lastFailure = null
                break
            } catch (error: Throwable) {
                lastFailure = error
                closeSession()
                Log.w(TAG, "LiteRT ${attempt.label} initialization failed", error)
            }
        }
        if (lastFailure != null || compiledModel == null) {
            close()
            throw IllegalStateException("No LiteRT accelerator could load UVR MDX-Net", lastFailure)
        }
    }

    @Synchronized
    override fun run(input: FloatArray): FloatArray {
        require(input.size == MdxVocalChunkProcessor.MODEL_FLOATS)
        val model = checkNotNull(compiledModel) { "MDX model is closed" }
        inputs.single().writeFloat(input)
        model.run(inputs, outputs)
        return outputs.single().readFloat()
    }

    private fun openAndWarmUp(modelPath: String, attempt: Attempt) {
        val model = CompiledModel.create(modelPath, attempt.options, environment)
        val modelInputs = model.createInputBuffers()
        val modelOutputs = model.createOutputBuffers()
        check(modelInputs.size == 1 && modelOutputs.size == 1) {
            "UVR MDX-Net must have exactly one input and one output"
        }

        compiledModel = model
        inputs = modelInputs
        outputs = modelOutputs
        val zeroes = FloatArray(MdxVocalChunkProcessor.MODEL_FLOATS)
        val started = SystemClock.elapsedRealtime()
        modelInputs.single().writeFloat(zeroes)
        model.run(modelInputs, modelOutputs)
        val warmOutput = modelOutputs.single().readFloat()
        check(warmOutput.size == MdxVocalChunkProcessor.MODEL_FLOATS) {
            "Unexpected UVR MDX-Net output: ${warmOutput.size}"
        }
        warmUpMs = SystemClock.elapsedRealtime() - started
    }

    private fun materializeModel(): File {
        val directory = File(appContext.filesDir, MODEL_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            error("Unable to create AI model directory")
        }
        val destination = File(directory, MODEL_FILE)
        val expectedSize = appContext.assets.openFd(MODEL_ASSET).use { it.length }
        if (!destination.exists() || destination.length() != expectedSize) {
            val temporary = File(directory, "$MODEL_FILE.tmp")
            appContext.assets.open(MODEL_ASSET).use { input ->
                temporary.outputStream().buffered().use(input::copyTo)
            }
            if (destination.exists() && !destination.delete()) {
                temporary.delete()
                error("Unable to replace old AI model")
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                error("Unable to install AI model")
            }
        }
        return destination
    }

    private fun closeSession() {
        outputs.forEach { runCatching { it.close() } }
        inputs.forEach { runCatching { it.close() } }
        outputs = emptyList()
        inputs = emptyList()
        compiledModel?.let { runCatching { it.close() } }
        compiledModel = null
    }

    @Synchronized
    override fun close() {
        closeSession()
        runCatching { environment.close() }
    }

    private data class Attempt(
        val label: String,
        val options: CompiledModel.Options,
    )

    private companion object {
        const val TAG = "MdxLiteRtModel"
        const val MODEL_DIRECTORY = "ai-models"
        const val MODEL_FILE = "UVR_MDXNET_9482.fp16acc.tflite"
        const val MODEL_ASSET = "models/$MODEL_FILE"
        const val CPU_THREADS = 3
    }
}
