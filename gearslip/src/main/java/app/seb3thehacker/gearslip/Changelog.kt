package app.seb3thehacker.gearslip

/**
 * What changed in each release, newest first, shown on the car screen after an update (and on
 * the phone). Only the changes a driver would notice. Plain words, short lines: this is read in
 * a parked car.
 */
object Changelog {

    class Release(val versionCode: Int, val versionName: String, val changes: List<String>)

    val releases = listOf(
        Release(
            6, "0.1.0-alpha",
            listOf(
                "Messaging apps such as Signal and Molly open to your recent chats.",
                "Gearslip reads a message aloud, and you reply by voice. Say \"cancel\" or \"change it\" before it sends, or tap a quick reply such as your arrival time.",
                "Voice works on any phone, GrapheneOS included. Gearslip turns speech into text itself, so it needs no Google account.",
                "New messages show as a small card at the top right, even while you drive. A bar shows how long until the card closes or the reply sends.",
                "Only messages, missed calls, alarms, and reminders pop up. Everything else waits on the dashboard.",
                "The map stays loaded when you switch screens, so it no longer flashes black.",
                "Map apps draw more reliably and keep their connection when the phone is locked.",
                "The Home button shows your map app, so it no longer appears twice.",
                "The next turn shows in the nav bar when you leave the map.",
                "Steering wheel buttons, control knobs, and D-pads now work. If yours don't, open an issue on GitHub.",
                "To see the whole song title and artist in the nav bar, choose Settings > Player in the nav bar > Song title.",
                "Choose miles or kilometres under Settings > Units.",
                "Try the voice assistant and the vehicle data screen under Settings > Experimental features.",
                "Logs now say why a car ended the connection.",
                "Spotify, Waze, and Google Messages updated to a version of Google's car library that only accepts Android Auto itself. Their own car screens won't open in Gearslip. Spotify still plays through the Gearslip player, which has the green check.",
                "Calls go through the car's Bluetooth, as they do in Android Auto. Pair your phone with the car to hear calls on its speakers.",
            ),
        ),
    )

    /** The release that matches this build, if it has notes. */
    val current: Release? get() = releases.firstOrNull { it.versionCode == BuildConfig.VERSION_CODE }
}
