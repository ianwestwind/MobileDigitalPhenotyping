package edu.stanford.screenomics.edge

import android.content.Context
import edu.stanford.screenomics.core.edge.TfliteInferenceResult
import edu.stanford.screenomics.core.edge.TfliteInferenceTrace
import edu.stanford.screenomics.core.edge.TfliteInterpreterBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter

/**
 * Android TensorFlow Lite bridge for [edu.stanford.screenomics.core.edge.EdgeComputationEngine].
 * Loads models from **assets**; reuses [Interpreter] instances per asset path under a [Mutex].
 *
 * Uses [Interpreter.run] with tensor-shaped arrays — [org.tensorflow.lite.Tensor] in this artifact
 * does not expose a direct `buffer()` API compatible with the previous float-buffer path.
 */
class AndroidTfliteInterpreterBridge(
    appContext: Context,
) : TfliteInterpreterBridge {

    private val applicationContext = appContext.applicationContext
    private val mutex = Mutex()
    private val interpreters = ConcurrentHashMap<String, Interpreter>()

    override suspend fun runVectorInference(
        modelAssetPath: String?,
        inputFeatures: FloatArray,
        outputLength: Int,
    ): TfliteInferenceResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (modelAssetPath.isNullOrBlank()) {
                return@withLock skippedResult(
                    inputLength = inputFeatures.size,
                    outputLength = outputLength,
                    modelAssetPath = "",
                    reason = "no_model_asset_path",
                )
            }
            runCatching {
                val interpreter = interpreters.computeIfAbsent(modelAssetPath) { path ->
                    val bytes = applicationContext.assets.open(path).use { it.readBytes() }
                    val bb = ByteBuffer.allocateDirect(bytes.size)
                    bb.order(ByteOrder.nativeOrder())
                    bb.put(bytes)
                    bb.rewind()
                    Interpreter(bb)
                }

                interpreter.allocateTensors()

                val inShape = interpreter.getInputTensor(0).shape()
                val outShape = interpreter.getOutputTensor(0).shape()

                val (inputWritten, output) = runWithTensorShapes(
                    interpreter = interpreter,
                    inputFeatures = inputFeatures,
                    inShape = inShape,
                    outShape = outShape,
                    outputLengthHint = outputLength,
                )

                val agg = output.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
                val fp = output.take(8).joinToString(prefix = "fp:", separator = ",") { "%.4f".format(it) }
                TfliteInferenceResult(
                    output = output,
                    trace = TfliteInferenceTrace(
                        modelAssetPath = modelAssetPath,
                        inputLength = inputWritten,
                        outputLength = output.size,
                        aggregateScore = agg,
                        fingerprint = fp,
                        skipped = false,
                        skipReason = null,
                    ),
                )
            }.getOrElse { e ->
                skippedResult(
                    inputLength = inputFeatures.size,
                    outputLength = outputLength,
                    modelAssetPath = modelAssetPath,
                    reason = e.javaClass.simpleName + ":" + (e.message ?: ""),
                )
            }
        }
    }

    /**
     * Fills TFLite inputs from [inputFeatures] and runs [Interpreter.run].
     * Supports common rank-1 / rank-2 float layouts; other ranks throw with a clear message.
     */
    private fun runWithTensorShapes(
        interpreter: Interpreter,
        inputFeatures: FloatArray,
        inShape: IntArray,
        outShape: IntArray,
        outputLengthHint: Int,
    ): Pair<Int, FloatArray> {
        val inputWritten: Int
        val output: FloatArray
        when (inShape.size) {
            1 -> {
                val inArr = FloatArray(inShape[0])
                val n = min(inputFeatures.size, inArr.size)
                inputFeatures.copyInto(inArr, destinationOffset = 0, startIndex = 0, endIndex = n)
                inputWritten = n
                output = when (outShape.size) {
                    1 -> {
                        val outArr = FloatArray(outShape[0])
                        interpreter.run(inArr, outArr)
                        outArr
                    }
                    2 -> {
                        val outArr = Array(outShape[0]) { FloatArray(outShape[1]) }
                        interpreter.run(inArr, outArr)
                        flatten2dFirstRow(outArr, outShape, outputLengthHint)
                    }
                    else -> throw IllegalArgumentException("unsupported output rank ${outShape.contentToString()}")
                }
            }
            2 -> {
                val batch = inShape[0]
                val dim = inShape[1]
                val inArr = Array(batch) { FloatArray(dim) }
                val total = batch * dim
                val n = min(inputFeatures.size, total)
                for (i in 0 until n) {
                    inArr[i / dim][i % dim] = inputFeatures[i]
                }
                inputWritten = n
                output = when (outShape.size) {
                    1 -> {
                        val outArr = FloatArray(outShape[0])
                        interpreter.run(inArr, outArr)
                        outArr
                    }
                    2 -> {
                        val outArr = Array(outShape[0]) { FloatArray(outShape[1]) }
                        interpreter.run(inArr, outArr)
                        flatten2dFirstRow(outArr, outShape, outputLengthHint)
                    }
                    else -> throw IllegalArgumentException("unsupported output rank ${outShape.contentToString()}")
                }
            }
            else -> throw IllegalArgumentException("unsupported input rank ${inShape.contentToString()}")
        }
        return inputWritten to output
    }

    private fun flatten2dFirstRow(
        outArr: Array<FloatArray>,
        outShape: IntArray,
        outputLengthHint: Int,
    ): FloatArray {
        if (outShape[0] <= 0 || outShape[1] <= 0 || outArr.isEmpty()) {
            return FloatArray(outputLengthHint.coerceAtLeast(1)) { 0f }
        }
        val row = outArr[0]
        val len = if (outputLengthHint > 0) min(row.size, outputLengthHint) else row.size
        return row.copyOf(len)
    }

    private fun skippedResult(
        inputLength: Int,
        outputLength: Int,
        modelAssetPath: String,
        reason: String,
    ): TfliteInferenceResult {
        val out = FloatArray(outputLength.coerceAtLeast(1)) { 0f }
        return TfliteInferenceResult(
            output = out,
            trace = TfliteInferenceTrace(
                modelAssetPath = modelAssetPath,
                inputLength = inputLength,
                outputLength = out.size,
                aggregateScore = 0.0,
                fingerprint = "skipped:$reason",
                skipped = true,
                skipReason = reason,
            ),
        )
    }
}
