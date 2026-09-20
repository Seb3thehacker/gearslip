package app.seb3thehacker.gearslip.host

/**
 * Google's own Android Auto, in whatever guise it appears. Gearslip replaces it, so its entries
 * (there are many: one per screen it registers) are noise in every list of apps to open.
 */
object AndroidAuto {

    private val PACKAGE_MARKERS = listOf("gearhead", "projection.gearhead", "android.auto", "androidauto")

    fun matches(packageName: String, label: String): Boolean {
        val pkg = packageName.lowercase()
        if (PACKAGE_MARKERS.any { it in pkg }) return true
        val name = label.lowercase().replace(Regex("[^a-z ]"), " ")
        return "android auto" in name || "androidauto" in name.replace(" ", "")
    }
}
