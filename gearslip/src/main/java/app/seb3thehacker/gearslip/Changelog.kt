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
                "Hear a message read aloud and reply by voice. Say \"cancel\" or \"change it\" before it sends, or tap a quick reply such as your arrival time.",
                "Voice works on any phone, GrapheneOS included. Gearslip now turns speech into text itself, with no Google services.",
                "New messages show as a small card at the top right. A bar shows how long until it closes or sends.",
                "Only messages, missed calls, alarms and reminders pop up. Everything else waits on the dashboard.",
                "Steering wheel buttons, control knobs and D-pads now work. Tell us if yours don't.",
                "The map stays loaded when you switch screens, so it no longer flashes black.",
                "Map apps draw more reliably and keep their connection when the phone is locked.",
                "The Home button shows your map app, so it no longer appears twice.",
                "The next turn shows in the nav bar when you leave the map.",
                "Choose miles or kilometres under Settings > Units.",
                "Try the voice assistant and the vehicle data screen under Settings > Experimental features.",
                "Logs now say why a car ended the connection.",
            ),
        ),
    )

    /** The release that matches this build, if it has notes. */
    val current: Release? get() = releases.firstOrNull { it.versionCode == BuildConfig.VERSION_CODE }
}
