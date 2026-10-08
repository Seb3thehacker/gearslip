package app.seb3thehacker.gearslip.wireless

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.Wire
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Wireless Android Auto, phone side: the start-up that hands Gearslip a socket instead of a USB
 * accessory. Everything after it - versions, TLS, the certificate, channels, video - is the same
 * protocol [app.seb3thehacker.gearslip.GearslipRunner] already speaks over USB.
 *
 *   1. Bluetooth: open RFCOMM to the car's Android Auto wireless service. The car must already be
 *      paired with the phone, as it usually is for calls.
 *   2. Over that channel, framed as [u16 length][u16 message id][protobuf]:
 *        <- VersionRequest            -> VersionResponse
 *        <- StartRequest {ip, port}   -> InfoRequest
 *        <- InfoResponse {ssid, password, bssid, security}
 *   3. Join the car's Wi-Fi as a local-only network, so the phone's internet stays on mobile data.
 *        -> StartResponse {status}    -> ConnectionStatus {status}
 *   4. TCP to {ip, port} over that network, and hand the socket to [onConnected].
 *
 * The Bluetooth channel stays open for the whole drive: the car pings over it, and some cars end
 * the session when it closes. Message layout from LIVI's head-unit side (livi-aa/src/wpp.rs) and
 * aasdk's aaw protos.
 */
object WirelessLink {

    private val log = GearslipLog.tagged("AAW")

    /** The Android Auto wireless service every head unit publishes over Bluetooth. */
    val AA_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")

    private const val MSG_START_REQUEST = 1
    private const val MSG_INFO_REQUEST = 2
    private const val MSG_INFO_RESPONSE = 3
    private const val MSG_VERSION_REQUEST = 4
    private const val MSG_VERSION_RESPONSE = 5
    private const val MSG_CONNECTION_STATUS = 6
    private const val MSG_START_RESPONSE = 7
    private const val MSG_PING = 8
    private const val MSG_PONG = 9

    private const val TCP_TRIES = 5
    private const val WIFI_WAIT_MS = 60_000

    /** Set by the activity: starts a session on the car's socket. */
    @Volatile var onConnected: ((Socket) -> Unit)? = null

    @Volatile private var rfcomm: BluetoothSocket? = null
    @Volatile private var tcp: Socket? = null
    @Volatile private var network: ConnectivityManager.NetworkCallback? = null
    @Volatile private var worker: Thread? = null
    private val writeLock = Any()

    /** Paired Bluetooth devices, cars or not: the driver picks theirs. Needs BLUETOOTH_CONNECT. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<BluetoothDevice> =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
            ?.sortedBy { it.name ?: it.address }
            .orEmpty()

    @SuppressLint("MissingPermission")
    fun name(device: BluetoothDevice): String = device.name ?: device.address

    fun start(context: Context, device: BluetoothDevice) {
        stop(context)
        val app = context.applicationContext
        worker = Thread({ run(app, device) }, "gearslip-wireless").apply { isDaemon = true; start() }
    }

    /** Closes the Bluetooth channel, the socket and the Wi-Fi request. Safe to call any time. */
    fun stop(context: Context) {
        worker?.interrupt()
        worker = null
        runCatching { rfcomm?.close() }
        rfcomm = null
        runCatching { tcp?.close() }
        tcp = null
        network?.let { cb ->
            runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        network = null
    }

    @SuppressLint("MissingPermission")
    private fun run(context: Context, device: BluetoothDevice) {
        val name = name(device)
        log.i("--- wireless start-up with $name (${device.address}) ---")
        SessionStatus.connecting("Bluetooth: reaching $name")
        val socket = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()
            device.createRfcommSocketToServiceRecord(AA_UUID).also { it.connect() }
        } catch (e: IOException) {
            log.e("Bluetooth: could not open the Android Auto channel on $name", e)
            SessionStatus.failed(
                "Car didn't answer over Bluetooth",
                "$name has no wireless Android Auto service, or it's turned off in the car. Check that " +
                    "the car is paired and wireless Android Auto is on.",
            )
            return
        }
        rfcomm = socket
        log.i("Bluetooth: channel open")
        SessionStatus.connecting("Bluetooth: talking to $name")

        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream
        var target: Pair<String, Int>? = null
        try {
            while (!Thread.currentThread().isInterrupted) {
                val length = input.readUnsignedShort()
                val id = input.readUnsignedShort()
                val body = ByteArray(length).also { input.readFully(it) }
                when (id) {
                    MSG_VERSION_REQUEST -> {
                        log.i("<- VersionRequest")
                        log.hex("   version", body)
                        // The fields' meaning isn't known; answering with the car's own numbers
                        // has worked on LIVI. Field 4 is required, so it goes as 0.
                        val f = Wire.fields(body)
                        val reply = Protobuf.varintField(1, Wire.varint(f, 1) ?: 1) +
                            Protobuf.varintField(2, Wire.varint(f, 2) ?: 0) +
                            Protobuf.varintField(4, 0)
                        send(output, MSG_VERSION_RESPONSE, reply)
                        log.i("-> VersionResponse")
                    }
                    MSG_START_REQUEST -> {
                        val f = Wire.fields(body)
                        val ip = f.firstOrNull { it.number == 1 && it.wireType == 2 }?.bytes?.decodeToString().orEmpty()
                        val port = Wire.varint(f, 2)?.toInt() ?: 0
                        log.i("<- StartRequest: projection at $ip:$port")
                        target = ip to port
                        send(output, MSG_INFO_REQUEST, ByteArray(0))
                        log.i("-> InfoRequest")
                    }
                    MSG_INFO_RESPONSE -> {
                        val f = Wire.fields(body)
                        fun text(n: Int) = f.firstOrNull { it.number == n && it.wireType == 2 }?.bytes?.decodeToString().orEmpty()
                        val ssid = text(1)
                        val password = text(2)
                        val bssid = text(3)
                        val security = Wire.varint(f, 4) ?: -1
                        log.i("<- InfoResponse: ssid=\"$ssid\" bssid=$bssid security=$security password=${if (password.isEmpty()) "none" else "(${password.length} chars)"}")
                        val (ip, port) = target ?: run {
                            log.w("InfoResponse before StartRequest: no address to connect to yet")
                            "" to 0
                        }
                        joinWifi(context, output, name, ssid, password, bssid, ip, port)
                    }
                    MSG_PING -> send(output, MSG_PONG, body)
                    MSG_CONNECTION_STATUS, MSG_START_RESPONSE -> {
                        log.i("<- message $id: status=${Wire.varint(Wire.fields(body), if (id == MSG_START_RESPONSE) 3 else 1)}")
                    }
                    else -> {
                        log.i("<- unknown message $id (${body.size} bytes)")
                        log.hex("   msg", body)
                    }
                }
            }
        } catch (e: IOException) {
            if (rfcomm != null) log.w("Bluetooth: channel closed (${e.message})")
        }
    }

