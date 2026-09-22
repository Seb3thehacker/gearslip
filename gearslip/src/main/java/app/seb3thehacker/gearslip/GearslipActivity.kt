package app.seb3thehacker.gearslip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import android.content.res.Configuration
import app.seb3thehacker.gearslip.audio.AudioCaptureService
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.mirror.MirrorService
import app.seb3thehacker.gearslip.mirror.PhoneMirror
import app.seb3thehacker.gearslip.mirror.TouchRelayService
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.CarUi
import app.seb3thehacker.gearslip.car.Prefetch
import app.seb3thehacker.gearslip.ui.GearslipApp
import app.seb3thehacker.gearslip.ui.GearslipTheme
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Hosts the phone UI and, for now, the car session itself.
 *
 * Launched by the system when the head unit switches the phone into accessory mode. The
 * session lives in this activity, so [android:configChanges] in the manifest keeps a
 * rotation or theme change from recreating it and dropping the connection.
 */
class GearslipActivity : ComponentActivity(), Projection {

    private var descriptor: ParcelFileDescriptor? = null
    private var runner: GearslipRunner? = null
    private var screenProjector: ScreenProjector? = null
    private var worker: Thread? = null

    /** The encoder's input Surface, kept so the car's picture can be switched to the mirror. */
    private var surface: Surface? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameDensity = 0

