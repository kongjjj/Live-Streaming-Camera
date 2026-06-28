package com.kongjjj.livestreamingcamera.data.rotation

import android.content.Context
import io.github.thibaultbee.streampack.core.streamers.orientation.DisplayRotationProvider
import io.github.thibaultbee.streampack.core.streamers.orientation.asFlowProvider
import kotlinx.coroutines.flow.Flow

/**
 * A repository for orientation data.
 */
class RotationRepository(
    context: Context,
) {
    private val rotationProvider = DisplayRotationProvider(context).asFlowProvider()
    val rotationFlow: Flow<Int> = rotationProvider.rotationFlow

    companion object {
        @Volatile
        private var INSTANCE: RotationRepository? = null

        fun getInstance(context: Context): RotationRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let {
                    return it
                }

                RotationRepository(context).apply {
                    INSTANCE = this
                }
            }
        }
    }
}