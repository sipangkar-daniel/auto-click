package com.sipangkar.autoclick

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class AutoClickApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (OpenCVLoader.initLocal()) {
            Log.d("AutoClickApp", "OpenCV loaded successfully")
        } else {
            Log.e("AutoClickApp", "OpenCV failed to load")
        }
    }
}
