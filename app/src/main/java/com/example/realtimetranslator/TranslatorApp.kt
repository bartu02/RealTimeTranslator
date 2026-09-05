package com.example.realtimetranslator

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * Loads the OpenCV native library once, at startup.
 *
 * Nothing did this before, so any call into OpenCV would have thrown
 * `UnsatisfiedLinkError`. Motion tracking depends on it, and the app degrades to
 * recognition-only updates when the library is missing rather than crashing.
 */
class TranslatorApp : Application() {

    override fun onCreate() {
        super.onCreate()

        openCvAvailable = try {
            OpenCVLoader.initLocal()
        } catch (throwable: Throwable) {
            Log.e(TAG, "OpenCV native library failed to load", throwable)
            false
        }

        if (!openCvAvailable) {
            Log.w(TAG, "OpenCV unavailable - overlay tracking is disabled")
        }
    }

    companion object {
        private const val TAG = "TranslatorApp"

        @Volatile
        var openCvAvailable: Boolean = false
            private set
    }
}
