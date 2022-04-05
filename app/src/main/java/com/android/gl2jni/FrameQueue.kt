package com.android.gl2jni

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

class FrameQueue(frameQueueSize: Int) {

    /*data class Frame (
        val data: ByteArray,
        val offset: Int,
        val length: Int,
        val timestamp: Long
    )*/

   /* data class Frame (
        val data: Bitmap

    )
*/
    private val queue: BlockingQueue<Bitmap> = ArrayBlockingQueue(frameQueueSize)

    @Throws(InterruptedException::class)
    fun push(bitmap: Bitmap): Boolean {
        if (queue.offer(bitmap, 5, TimeUnit.MILLISECONDS)) {
            return true
        }
        Log.w(TAG, "Cannot add frame, queue is full")
        return false
    }

    @Throws(InterruptedException::class)
    fun pop(): Bitmap? {
        try {
            val bitmap: Bitmap? = queue.poll(1000, TimeUnit.MILLISECONDS)
            if (bitmap == null) {
                Log.w(TAG, "Cannot get frame, queue is empty")
            }
            return bitmap
        } catch (e: InterruptedException) {
            Log.w(TAG, "Cannot add frame, queue is full", e)
            Thread.currentThread().interrupt()
        }
        return null
    }

    fun clear() {
        queue.clear()
    }

    companion object {
        private val TAG: String = FrameQueue::class.java.simpleName
    }

}
