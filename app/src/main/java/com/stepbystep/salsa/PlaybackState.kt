package com.stepbystep.salsa

/** Independent learning controls; choosing a video clears its A/B marks only. */
class PlaybackState {
    var speed = 1f
    var crop = false
    var loop = false
        private set

    var a: Int? = null
        private set

    var b: Int? = null
        private set

    fun toggleLoop() {
        loop = !loop
        if (loop) clearAB()
    }

    fun markA(position: Int) {
        a = position.coerceAtLeast(0)
        b = null
        loop = false
    }

    fun markB(position: Int) {
        if (a != null && position > a!! + 100) {
            b = position
            loop = false
        }
    }

    fun clearAB() {
        a = null
        b = null
    }

    fun newVideo() {
        clearAB()
    }

    fun target(position: Int, ended: Boolean = false): Int? =
        if (a != null && b != null && (position >= b!! || ended)) a
        else if (loop && ended) 0 else null
}