    /**
     * Screen capture always begins with a consent dialog, which only an activity can show -
     * so the car UI asks, and the request surfaces here on the phone.
     */
    private val captureConsent = registerForActivityResult(StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            MirrorService.start(this, result.resultCode, data)
        } else {
            GearslipLog.w("screen capture consent declined")
        }
    }

    /** Media audio needs the microphone permission (a platform rule for playback capture) first. */
    private val audioPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) askAudioCapture() else {
            GearslipLog.w("audio: record permission declined")
            CarAudio.denied()
        }
    }

    private val audioConsent = registerForActivityResult(StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            AudioCaptureService.start(this, result.resultCode, data)
        } else {
            GearslipLog.w("audio: capture consent declined")
            CarAudio.denied()
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                GearslipLog.w("accessory detached")
                closeAccessory()
            }
        }
    }

    /** Hosted map apps need Gearslip to hold location so they can keep it in the background. */
    private val locationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) Prefetch.warm(this, force = true) }

    /** The car dashboard's agenda card; declined just leaves it empty rather than asking again. */
    private val calendarPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) Prefetch.warm(this, force = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) calendarPermission.launch(android.Manifest.permission.READ_CALENDAR)
        // Playback capture needs the microphone even though it never records one. Ask for it here,
        // at the kerb, rather than the first time a song plays: a permission dialog that appears
        // mid-drive is one the driver has to read and answer at the wheel, and missing it is
        // indistinguishable from the car link being broken.
        if (CarSettings.pipeAudio.value &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        GearslipLog.init(this)
        GearslipLog.installCrashHandler()
        GearslipLog.i("Gearslip ready")
        CarSettings.init(this)
        // A capture that died without tidying up would leave notifications exposed to every
        // later screen share. Nothing is capturing if the service is not running, so undo it.
        if (!app.seb3thehacker.gearslip.audio.AudioCaptureService.running) {
            app.seb3thehacker.gearslip.audio.ScreenShareGuard.restore(this)
        }
        Prefetch.warm(this)
        CarEnvironment.setPhoneTheme(resources.configuration)

        setContent {
            GearslipTheme {
                GearslipApp(onDisconnect = ::closeAccessory)
            }
        }

        PhoneMirror.onStartRequested = { runOnUiThread { requestCapture() } }
        PhoneMirror.onTokenReady = { token -> runOnUiThread { showMirror(token) } }
        PhoneMirror.onEnded = { runOnUiThread { showCarUi() } }
        CarAudio.onConsentRequested = { runOnUiThread { requestAudioCapture() } }

        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), RECEIVER_NOT_EXPORTED)
        handleIntent(intent)
    }

    // The manifest opts out of recreation on uiMode changes, so a light/dark switch on the phone
    // arrives here, and the car UI follows it.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CarEnvironment.setPhoneTheme(newConfig)
    }

    // Throttled inside Prefetch, so this only does work if the last run was a while ago.
    override fun onStart() {
        super.onStart()
        Prefetch.warm(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(detachReceiver) }
        PhoneMirror.onStartRequested = null
        PhoneMirror.onTokenReady = null
        PhoneMirror.onEnded = null
        CarAudio.onConsentRequested = null
        CarAudio.release()
        PhoneMirror.requestStop()
        closeAccessory()
        GearslipLog.flush()
    }

    // --- Media audio --------------------------------------------------------------------

    private fun requestAudioCapture() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askAudioCapture()
        } else {
            audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun askAudioCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { audioConsent.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                GearslipLog.e("could not ask for audio capture", it)
                CarAudio.denied()
            }
    }

    // --- Phone mirroring ----------------------------------------------------------------

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { captureConsent.launch(manager.createScreenCaptureIntent()) }
            .onFailure { GearslipLog.e("could not ask for screen capture", it) }
    }

    /** A Surface has a single producer, so our own display must go before the mirror arrives. */
    private fun showMirror(token: MediaProjection) {
        val target = surface ?: return
        screenProjector?.stop()
        screenProjector = null
        PhoneMirror.begin(token, target, frameWidth, frameHeight, frameDensity)
        if (PhoneMirror.active.value) PhoneMirror.launchPending(this) else showCarUi()
    }

    private fun showCarUi() {
        val target = surface ?: return
        if (screenProjector != null) return
        val projector = ScreenProjector(this).also { screenProjector = it }
        projector.start(target, frameWidth, frameHeight, frameDensity) { CarUi() }
    }

    /**
     * Sends a head-unit touch to the phone. Anything landing on the mirror's letterbox padding
     * maps to nothing on the phone and is dropped rather than clamped onto a screen edge.
     */
    private fun relayTouch(action: Int, x: Float, y: Float) {
        val point = PhoneMirror.mapToPhone(this, x, y)
        if (point == null) {
            // The letterbox bars belong to no window, which makes them a safe way back: a tap
            // on the padding leaves the mirror and returns the car to Gearslip's own UI.
            if (action == MotionEvent.ACTION_UP) PhoneMirror.requestStop()
            return
        }
        val relay = TouchRelayService.instance ?: return
        when (action) {
            MotionEvent.ACTION_DOWN -> relay.down(point.x, point.y)
            MotionEvent.ACTION_MOVE -> relay.move(point.x, point.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> relay.up(point.x, point.y)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        if (accessory == null) {
            GearslipLog.e("attach intent carried no accessory")
            SessionStatus.failed("Accessory missing", "The system reported a USB attach but did not include the accessory.")
            return
        }
        GearslipLog.i("accessory attached by the system - permission is implicit on this path")
        start(accessory)
    }

    private fun start(accessory: UsbAccessory) {
        if (worker?.isAlive == true) {
            GearslipLog.w("a run is already in progress")
            return
        }
        describe(accessory)

        val manager = getSystemService(UsbManager::class.java)
        val pfd = manager.openAccessory(accessory)
        if (pfd == null) {
            GearslipLog.e("openAccessory returned null - the session was claimed by someone else")
            SessionStatus.failed("Accessory unavailable", "Another app claimed the USB accessory before Gearslip could open it.")
            return
        }
        descriptor = pfd

        val runner = GearslipRunner(
            input = FileInputStream(pfd.fileDescriptor),
            output = FileOutputStream(pfd.fileDescriptor),
            identityProvider = { CertProvider.load(this) },
            projection = this,
            vehicleProfileFor = { info -> VehicleProfiles.find(VehicleProfiles.load(this), info) },
        )
        this.runner = runner
        worker = Thread { runner.run() }.apply { name = "gearslip"; start() }
    }

    private fun describe(accessory: UsbAccessory) {
        GearslipLog.i("accessory: manufacturer=${accessory.manufacturer} model=${accessory.model}")
        GearslipLog.i("           version=${accessory.version} description=${accessory.description}")
    }

    private fun closeAccessory() {
        runner?.stop()
        SessionReport.print()
        runCatching { descriptor?.close() }
        descriptor = null
        runner = null
    }

    // --- Projection ---------------------------------------------------------------------

    override fun onSurfaceReady(surface: Surface, width: Int, height: Int, densityDpi: Int) {
        runOnUiThread {
            this.surface = surface
            frameWidth = width
            frameHeight = height
            frameDensity = densityDpi
            CarEnvironment.setDisplay(width, height, densityDpi)
            CarEnvironment.start(this)
            showCarUi()
        }
    }

    override fun onTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        runOnUiThread {
            // Mirroring drives one phone touch at a time, so it follows the first finger only.
            if (PhoneMirror.active.value) points.firstOrNull()?.let { relayTouch(action, it.x, it.y) }
            else screenProjector?.dispatchTouch(action, actionIndex, points)
        }
    }

    override fun onProjectionStopped() {
        runOnUiThread {
            PhoneMirror.requestStop()
            screenProjector?.stop()
            screenProjector = null
            surface = null
            CarEnvironment.stop()
        }
    }
}