    private fun send(output: OutputStream, id: Int, body: ByteArray) {
        val frame = ByteArray(4 + body.size)
        frame[0] = (body.size shr 8).toByte(); frame[1] = body.size.toByte()
        frame[2] = (id shr 8).toByte(); frame[3] = id.toByte()
        body.copyInto(frame, 4)
        synchronized(writeLock) { output.write(frame); output.flush() }
    }

    /**
     * Asks Android for the car's network. The first time, Android shows a "Connect to device"
     * prompt on the phone; the driver has to accept it.
     */
    private fun joinWifi(
        context: Context, output: OutputStream, name: String,
        ssid: String, password: String, bssid: String, ip: String, port: Int,
    ) {
        SessionStatus.connecting("Wi-Fi: joining $name's network")
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            runCatching { MacAddress.fromString(bssid) }.getOrNull()
                ?.takeIf { it != MacAddress.fromString("00:00:00:00:00:00") }
                ?.let { setBssid(it) }
            if (password.isNotEmpty()) setWpa2Passphrase(password)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                log.i("Wi-Fi: joined \"$ssid\"")
                Thread({ connectTcp(output, name, net, ip, port) }, "gearslip-wireless-tcp").start()
            }

            override fun onUnavailable() {
                log.w("Wi-Fi: couldn't join \"$ssid\" (declined, out of range, or wrong password)")
                runCatching { send(output, MSG_CONNECTION_STATUS, Protobuf.varintField(1, -11)) }
                SessionStatus.failed(
                    "Couldn't join the car's Wi-Fi",
                    "Android didn't connect to $name's network. If a \"Connect to device\" prompt " +
                        "appeared, it has to be accepted.",
                )
            }

            override fun onLost(net: Network) {
                log.w("Wi-Fi: lost \"$ssid\"")
            }
        }
        network?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        network = callback
        manager.requestNetwork(request, callback, WIFI_WAIT_MS)
    }

    private fun connectTcp(output: OutputStream, name: String, net: Network, ip: String, port: Int) {
        SessionStatus.connecting("Wi-Fi: reaching $name")
        var last: Exception? = null
        repeat(TCP_TRIES) { attempt ->
            try {
                val socket = net.socketFactory.createSocket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, port), 5_000)
                tcp = socket
                log.i("TCP: connected to $ip:$port (try ${attempt + 1})")
                runCatching {
                    send(output, MSG_START_RESPONSE, Protobuf.varintField(3, 0))
                    send(output, MSG_CONNECTION_STATUS, Protobuf.varintField(1, 0))
                }
                val start = onConnected
                if (start == null) {
                    log.w("TCP: connected, but nothing is waiting for the socket")
                    socket.close()
                } else {
                    start(socket)
                }
                return
            } catch (e: Exception) {
                last = e
                log.w("TCP: try ${attempt + 1} to $ip:$port failed: ${e.message}")
                Thread.sleep(1_000)
            }
        }
        log.e("TCP: gave up on $ip:$port", last)
        SessionStatus.failed(
            "Car's Wi-Fi joined, but no connection",
            "Gearslip joined $name's network but couldn't reach Android Auto at $ip:$port.",
        )
    }
}
