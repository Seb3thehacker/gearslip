package app.seb3thehacker.gearslip

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** SDK Manager's stable DHU 2.0 archive. The desktop executable is read, never run on the phone. */
internal object DhuCertificateDownloader {
    internal const val URL = "https://dl.google.com/android/repository/desktop-head-unit-linux-x64_r02.0.zip"
    private const val SHA256 = "80cf2dd57315098f5441139131ef5b41e16ad003c1cdd21851ba15e49c95d76b"

    fun fetch(): CertProvider.Identity {
        val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        val bytes = try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "DHU download failed (HTTP ${connection.responseCode})" }
            connection.inputStream.use { ProjectionIdentityExtractor.readLimited(it, 16 * 1024 * 1024) }
        } finally {
            connection.disconnect()
        }
        return extract(bytes)
    }

    internal fun extract(archive: ByteArray): CertProvider.Identity {
        val digest = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        require(digest == SHA256) { "The downloaded DHU archive failed its checksum check" }
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "desktop-head-unit") {
                    return ProjectionIdentityExtractor.desktopHeadUnit(ProjectionIdentityExtractor.readLimited(zip, 32 * 1024 * 1024))
                }
            }
        }
        error("The DHU archive has no desktop-head-unit executable")
    }
}
