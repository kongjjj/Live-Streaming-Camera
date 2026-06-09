package com.kongjjj.livestreamingcamera.utils

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Size
import android.view.Surface
import androidx.annotation.IntRange
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.ListenableFuture
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.OpenGlRenderer
import io.github.thibaultbee.streampack.core.elements.processing.video.ShaderProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.ISurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.elements.utils.time.TimeUtils
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.elements.utils.time.VideoTimebaseConverter
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import io.github.thibaultbee.streampack.core.pipelines.utils.HandlerThreadExecutor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自定義的 SurfaceProcessor，允許注入 Shader 特效
 */
class EffectSurfaceProcessor(
    private val dynamicRangeProfile: DynamicRangeProfile,
    private val glThread: HandlerThreadExecutor,
    private val shaderProvider: ShaderProvider,
) : ISurfaceProcessorInternal, SurfaceTexture.OnFrameAvailableListener {
    private val renderer = OpenGlRenderer()
    private val glHandler = glThread.handler
    private val isReleaseRequested = AtomicBoolean(false)
    private var isReleased = false

    private val textureMatrix = FloatArray(16)
    private val surfaceOutputMatrix = FloatArray(16)

    private val surfaceOutputs: MutableList<ISurfaceOutput> = mutableListOf()
    private val surfaceInputs: MutableList<SurfaceInput> = mutableListOf()
    private val surfaceInputsToTimeConverterMap: MutableMap<SurfaceTexture, VideoTimebaseConverter> =
        hashMapOf()

    private val pendingSnapshots = mutableListOf<PendingSnapshot>()

    // 特效狀態
    private var isGrayscale = false
    private var isBeauty = false
    private var isBlur = false
    private var isMosaic = false
    private var isSepia = false
    private var isSplitThree = false

    private val surfaceToEffectsMap: MutableMap<Surface, Boolean> = HashMap()
    private var inputSurfaceSize = Size(0, 0)

    // PiP 相關
    var isPipEnabled = false
    private var pipPosition = floatArrayOf(0.7f, 0.7f) // 左下角預設
    private var pipSize = floatArrayOf(0.25f, 0.25f)    // 1/4 預設
    private var pipTextureId = -1
    private var pipSurfaceTexture: SurfaceTexture? = null
    var pipSurface: Surface? = null
        private set

    fun updateEffects(
        grayscale: Boolean,
        beauty: Boolean,
        blur: Boolean,
        mosaic: Boolean,
        sepia: Boolean,
        splitThree: Boolean,
        pipEnabled: Boolean = false,
        pipPos: FloatArray? = null,
        pipSz: FloatArray? = null
    ) {
        isGrayscale = grayscale
        isBeauty = beauty
        isBlur = blur
        isMosaic = mosaic
        isSepia = sepia
        isSplitThree = splitThree
        isPipEnabled = pipEnabled
        pipPos?.let { pipPosition = it }
        pipSz?.let { pipSize = it }
    }

    /**
     * 設定特定 Surface 是否套用特效
     */
    fun setSurfaceEffectEnabled(surface: Surface, enabled: Boolean) {
        executeSafely {
            surfaceToEffectsMap[surface] = enabled
        }
    }

    init {
        val future = submitSafely {
            // 為所有可能的輸入格式都套用 Shader，確保預覽也能更新
            renderer.init(dynamicRangeProfile, mapOf(
                GLUtils.InputFormat.DEFAULT to shaderProvider,
                GLUtils.InputFormat.YUV to shaderProvider
            ))
        }
        try {
            future.get()
        } catch (e: Exception) {
            release()
            Logger.e(TAG, "Error while initializing renderer", e)
            throw e
        }
    }

    override fun createInputSurface(surfaceSize: Size, timebase: Timebase): Surface {
        if (isReleaseRequested.get()) {
            throw IllegalStateException("SurfaceProcessor is released")
        }

        inputSurfaceSize = surfaceSize
        val future = submitSafely {
            if (pipTextureId == -1) {
                pipTextureId = createTexture()
                pipSurfaceTexture = SurfaceTexture(pipTextureId).apply {
                    setDefaultBufferSize(480, 360) // 設定 PiP 解析度為 480x360 以節省 CPU
                    setOnFrameAvailableListener({ it.updateTexImage() }, glHandler)
                }
                pipSurface = Surface(pipSurfaceTexture)
            }

            val surfaceTexture = SurfaceTexture(renderer.textureName)
            surfaceTexture.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
            surfaceTexture.setOnFrameAvailableListener(this, glHandler)
            if (dynamicRangeProfile.isHdr) {
                renderer.setInputFormat(GLUtils.InputFormat.YUV)
            }

            surfaceInputsToTimeConverterMap[surfaceTexture] = VideoTimebaseConverter(
                timebase,
                TimeUtils.systemTimeProvider
            )
            SurfaceInput(Surface(surfaceTexture), surfaceTexture)
        }

        val surfaceInput = future.get()
        surfaceInputs.add(surfaceInput)
        return surfaceInput.surface
    }

    override fun removeInputSurface(surface: Surface) {
        executeSafely {
            val surfaceInput = surfaceInputs.find { it.surface == surface }
            surfaceInput?.let {
                val surfaceTexture = it.surfaceTexture
                surfaceTexture.setOnFrameAvailableListener(null, glHandler)
                surfaceTexture.release()
                surface.release()

                surfaceInputsToTimeConverterMap.remove(surfaceTexture)
                surfaceInputs.remove(it)

                checkReadyToRelease()
            }
        }
    }

    override fun setTimebase(surface: Surface, timebase: Timebase) {
        executeSafely {
            val surfaceInput = surfaceInputs.find { it.surface == surface }
            if (surfaceInput != null) {
                surfaceInputsToTimeConverterMap[surfaceInput.surfaceTexture] =
                    VideoTimebaseConverter(
                        timebase,
                        TimeUtils.systemTimeProvider
                    )
            }
        }
    }

    override fun addOutputSurface(surfaceOutput: ISurfaceOutput) {
        if (isReleaseRequested.get()) return

        executeSafely {
            if (surfaceOutputs.none { it.targetSurface == surfaceOutput.targetSurface }) {
                renderer.registerOutputSurface(surfaceOutput.targetSurface)
                surfaceOutputs.add(surfaceOutput)
            }
        }
    }

    override fun removeOutputSurface(surfaceOutput: ISurfaceOutput) {
        if (isReleaseRequested.get()) return

        executeSafely {
            if (surfaceOutputs.contains(surfaceOutput)) {
                renderer.unregisterOutputSurface(surfaceOutput.targetSurface)
                surfaceOutputs.remove(surfaceOutput)
            }
        }
    }

    override fun removeOutputSurface(surface: Surface) {
        if (isReleaseRequested.get()) return

        executeSafely {
            val surfaceOutput = surfaceOutputs.firstOrNull { it.targetSurface == surface }
            if (surfaceOutput != null) {
                renderer.unregisterOutputSurface(surfaceOutput.targetSurface)
                surfaceOutputs.remove(surfaceOutput)
                surfaceToEffectsMap.remove(surface)
            }
        }
    }

    override fun removeAllOutputSurfaces() {
        if (isReleaseRequested.get()) return

        executeSafely {
            surfaceOutputs.forEach { renderer.unregisterOutputSurface(it.targetSurface) }
            surfaceOutputs.clear()
        }
    }

    override fun release() {
        if (isReleaseRequested.getAndSet(true)) return
        executeSafely {
            pipSurface?.release()
            pipSurfaceTexture?.release()
            if (!isReleased) {
                isReleased = true
                checkReadyToRelease()
            }
        }
    }

    private fun checkReadyToRelease() {
        if (isReleased && surfaceInputs.isEmpty()) {
            surfaceOutputs.forEach { renderer.unregisterOutputSurface(it.targetSurface) }
            surfaceOutputs.clear()
            renderer.release()
            glThread.quit()
        }
    }

    override fun snapshot(@IntRange(from = 0, to = 359) rotationDegrees: Int): ListenableFuture<Bitmap> {
        if (isReleaseRequested.get()) {
            throw IllegalStateException("SurfaceProcessor is released")
        }
        return CallbackToFutureAdapter.getFuture { completer ->
            executeSafely {
                pendingSnapshots.add(PendingSnapshot(rotationDegrees, completer))
            }
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (isReleaseRequested.get()) return

        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureMatrix)

        val timeConverter = surfaceInputsToTimeConverterMap[surfaceTexture] ?: return

        // 1. 處理截圖請求 (截圖不套用特效)
        if (pendingSnapshots.isNotEmpty()) {
            val snapshots = synchronized(pendingSnapshots) {
                val list = pendingSnapshots.toList()
                pendingSnapshots.clear()
                list
            }
            snapshots.forEach { snapshot ->
                try {
                    // 截圖前將特效關閉
                    setEffectUniforms(false)
                    
                    // 使用輸入的原始大小 (通常是感測器方向的大小)
                    val size = if (inputSurfaceSize.width > 0 && inputSurfaceSize.height > 0) {
                        inputSurfaceSize
                    } else {
                        Size(1280, 720)
                    }
                    
                    val bitmap = renderer.snapshot(size, textureMatrix)
                    
                    // 根據請求旋轉圖片 (處理感測器旋轉)
                    val finalBitmap = if (snapshot.rotationDegrees != 0) {
                        val matrix = Matrix()
                        matrix.postRotate(snapshot.rotationDegrees.toFloat())
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else {
                        bitmap
                    }
                    
                    snapshot.completer.set(finalBitmap)
                } catch (e: Exception) {
                    snapshot.completer.setException(e)
                }
            }
        }

        // 2. 渲染到各個輸出 Surface
        surfaceOutputs.filterIsInstance<SurfaceOutput>().forEach { output ->
            try {
                output.updateTransformMatrix(surfaceOutputMatrix, textureMatrix)
                if (output.isStreaming()) {
                    // 根據 Surface 設定決定是否套用特效
                    val shouldApply = surfaceToEffectsMap[output.targetSurface] ?: true
                    setEffectUniforms(shouldApply)

                    renderer.render(
                        timeConverter.convertToUptimeNs(surfaceTexture.timestamp),
                        surfaceOutputMatrix,
                        output.targetSurface
                    )
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "Error while rendering frame", t)
            }
        }
    }

    private fun createTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textureId
    }

    private fun setEffectUniforms(enabled: Boolean) {
        val programArr = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, programArr, 0)
        val currentProgram = programArr[0]
        if (currentProgram > 0) {
            GLES20.glUniform1i(GLES20.glGetUniformLocation(currentProgram, "uGrayscale"), if (enabled && isGrayscale) 1 else 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(currentProgram, "uBeauty"), if (enabled && isBeauty) 1 else 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(currentProgram, "uBlur"), if (enabled && isBlur) 1 else 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(currentProgram, "uMosaic"), if (enabled && isMosaic) 1 else 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(currentProgram, "uSepia"), if (enabled && isSepia) 1 else 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(currentProgram, "uSplitThree"), if (enabled && isSplitThree) 1 else 0)

            GLES20.glUniform1i(GLES20.glGetUniformLocation(currentProgram, "uPipEnabled"), if (enabled && isPipEnabled) 1 else 0)
            if (enabled && isPipEnabled) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, pipTextureId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(currentProgram, "uPipSampler"), 1)
                GLES20.glUniform2fv(GLES20.glGetUniformLocation(currentProgram, "uPipPosition"), 1, pipPosition, 0)
                GLES20.glUniform2fv(GLES20.glGetUniformLocation(currentProgram, "uPipSize"), 1, pipSize, 0)
            }
        }
    }

    private fun executeSafely(block: () -> Unit) {
        executeSafely(block, {}, {})
    }

    private fun <T> executeSafely(
        block: () -> T,
        onSuccess: (T) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        try {
            glHandler.post {
                if (isReleased) {
                    onError(IllegalStateException("SurfaceProcessor is released"))
                } else {
                    try {
                        onSuccess(block())
                    } catch (t: Throwable) {
                        onError(t)
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "Error while executing block", t)
            onError(t)
        }
    }

    private fun <T : Any> submitSafely(block: () -> T): ListenableFuture<T> {
        return CallbackToFutureAdapter.getFuture {
            executeSafely(block, { result -> it.set(result) }, { t -> it.setException(t) })
        }
    }

    companion object {
        private const val TAG = "EffectSurfaceProcessor"
    }

    private data class SurfaceInput(val surface: Surface, val surfaceTexture: SurfaceTexture)
    private data class PendingSnapshot(
        val rotationDegrees: Int,
        val completer: CallbackToFutureAdapter.Completer<Bitmap>
    )
}

class EffectSurfaceProcessorFactory(private val shaderProvider: ShaderProvider) :
    ISurfaceProcessorInternal.Factory {
    override fun create(
        dynamicRangeProfile: DynamicRangeProfile,
        dispatcherProvider: IVideoDispatcherProvider
    ): ISurfaceProcessorInternal {
        return EffectSurfaceProcessor(
            dynamicRangeProfile,
            dispatcherProvider.createVideoHandlerExecutor("gl"),
            shaderProvider
        )
    }
}
