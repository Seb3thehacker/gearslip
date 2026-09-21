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
    )

    fun works(packageName: String): Boolean = packageName in working
}
