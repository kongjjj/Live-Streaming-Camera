package com.kongjjj.livestreamingcamera

import android.content.pm.ActivityInfo

/**
 * Application configuration.
 */
object ApplicationConstants {
    /**
     * Default application orientation.
     * Also set in `AndroidManifest.xml` `android:screenOrientation` attribute.
     */
    const val SUPPORTED_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}