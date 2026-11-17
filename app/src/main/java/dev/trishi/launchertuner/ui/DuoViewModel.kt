package dev.trishi.launchertuner.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.trishi.launchertuner.depth.DepthEngine
import dev.trishi.launchertuner.gesture.GestureService

class DuoViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = GestureService.prefs(app)

    var enabled by mutableStateOf(prefs.getBoolean(GestureService.KEY_ENABLED, false))
        private set
    var angle by mutableStateOf(prefs.getFloat(GestureService.KEY_ANGLE, 28f))
        private set
    var strength by mutableStateOf(prefs.getFloat(GestureService.KEY_STRENGTH, 1f))
        private set
    var accessibility by mutableStateOf(GestureService.isRunning())
        private set

    fun refresh() {
        accessibility = GestureService.isRunning()
        enabled = prefs.getBoolean(GestureService.KEY_ENABLED, false)
    }

    fun updateEnabled(value: Boolean) {
        prefs.edit().putBoolean(GestureService.KEY_ENABLED, value).apply()
        enabled = value
        GestureService.refresh()
    }

    fun updateAngle(value: Float) {
        angle = value
        DepthEngine.maxAngle = value
        prefs.edit().putFloat(GestureService.KEY_ANGLE, value).apply()
    }

    fun updateStrength(value: Float) {
        strength = value
        DepthEngine.strength = value
        prefs.edit().putFloat(GestureService.KEY_STRENGTH, value).apply()
    }

}
