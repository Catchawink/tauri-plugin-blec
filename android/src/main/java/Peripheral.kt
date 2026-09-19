import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.tauri.plugin.Channel
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import com.plugin.blec.BleClientPlugin
import org.json.JSONArray
import java.util.UUID

private fun bytesToJson(bytes: ByteArray): JSONArray {
    val array = JSONArray()

    // Kotlin Byte is signed (-128..127), while Rust Vec<u8> expects 0..255.
    for (byte in bytes) {
        array.put(byte.toInt() and 0xFF)
    }

    return array
}

class Peripheral(
    private val activity: Activity,
    private val device: BluetoothDevice,
    private val plugin: BleClientPlugin
) {
    companion object {
        private val CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Ask Android for the maximum ATT MTU. The peripheral will negotiate
        // this down to the largest value it actually supports.
        private const val REQUESTED_MTU = 517

        // Don't allow a missing onMtuChanged callback to stall connection
        // forever. If MTU negotiation doesn't finish, continue with whatever
        // MTU Android currently has.
        private const val MTU_NEGOTIATION_TIMEOUT_MS = 3000L
    }

    private var connected = false
    private var connectionReady = false

    private var gatt: BluetoothGatt? = null

    private var services: List<BluetoothGattService> = listOf()

    private val characteristics:
        MutableMap<UUID, BluetoothGattCharacteristic> = mutableMapOf()

    private var onConnectionStateChange:
        ((connected: Boolean, error: String) -> Unit)? = null

    private var onServicesDiscovered:
        ((connected: Boolean, error: String) -> Unit)? = null

    private var notifyChannel: Channel? = null

    private val onReadInvoke:
        MutableMap<UUID, Invoke> = mutableMapOf()

    private val onWriteInvoke:
        MutableMap<UUID, Invoke> = mutableMapOf()

    private var onDescriptorInvoke: Invoke? = null

    private var mtuRequestPending = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val mtuTimeoutRunnable = Runnable {
        if (mtuRequestPending && connected && !connectionReady) {
            Log.w(
                "BLEC",
                "MTU negotiation timed out; continuing with current MTU"
            )

            mtuRequestPending = false
            finishConnection()
        }
    }

    private enum class Event {
        DeviceConnected,
        DeviceDisconnected
    }

    private fun sendEvent(event: Event) {
        val channel = plugin.eventChannel ?: return
        val data = JSObject()

        when (event) {
            Event.DeviceConnected -> {
                data.put("DeviceConnected", device.address)
            }

            Event.DeviceDisconnected -> {
                data.put("DeviceDisconnected", device.address)
            }
        }

        println("sending event $data")
        channel.send(data)
    }

    /**
     * Finish the logical connection only after MTU negotiation has completed
     * (or failed/timed out).
     *
     * This keeps service discovery / subscription / transport setup from
     * beginning while Android is still negotiating the ATT MTU.
     */
    private fun finishConnection() {
        if (!connected || connectionReady) {
            return
        }

        connectionReady = true
        mtuRequestPending = false

        mainHandler.removeCallbacks(mtuTimeoutRunnable)

        Log.i("BLEC", "BLE connection ready")

        onConnectionStateChange?.invoke(true, "")
        sendEvent(Event.DeviceConnected)
    }

    private fun handleCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val channel = notifyChannel ?: return

        val notification = JSObject()
        notification.put("uuid", characteristic.uuid.toString())
        notification.put("data", bytesToJson(value))

        channel.send(notification)
    }

    private fun handleCharacteristicRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        val id = characteristic.uuid

        synchronized(onReadInvoke) {
            val invoke = onReadInvoke[id]

            if (invoke == null) {
                Log.e(
                    "Peripheral",
                    "Did not find Tauri invoke object for read on $id"
                )
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                invoke.reject(
                    "Read from characteristic $id failed with status $status"
                )
            } else {
                val res = JSObject()
                res.put("value", bytesToJson(value))
                invoke.resolve(res)
            }

            onReadInvoke.remove(id)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt?,
            status: Int,
            newState: Int
        ) {
            if (
                status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothGatt.STATE_CONNECTED &&
                gatt != null
            ) {
                this@Peripheral.connected = true
                this@Peripheral.connectionReady = false
                this@Peripheral.gatt = gatt

                Log.i(
                    "BLEC",
                    "GATT connected; requesting ATT MTU $REQUESTED_MTU"
                )

                mainHandler.removeCallbacks(mtuTimeoutRunnable)

                val started = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                ) {
                    gatt.requestMtu(REQUESTED_MTU)
                } else {
                    false
                }

                this@Peripheral.mtuRequestPending = started

                if (started) {
                    mainHandler.postDelayed(
                        mtuTimeoutRunnable,
                        MTU_NEGOTIATION_TIMEOUT_MS
                    )
                } else {
                    Log.w(
                        "BLEC",
                        "Could not start MTU negotiation; continuing with current MTU"
                    )

                    this@Peripheral.finishConnection()
                }

                return
            }

            mainHandler.removeCallbacks(mtuTimeoutRunnable)

            this@Peripheral.mtuRequestPending = false
            this@Peripheral.connectionReady = false
            this@Peripheral.connected = false
            this@Peripheral.gatt = null

            this@Peripheral.services = listOf()
            this@Peripheral.characteristics.clear()

            this@Peripheral.onConnectionStateChange?.invoke(
                false,
                "Not connected. Status: $status, State: $newState"
            )

            this@Peripheral.sendEvent(Event.DeviceDisconnected)
        }

        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            status: Int
        ) {
            Log.i(
                "BLEC",
                "MTU changed: mtu=$mtu status=$status"
            )

            if (!this@Peripheral.mtuRequestPending) {
                return
            }

            mainHandler.removeCallbacks(mtuTimeoutRunnable)

            this@Peripheral.mtuRequestPending = false

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(
                    "BLEC",
                    "MTU negotiation failed with status $status; " +
                        "continuing with current MTU"
                )
            }

            this@Peripheral.finishConnection()
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                this@Peripheral.services = listOf()
                this@Peripheral.characteristics.clear()

                this@Peripheral.onServicesDiscovered?.invoke(
                    false,
                    "No services discovered. Status $status"
                )

                return
            }

            this@Peripheral.services = gatt.services

            this@Peripheral.characteristics.clear()

            for (service in gatt.services) {
                for (characteristic in service.characteristics) {
                    this@Peripheral.characteristics[characteristic.uuid] =
                        characteristic
                }
            }

            this@Peripheral.onServicesDiscovered?.invoke(true, "")
        }

        // Android 13+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            this@Peripheral.handleCharacteristicChanged(
                characteristic,
                value
            )
        }

        // Android 12 and below
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return

            this@Peripheral.handleCharacteristicChanged(
                characteristic,
                value
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            val id = characteristic?.uuid ?: return

            synchronized(this@Peripheral.onWriteInvoke) {
                val invoke = this@Peripheral.onWriteInvoke[id]

                if (invoke == null) {
                    Log.e(
                        "Peripheral",
                        "Did not find Tauri invoke object for write on $id"
                    )
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    invoke.reject(
                        "Write to characteristic $id failed with status $status"
                    )
                } else {
                    invoke.resolve()
                }

                this@Peripheral.onWriteInvoke.remove(id)
            }
        }

        // Android 13+
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            this@Peripheral.handleCharacteristicRead(
                characteristic,
                value,
                status
            )
        }

        // Android 12 and below
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val value = characteristic.value ?: byteArrayOf()

            this@Peripheral.handleCharacteristicRead(
                characteristic,
                value,
                status
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            val invoke = this@Peripheral.onDescriptorInvoke

            this@Peripheral.onDescriptorInvoke = null

            if (invoke == null) {
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                invoke.reject(
                    "Descriptor write failed with status: $status"
                )

                return
            }

            if (
                descriptor?.uuid !=
                CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR
            ) {
                invoke.reject(
                    "Unexpected descriptor write: ${descriptor?.uuid}"
                )

                return
            }

            invoke.resolve()
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(invoke: Invoke) {
        mainHandler.removeCallbacks(mtuTimeoutRunnable)

        mtuRequestPending = false
        connectionReady = false

        onConnectionStateChange = { success, error ->
            if (success) {
                invoke.resolve()
            } else {
                invoke.reject(error)
            }

            this@Peripheral.onConnectionStateChange = null
        }

        device.connectGatt(
            activity,
            false,
            callback
        )
    }

    @SuppressLint("MissingPermission")
    fun discoverServices(invoke: Invoke) {
        val gatt = this.gatt

        if (gatt == null) {
            invoke.reject("No GATT server connected")
            return
        }

        onServicesDiscovered = { success, error ->
            if (success) {
                invoke.resolve()
            } else {
                invoke.reject(error)
            }

            this@Peripheral.onServicesDiscovered = null
        }

        if (!gatt.discoverServices()) {
            onServicesDiscovered = null

            invoke.reject(
                "Failed to start BLE service discovery"
            )
        }
    }

    fun isConnected(): Boolean {
        return connected
    }

    @SuppressLint("MissingPermission")
    fun disconnect(invoke: Invoke) {
        mainHandler.removeCallbacks(mtuTimeoutRunnable)

        mtuRequestPending = false
        connectionReady = false

        gatt?.disconnect()

        connected = false

        invoke.resolve()
    }

    /**
     * Return the services/characteristics discovered by Android.
     *
     * IMPORTANT:
     * charac.properties must be passed through here. Older versions of this
     * plugin hard-coded this field to zero, which broke notification detection.
     */
    fun services(invoke: Invoke) {
        val servicesJson = JSONArray()

        for (service in services) {
            val characteristicsJson = JSONArray()

            for (characteristic in service.characteristics) {
                val descriptorsJson = JSONArray()

                for (descriptor in characteristic.descriptors) {
                    descriptorsJson.put(
                        descriptor.uuid.toString()
                    )
                }

                val characteristicJson = JSObject()
                characteristicJson.put(
                    "uuid",
                    characteristic.uuid.toString()
                )
                characteristicJson.put(
                    "properties",
                    characteristic.properties
                )
                characteristicJson.put(
                    "descriptors",
                    descriptorsJson
                )

                characteristicsJson.put(characteristicJson)
            }

            val serviceJson = JSObject()
            serviceJson.put(
                "uuid",
                service.uuid.toString()
            )
            serviceJson.put(
                "primary",
                service.type ==
                    BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            serviceJson.put(
                "characs",
                characteristicsJson
            )

            servicesJson.put(serviceJson)
        }

        val result = JSObject()
        result.put("result", servicesJson)

        invoke.resolve(result)
    }

    fun setNotifyChannel(channel: Channel) {
        notifyChannel = channel
    }

    @SuppressLint("MissingPermission")
    fun write(invoke: Invoke) {
        val args =
            invoke.parseArgs(BleClientPlugin.WriteParams::class.java)

        val gatt = this.gatt

        if (gatt == null) {
            invoke.reject("No GATT server connected")
            return
        }

        val characteristicId = args.characteristic

        if (characteristicId == null) {
            invoke.reject("No characteristic specified")
            return
        }

        val characteristic =
            characteristics[characteristicId]

        if (characteristic == null) {
            invoke.reject(
                "Characteristic $characteristicId not found"
            )

            return
        }

        synchronized(onWriteInvoke) {
            onWriteInvoke[characteristicId]?.reject(
                "Write was overwritten before finishing"
            )

            onWriteInvoke[characteristicId] = invoke
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(
                characteristic,
                args.data!!,
                if (args.withResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
            )

            if (result != BluetoothStatusCodes.SUCCESS) {
                synchronized(onWriteInvoke) {
                    onWriteInvoke.remove(characteristicId)
                }

                invoke.reject(
                    "Failed to start characteristic write: $result"
                )
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = args.data

            @Suppress("DEPRECATION")
            val started =
                gatt.writeCharacteristic(characteristic)

            if (!started) {
                synchronized(onWriteInvoke) {
                    onWriteInvoke.remove(characteristicId)
                }

                invoke.reject(
                    "Failed to start characteristic write"
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun read(invoke: Invoke) {
        val args =
            invoke.parseArgs(BleClientPlugin.ReadParams::class.java)

        val gatt = this.gatt

        if (gatt == null) {
            invoke.reject("No GATT server connected")
            return
        }

        val characteristicId = args.characteristic

        if (characteristicId == null) {
            invoke.reject("No characteristic specified")
            return
        }

        val characteristic =
            characteristics[characteristicId]

        if (characteristic == null) {
            invoke.reject(
                "Characteristic $characteristicId not found"
            )

            return
        }

        synchronized(onReadInvoke) {
            onReadInvoke[characteristicId]?.reject(
                "Read was overwritten before finishing"
            )

            onReadInvoke[characteristicId] = invoke
        }

        @Suppress("DEPRECATION")
        val started =
            gatt.readCharacteristic(characteristic)

        if (!started) {
            synchronized(onReadInvoke) {
                onReadInvoke.remove(characteristicId)
            }

            invoke.reject(
                "Failed to start characteristic read"
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun subscribe(
        invoke: Invoke,
        enabled: Boolean
    ) {
        val args =
            invoke.parseArgs(BleClientPlugin.ReadParams::class.java)

        val gatt = this.gatt

        if (gatt == null) {
            invoke.reject("No GATT server connected")
            return
        }

        val characteristicId = args.characteristic

        if (characteristicId == null) {
            invoke.reject("No characteristic specified")
            return
        }

        val characteristic =
            characteristics[characteristicId]

        if (characteristic == null) {
            invoke.reject(
                "Characteristic $characteristicId not found"
            )

            return
        }

        if (
            !gatt.setCharacteristicNotification(
                characteristic,
                enabled
            )
        ) {
            invoke.reject(
                "Failed to set characteristic notification status"
            )

            return
        }

        val descriptor =
            characteristic.getDescriptor(
                CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR
            )

        if (descriptor == null) {
            invoke.reject(
                "Characteristic $characteristicId has no CCCD"
            )

            return
        }

        val descriptorValue =
            if (!enabled) {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else if (
                characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            ) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else if (
                characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            ) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                invoke.reject(
                    "Characteristic $characteristicId does not " +
                        "support notifications or indications"
                )

                return
            }

        onDescriptorInvoke?.reject(
            "Descriptor write was overwritten before finishing"
        )

        onDescriptorInvoke = invoke

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result =
                gatt.writeDescriptor(
                    descriptor,
                    descriptorValue
                )

            if (result != BluetoothStatusCodes.SUCCESS) {
                onDescriptorInvoke = null

                invoke.reject(
                    "Failed to start descriptor write: $result"
                )
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = descriptorValue

            @Suppress("DEPRECATION")
            val started =
                gatt.writeDescriptor(descriptor)

            if (!started) {
                onDescriptorInvoke = null

                invoke.reject(
                    "Failed to start descriptor write"
                )
            }
        }
    }
}