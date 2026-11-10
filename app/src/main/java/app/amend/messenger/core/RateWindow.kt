package app.amend.messenger.core

/**
 * A sliding-window limit: at most [max] things per [windowMs] milliseconds. Used wherever
 * something outside the phone (a nearby device, a watch, a stranger) could otherwise make
 * amend do unlimited work or raise unlimited alerts.
 */
class RateWindow(private val max: Int, private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    /** Counts one more and returns true, or returns false if the window is already full. */
    @Synchronized
    fun allow(now: Long = System.currentTimeMillis()): Boolean {
        while (times.isNotEmpty() && times.first() <= now - windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(now)
        return true
    }
}
