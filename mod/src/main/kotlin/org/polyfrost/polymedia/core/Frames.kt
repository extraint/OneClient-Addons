package org.polyfrost.polymedia.core

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** A reference-counted RGBA frame so one capture can feed several encoders without copying. */
class Frame internal constructor(
    val width: Int,
    val height: Int,
    val data: ByteArray,
    private val pool: FramePool,
) {
    private val refs = AtomicInteger(1)

    fun retain(): Frame { refs.incrementAndGet(); return this }

    fun release() {
        if (refs.decrementAndGet() == 0) pool.recycle(data)
    }
}

class FramePool(private val maxFree: Int = 6) {
    private val free = ConcurrentLinkedQueue<ByteArray>()

    fun acquire(width: Int, height: Int): Frame {
        val size = width * height * 4
        var buf = free.poll()
        while (buf != null && buf.size != size) buf = free.poll() // resolution changed: drop stale buffers
        return Frame(width, height, buf ?: ByteArray(size), this)
    }

    internal fun recycle(data: ByteArray) {
        if (free.size < maxFree) free.offer(data)
    }
}

object FrameScaler {
    /** Nearest-neighbour RGBA resize; used only when the game window changes size mid-recording. */
    fun nearest(src: ByteArray, sw: Int, sh: Int, dst: ByteArray, dw: Int, dh: Int) {
        for (y in 0 until dh) {
            val sy = y * sh / dh
            for (x in 0 until dw) {
                val sx = x * sw / dw
                System.arraycopy(src, (sy * sw + sx) * 4, dst, (y * dw + x) * 4, 4)
            }
        }
    }
}
