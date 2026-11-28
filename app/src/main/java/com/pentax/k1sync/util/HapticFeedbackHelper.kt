package com.pentax.k1sync.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Differentiated tactile vibration feedback for Pentax K-1 remote shooting & astro blind control:
 * - Focus locked: crisp short tick (18ms)
 * - Focus failed: double warning buzz
 * - Shutter released: firm mechanical shutter thump (32ms)
 * - Sequence completed (AEB / Bulb / Interval): celebratory double pulse
 */
class HapticFeedbackHelper(context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }

    private fun vibrateEffect(effect: VibrationEffect) {
        try {
            val v = vibrator ?: return
            if (v.hasVibrator()) {
                v.vibrate(effect)
            }
        } catch (_: Exception) {
        }
    }

    fun focusLocked() {
        vibrateEffect(VibrationEffect.createOneShot(18L, 175))
    }

    fun focusFailed() {
        vibrateEffect(VibrationEffect.createWaveform(longArrayOf(0L, 28L, 55L, 28L), intArrayOf(0, 170, 0, 170), -1))
    }

    fun shutterReleased() {
        vibrateEffect(VibrationEffect.createOneShot(32L, 235))
    }

    fun sequenceCompleted() {
        vibrateEffect(VibrationEffect.createWaveform(longArrayOf(0L, 36L, 75L, 60L), intArrayOf(0, 190, 0, 230), -1))
    }
}
