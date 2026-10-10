package app.seb3thehacker.gearslip

/** Debug overrides deliberately disable fallback so a test uses exactly one identity. */
enum class CertificateMode(val label: String, val source: CertProvider.Source?) {
    AUTOMATIC("Automatic", null),
    ANDROID_AUTO("Android Auto", CertProvider.Source.ANDROID_AUTO),
    HEAD_UNIT("Head unit (DHU)", CertProvider.Source.HEAD_UNIT),
}

/**
 * A rejected TLS session needs a new connection initiated by the head unit. Carry one retry
 * across USB reattachment, without making the fallback the default for subsequent drives.
 * The pending retry stays in this process only; an app restart always tries Android Auto first.
 */
class CertificateRetry(private val clock: () -> Long = System::nanoTime) {
    class Attempt internal constructor(
        val accessory: String,
        val source: CertProvider.Source,
        val forced: Boolean,
        internal val generation: Long,
    )

    private data class Pending(val accessory: String, val createdAt: Long)
    private var pending: Pending? = null
    private var generation = 0L

    @Synchronized
    fun begin(accessory: String, mode: CertificateMode): Attempt {
        val retry = pending
        pending = null
        val canRetry = retry != null && retry.accessory == accessory &&
            clock() - retry.createdAt in 0 until RETRY_WINDOW_NANOS
        val source = mode.source ?: if (canRetry) CertProvider.Source.HEAD_UNIT else CertProvider.Source.ANDROID_AUTO
        return Attempt(accessory, source, mode.source != null, ++generation)
    }

    /** Ignore callbacks from a closed session, and never retry a forced or fallback identity. */
    @Synchronized
    fun failed(attempt: Attempt): Boolean {
        if (attempt.generation != generation || attempt.forced || attempt.source != CertProvider.Source.ANDROID_AUTO) return false
        if (pending != null) return false
        pending = Pending(attempt.accessory, clock())
        return true
    }

    /** Closing the descriptor must not let its final read error schedule a new retry. */
    @Synchronized
    fun closed(attempt: Attempt) {
        if (attempt.generation == generation) generation++
    }

    @Synchronized
    fun clear() {
        pending = null
        generation++
    }

    companion object {
        val shared = CertificateRetry()
        // A reconnect is part of this attempt; a drive much later should try the phone identity again.
        private const val RETRY_WINDOW_NANOS = 5L * 60 * 1_000_000_000
    }
}
