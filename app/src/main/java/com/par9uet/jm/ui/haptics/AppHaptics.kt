package com.par9uet.jm.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.MainThread

/**
 * 应用内统一触觉反馈。ViewModel 也可直接调用（Application 启动时 install）。
 * 系统设置关震动时各方法直接 no-op。
 */
object AppHaptics {
    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private fun vibratorOrNull(): Vibrator? {
        val context = appContext ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun canVibrate(): Boolean {
        val vibrator = vibratorOrNull() ?: return false
        return vibrator.hasVibrator()
    }

    /** 页码快调：每翻一页的轻触感。 */
    @MainThread
    fun tick() {
        if (!canVibrate()) return
        val vibrator = vibratorOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(12L, 48))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(12L)
        }
    }

    /** 收藏成功：短促确认震动。 */
    @MainThread
    fun success() {
        if (!canVibrate()) return
        val vibrator = vibratorOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 18L, 28L, 22L),
                    intArrayOf(0, 90, 0, 160),
                    -1,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(40L)
        }
    }

    /** 删除漫画：由轻到重的多层次震动。 */
    @MainThread
    fun deleteMulti() {
        if (!canVibrate()) return
        val vibrator = vibratorOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 16L, 28L, 24L, 40L, 36L),
                    intArrayOf(0, 70, 0, 140, 0, 220),
                    -1,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0L, 16L, 28L, 24L, 40L, 36L), -1)
        }
    }
}
