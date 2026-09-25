package dev.bilby

import android.os.SystemClock

actual fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
