package com.skoltech.densepose

import android.os.SystemClock

/** Frames per second over the frames of the last `windowMs`. One thread calls tick(). */
class FpsMeter(private val windowMs: Long = 1000) {
    private val times = ArrayDeque<Long>()

    /** Call once per frame; returns the rolling fps (0 until two frames are in the window). */
    fun tick(now: Long = SystemClock.elapsedRealtime()): Float {
        times.addLast(now)
        while (times.size > 2 && now - times.first() > windowMs) times.removeFirst()
        val span = times.last() - times.first()
        return if (times.size < 2 || span <= 0) 0f else (times.size - 1) * 1000f / span
    }
}
