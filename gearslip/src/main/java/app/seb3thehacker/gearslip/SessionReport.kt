package app.seb3thehacker.gearslip

import android.content.Context
import android.os.Build

/**
 * The facts a bug report needs, gathered as a session runs and printed as one readable block
 * when it ends. The log holds the full trace; this holds the answer to "what went wrong,
 * on what car, on what phone" so nobody has to read the trace to find out.
 *
 * Nothing here identifies a person: no serial numbers, accounts, locations or accessory
 * serials. The head unit and vehicle strings are what the head unit itself announces.
 */
object SessionReport {

    /** Where a session failed, coarse enough to count and group reports by. */
    enum class Category(val label: String) {
        NONE("none"),
        USB("USB transport"),
        NO_HANDSHAKE("head unit never started talking"),
        TLS("TLS handshake failed"),
        CERTIFICATE("certificate rejected by the head unit"),
        AUTH("authentication failed"),
        SERVICE_DISCOVERY("service discovery failed"),
        VIDEO("video stream failed"),
        CRASH("app crash"),
        UNKNOWN("unclassified"),
    }

    private var appVersion = "?"
    private var appCode = 0L

    @Volatile private var startedAt = 0L
    @Volatile var category = Category.NONE; private set
    @Volatile private var failure = ""
    @Volatile private var protocolVersion = ""
    @Volatile private var certSource = ""
    @Volatile private var certIssuer = ""
    @Volatile private var tlsProtocol = ""
    @Volatile private var tlsCipher = ""
    @Volatile private var authStatus: Int? = null
    @Volatile private var headUnit = ""
    @Volatile private var profile = ""
    @Volatile private var video = ""
    @Volatile private var audio = ""
    @Volatile private var lastStream = ""
    @Volatile private var lastState = ""
    private var printed = false

    fun init(context: Context) {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        appVersion = info?.versionName ?: "?"
        appCode = info?.longVersionCode ?: 0
    }

    fun begin() {
        startedAt = System.currentTimeMillis()
        category = Category.NONE
        failure = ""; protocolVersion = ""; certSource = ""; certIssuer = ""
        tlsProtocol = ""; tlsCipher = ""; authStatus = null
        headUnit = ""; profile = ""; video = ""; lastStream = ""; lastState = ""
        printed = false
    }

    fun protocolVersion(major: Int, minor: Int) { protocolVersion = "$major.$minor" }
    fun certificate(source: String, issuer: String) { certSource = source; certIssuer = issuer }
    fun tls(protocol: String, cipher: String) { tlsProtocol = protocol; tlsCipher = cipher }
    fun auth(status: Int) { authStatus = status }
    fun headUnit(info: String) { headUnit = info }
    fun profile(name: String?) { profile = name ?: "none matched" }
    fun video(summary: String) { video = summary }
    fun audio(summary: String) { audio = summary }
    fun stream(summary: String) { lastStream = summary }

    /** Records the first failure only: later ones are usually fallout from it. */
    fun fail(category: Category, detail: String, state: String = "") {
        if (this.category != Category.NONE) return
        this.category = category
        failure = detail
        lastState = state
    }

    fun fail(category: Category, detail: String, t: Throwable, state: String = "") =
        fail(category, "$detail (${GearslipLog.rootCause(t)})", state)

    /** The report as plain text, for the log file and for anything that gets sent. */
    fun render(): String {
        val seconds = if (startedAt == 0L) 0 else (System.currentTimeMillis() - startedAt) / 1000
        fun row(name: String, value: String) = if (value.isEmpty()) "" else "  ${name.padEnd(16)}$value\n"
        return buildString {
            append("=".repeat(60)).append('\n')
            append("SESSION REPORT\n")
            append(row("result", if (category == Category.NONE) "ok / no failure recorded" else "FAILED - ${category.label}"))
            append(row("detail", failure))
            append(row("failed in state", lastState))
            append(row("duration", "${seconds}s"))
            append("\n")
            append(row("gearslip", "$appVersion (code $appCode)"))
            append(row("phone", "${Build.MANUFACTURER} ${Build.MODEL}"))
            append(row("android", "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}, patch ${Build.VERSION.SECURITY_PATCH})"))
            append("\n")
            append(row("head unit", headUnit))
            append(row("AA protocol", protocolVersion))
            append(row("profile", profile))
            append("\n")
            append(row("phone cert", certSource))
            append(row("cert issuer", certIssuer))
            append(row("TLS", listOf(tlsProtocol, tlsCipher).filter { it.isNotEmpty() }.joinToString(" / ")))
            append(row("auth status", authStatus?.let { "$it (${authName(it)})" } ?: ""))
            append("\n")
            append(row("video", video))
            append(row("audio", audio))
            append(row("last stream stat", lastStream))
            append("=".repeat(60))
        }
    }

    /** Prints the report into the log, once per session. */
    fun print() {
        if (printed || startedAt == 0L) return
        printed = true
        render().lines().forEach { GearslipLog.i(it) }
        GearslipLog.flush()
    }

    private fun authName(status: Int) = when (status) {
        0 -> "success"
        -2 -> "certificate error"
        -3 -> "authentication failure"
        else -> "unknown"
    }
}
