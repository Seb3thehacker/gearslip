package app.seb3thehacker.gearslip.ui

import android.content.Context
import app.seb3thehacker.gearslip.CertProvider
import java.text.DateFormat
import java.util.Date

/** What the UI says about the certificate a connection would present. */
data class CertSummary(
    val kind: CertProvider.Kind,
    val headline: String,
    val subject: String?,
    val issuer: String?,
    val validUntil: String?,
) {
    companion object {
        /** Blocking (parses a key store): call from a background dispatcher. */
        fun read(context: Context): CertSummary {
            val identity = CertProvider.loadSupplied(context)
                ?: return CertSummary(
                    CertProvider.Kind.SELF_SIGNED,
                    "None loaded (self-signed; cars reject it)",
                    null, null, null,
                )
            val cert = identity.certificate
            val name = friendlyName(cert.subjectX500Principal.name)
            val prefix = when (identity.kind) {
                    CertProvider.Kind.IMPORTED -> "Imported"
                    CertProvider.Kind.DOWNLOADED -> "Downloaded"
                    CertProvider.Kind.ADB_STAGED -> "Staged over adb"
                    CertProvider.Kind.SELF_SIGNED -> "Self-signed"
                }
            return CertSummary(
                identity.kind,
                "$prefix: $name",
                cert.subjectX500Principal.name,
                cert.issuerX500Principal.name,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(cert.notAfter.time)),
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
