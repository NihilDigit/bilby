package dev.bilby

actual fun elapsedRealtimeMillis(): Long = System.nanoTime() / 1_000_000
