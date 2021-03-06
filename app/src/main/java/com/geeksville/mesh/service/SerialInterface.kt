package com.geeksville.mesh.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.geeksville.android.Logging
import com.geeksville.util.exceptionReporter
import com.geeksville.util.ignoreException
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager


class SerialInterface(private val service: RadioInterfaceService, val address: String) : Logging,
    IRadioInterface, SerialInputOutputManager.Listener {
    companion object : Logging {
        private const val START1 = 0x94.toByte()
        private const val START2 = 0xc3.toByte()
        private const val MAX_TO_FROM_RADIO_SIZE = 512

        /**
         * according to https://stackoverflow.com/questions/12388914/usb-device-access-pop-up-suppression/15151075#15151075
         * we should never ask for USB permissions ourselves, instead we should rely on the external dialog printed by the system.  If
         * we do that the system will remember we have accesss
         */
        val assumePermission = true

        fun toInterfaceName(deviceName: String) = "s$deviceName"

        fun findDrivers(context: Context): List<UsbSerialDriver> {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            val devices = drivers.map { it.device }
            devices.forEach { d ->
                debug("Found serial port ${d.deviceName}")
            }
            return drivers
        }

        fun addressValid(context: Context, rest: String): Boolean {
            findSerial(context, rest)?.let { d ->
                val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                return assumePermission || manager.hasPermission(d.device)
            }
            return false
        }

        fun findSerial(context: Context, rest: String): UsbSerialDriver? {
            val drivers = findDrivers(context)

            return if (drivers.isEmpty())
                null
            else  // Open a connection to the first available driver.
                drivers[0] // FIXME, instead we should find by name
        }
    }

    private var uart: UsbSerialDriver? = null
    private var ioManager: SerialInputOutputManager? = null

    var usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = exceptionReporter {

            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                debug("A USB device was detached")
                val device: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)!!
                if (uart?.device == device)
                    onDeviceDisconnect(true)
            }

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                debug("attaching USB")
                val device: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)!!
                val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                if (assumePermission || manager.hasPermission(device)) {
                    // reinit the port from scratch and reopen
                    onDeviceDisconnect(true)
                    connect()
                } else {
                    warn("We don't have permissions for this USB device")
                }
            }
        }
    }

    private val debugLineBuf = kotlin.text.StringBuilder()

    /** The index of the next byte we are hoping to receive */
    private var ptr = 0

    /** The two halves of our length */
    private var msb = 0
    private var lsb = 0
    private var packetLen = 0

    init {
        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        service.registerReceiver(usbReceiver, filter)

        connect()
    }

    override fun close() {
        debug("Closing serial port for good")
        service.unregisterReceiver(usbReceiver)
        onDeviceDisconnect(true)
    }

    /** Tell MeshService our device has gone away, but wait for it to come back
     *
     * @param waitForStopped if true we should wait for the manager to finish - must be false if called from inside the manager callbacks
     *  */
    fun onDeviceDisconnect(waitForStopped: Boolean) {
        ignoreException {
            ioManager?.let {
                debug("USB device disconnected, but it might come back")
                it.stop()

                // Allow a short amount of time for the manager to quit (so the port can be cleanly closed)
                if (waitForStopped) {
                    val msecSleep = 50L
                    var numTries = 1000 / msecSleep
                    while (it.state != SerialInputOutputManager.State.STOPPED && numTries > 0) {
                        debug("Waiting for USB manager to stop...")
                        Thread.sleep(msecSleep)
                        numTries -= 1
                    }
                }

                ioManager = null
            }
        }

        ignoreException {
            uart?.apply {
                ports[0].close() // This will cause the reader thread to exit

                uart = null
            }
        }

        service.onDisconnect(isPermanent = true) // if USB device disconnects it is definitely permantently gone, not sleeping)
    }

    private fun connect() {
        val manager = service.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findSerial(service, address)

        if (device != null) {
            info("Opening $device")
            val connection =
                manager.openDevice(device.device) // This can fail with "Control Transfer failed" if port was aleady open
            if (connection == null) {
                // FIXME add UsbManager.requestPermission(device, ..) handling to activity
                errormsg("Need permissions for port")
            } else {
                val port = device.ports[0] // Most devices have just one port (port 0)

                port.open(connection)
                port.setParameters(921600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                uart = device

                debug("Starting serial reader thread")
                val io = SerialInputOutputManager(port, this)
                io.readTimeout = 200 // To save battery we only timeout ever so often
                ioManager = io

                val thread = Thread(io)
                thread.isDaemon = true
                thread.priority = Thread.MAX_PRIORITY
                thread.name = "serial reader"
                thread.start() // No need to keep reference to thread around, we quit by asking the ioManager to quit

                // Before telling mesh service, send a few START1s to wake a sleeping device
                val wakeBytes = byteArrayOf(START1, START1, START1, START1)
                io.writeAsync(wakeBytes)

                // Now tell clients they can (finally use the api)
                service.onConnect()
            }
        } else {
            errormsg("Can't find device")
        }
    }

    override fun handleSendToRadio(p: ByteArray) {
        // This method is called from a continuation and it might show up late, so check for uart being null

        val header = ByteArray(4)
        header[0] = START1
        header[1] = START2
        header[2] = (p.size shr 8).toByte()
        header[3] = (p.size and 0xff).toByte()
        ioManager?.apply {
            writeAsync(header)
            writeAsync(p)
        }
    }


    /** Print device serial debug output somewhere */
    private fun debugOut(b: Byte) {
        when (val c = b.toChar()) {
            '\r' -> {
            } // ignore
            '\n' -> {
                debug("DeviceLog: $debugLineBuf")
                debugLineBuf.clear()
            }
            else ->
                debugLineBuf.append(c)
        }
    }

    private val rxPacket = ByteArray(MAX_TO_FROM_RADIO_SIZE)

    private fun readChar(c: Byte) {
        // Assume we will be advancing our pointer
        var nextPtr = ptr + 1

        fun lostSync() {
            errormsg("Lost protocol sync")
            nextPtr = 0
        }

        /// Deliver our current packet and restart our reader
        fun deliverPacket() {
            val buf = rxPacket.copyOf(packetLen)
            service.handleFromRadio(buf)

            nextPtr = 0 // Start parsing the next packet
        }

        when (ptr) {
            0 -> // looking for START1
                if (c != START1) {
                    debugOut(c)
                    nextPtr = 0 // Restart from scratch
                }
            1 -> // Looking for START2
                if (c != START2)
                    lostSync() // Restart from scratch
            2 -> // Looking for MSB of our 16 bit length
                msb = c.toInt() and 0xff
            3 -> { // Looking for LSB of our 16 bit length
                lsb = c.toInt() and 0xff

                // We've read our header, do one big read for the packet itself
                packetLen = (msb shl 8) or lsb
                if (packetLen > MAX_TO_FROM_RADIO_SIZE)
                    lostSync()  // If packet len is too long, the bytes must have been corrupted, start looking for START1 again
                else if (packetLen == 0)
                    deliverPacket() // zero length packets are valid and should be delivered immediately (because there won't be a next byte of payload)
            }
            else -> {
                // We are looking at the packet bytes now
                rxPacket[ptr - 4] = c

                // Note: we have to check if ptr +1 is equal to packet length (for example, for a 1 byte packetlen, this code will be run with ptr of4
                if (ptr - 4 + 1 == packetLen) {
                    deliverPacket()
                }
            }
        }
        ptr = nextPtr
    }


    /**
     * Called when [SerialInputOutputManager.run] aborts due to an error.
     */
    override fun onRunError(e: java.lang.Exception) {
        errormsg("Serial error: $e")

        onDeviceDisconnect(false)
    }

    /**
     * Called when new incoming data is available.
     */
    override fun onNewData(data: ByteArray) {
        data.forEach(::readChar)
    }
}