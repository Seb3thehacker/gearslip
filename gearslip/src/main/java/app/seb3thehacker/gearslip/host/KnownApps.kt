package app.seb3thehacker.gearslip.host

/**
 * Apps that have been run through Gearslip from start to finish and behave. The launcher marks
 * them so a driver can pick something that works without finding out at the wheel.
 *
 * An app belongs here only after a real session: the app starts, draws, takes touches and
 * answers its buttons. Apps that connect but show no library, or refuse Gearslip as a host, stay
 * off the list.
 */
object KnownApps {

    private val working = setOf(
        "app.organicmaps",       // Organic Maps: map, search, routing, settings
        "net.osmand.plus",       // OsmAnd: map, search, routing, settings
        "in.krosbits.musicolet", // Musicolet: browse, play, seek, lyrics
        "com.kododake.aabrowser", // Aardvark: browser template, starts, draws, takes touches
        "com.vivi.vivimusic", // Vivi Music: browse, play, seek, works well
    )

    fun works(packageName: String): Boolean = packageName in working

    /**
     * Registers a car template service but never gives Gearslip a usable screen - left off the
     * launcher entirely rather than shown as a tile that goes nowhere.
     */
    private val hidden = setOf(
        "com.google.android.dialer",               // Google Phone
        "com.google.android.googlequicksearchbox",  // Google Assistant driving surfaces
    )

    fun isHidden(packageName: String): Boolean = packageName in hidden

    /** Shown on the launcher with a red X: known broken, but expected enough that hiding it outright would look like a bug. */
    private val broken = setOf(
        "com.google.android.apps.messaging", // Google Messages
        "com.waze",                          // Waze
    )

    fun isBroken(packageName: String): Boolean = packageName in broken
}
