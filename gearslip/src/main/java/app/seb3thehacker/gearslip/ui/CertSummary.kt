package app.seb3thehacker.gearslip.ui

import android.content.Context
import app.seb3thehacker.gearslip.CertProvider
import java.text.DateFormat
import java.util.Date

/** What the UI says about the certificate a connection would present. */
data class CertSummary(
    val headline: String,
    val subject: String?,
    val issuer: String?,
    val validUntil: String?,
    val expired: Boolean,
) {
    companion object {
        /**
         * The last [read], so Settings draws the certificate row at once instead of a second
         * later, which shifted everything under it. Null until the first read.
         */
        @Volatile var cached: CertSummary? = null
            private set

        /** Blocking (loads the bundled identity): call from a background dispatcher. */
        fun read(context: Context): CertSummary = readNow(context).also { cached = it }

        private fun readNow(context: Context): CertSummary {
            val cert = CertProvider.load(context).certificate
            val name = friendlyName(cert.subjectX500Principal.name)
            return CertSummary(
                "Bundled: $name",
                cert.subjectX500Principal.name,
                cert.issuerX500Principal.name,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(cert.notAfter),
                cert.notAfter.before(Date()),
            )
        }

        private fun friendlyName(distinguishedName: String): String {
            for (key in listOf("CN", "O")) {
                Regex("(?:^|,)$key=([^,]+)").find(distinguishedName)?.let { return it.groupValues[1] }
            }
            return distinguishedName
        }
    }
}
