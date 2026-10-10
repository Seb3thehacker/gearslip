package app.seb3thehacker.gearslip.car

/** A small immutable snapshot; Android's mutable Location must not escape a Binder callback. */
internal data class AppLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val elapsedRealtimeNanos: Long,
) {
    fun isFresh(nowNanos: Long): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            accuracyMeters.isFinite() && accuracyMeters >= 0 &&
            elapsedRealtimeNanos > 0 && elapsedRealtimeNanos <= nowNanos &&
            nowNanos - elapsedRealtimeNanos <= MAX_AGE_NANOS

    private companion object {
        const val MAX_AGE_NANOS = 2 * 60 * 1_000_000_000L
    }
}

/** Session-only fixes from the navigation app, shared with weather's background worker. */
internal object AppLocations {
    private var owner: Any? = null
    private var location: AppLocation? = null

    @Synchronized
    fun begin(owner: Any) {
        this.owner = owner
        location = null
    }

    @Synchronized
    fun update(owner: Any, fix: AppLocation, nowNanos: Long) {
        if (this.owner !== owner || !fix.isFresh(nowNanos)) return
        // Binder delivery order must not let an older fix move the cached position backwards.
        if (fix.elapsedRealtimeNanos <= (location?.elapsedRealtimeNanos ?: 0)) return
        location = fix
    }

    @Synchronized
    fun latest(nowNanos: Long): AppLocation? = location?.takeIf { it.isFresh(nowNanos) }

    @Synchronized
    fun clear(owner: Any) {
        // Stopping a replaced connection must not erase the new navigation app's fix.
        if (this.owner !== owner) return
        this.owner = null
        location = null
    }
}
