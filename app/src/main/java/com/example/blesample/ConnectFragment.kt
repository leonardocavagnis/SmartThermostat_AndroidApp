package com.example.blesample

import android.bluetooth.*
import android.bluetooth.BluetoothDevice.*
import android.bluetooth.BluetoothGattCharacteristic.*
import android.bluetooth.BluetoothGattDescriptor.*
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kotlinx.android.synthetic.main.fragment_connect.*
import org.eclipse.paho.client.mqttv3.*
import java.util.*

private const val TAG                       = "ConnectFragment"
private const val BLE_CMD_MAX_TRIES: Int    = 10

/**
 * Connect fragment
 */
class ConnectFragment : Fragment() {
    // BLE Connection management
    private var bleHandler: Handler? = null
    private var bleAdapter: BluetoothAdapter? = null
    private var bleDeviceAddress: String? = null
    private var bleDeviceReady: Boolean = false
    private lateinit var bleDevice: BluetoothDevice
    private lateinit var bleGatt: BluetoothGatt
    private lateinit var bleDiscoveredServices: List<BluetoothGattService>
    private lateinit var bleNotifyingCharacteristicsList: ArrayList<UUID>

    // BLE Command queue
    private var bleCommandQueue: Queue<Runnable>? = null
    private var bleCommandQueueBusy = false
    private var bleCommandNrTries: Int = 0
    private var bleCommandIsRetrying: Boolean = false

    // BLE Characteristics value
    private var essTemperature: Float? = null
    private var prevEssTemperature: Float? = null

    // MQTT connection management
    private lateinit var mqttClient : MQTTClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity?.onBackPressedDispatcher?.addCallback(this, object : OnBackPressedCallback(true) {
            @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
            override fun handleOnBackPressed() {
                // MQTT Disconnection
                mqttDisconnection()

                // BLE Disconnection
                bleGatt.disconnect()
            }
        })
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_connect, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Init vars
        bleDeviceReady = false
        bleNotifyingCharacteristicsList = ArrayList<UUID>()
        bleCommandQueueBusy = false
        bleCommandNrTries = 0
        bleCommandIsRetrying = false
        bleCommandQueue = LinkedList<Runnable>()

        // Initializes handler
        bleHandler = Handler()

        // Initializes a Bluetooth adapter
        val bluetoothManager = activity?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bleAdapter = bluetoothManager.adapter

        bleDeviceAddress = arguments?.getString("deviceAddressFromScan", null)!!

        if (bleDeviceAddress != null) {
            bleDevice = bleAdapter!!.getRemoteDevice(bleDeviceAddress)

            if (bleDevice.name != null) {
                textview_device_name.text = bleDevice.name
            } else {
                textview_device_name.text = "N/A"
            }
            textview_device_name.append("\r\n"+bleDevice.address)

            // Connect to BLE device
            bleGatt = bleDevice.connectGatt(context, false, bluetoothGattCallback, TRANSPORT_LE)
        } else {
            Log.e(TAG, "Device address is invalid, back to scan")
            findNavController().navigate(R.id.action_ConnectFragment_to_ScanFragment)
        }

        // Open MQTT Broker communication
        mqttClient = MQTTClient(context, MQTT_SERVER_URI, MQTT_CLIENT_ID)

        // Disconnect button
        view.findViewById<Button>(R.id.button_disconnect).setOnClickListener {
            Log.i(TAG, "Press disconnect button")
            // MQTT Disconnection
            mqttDisconnection()

            // BLE Disconnection
            bleGatt.disconnect()
        }

        // Read Temperature characteristic button
        view.findViewById<Button>(R.id.button_read_temp).setOnClickListener {
            Log.i(TAG, "Press Read Characteristic button")

            if (bleDeviceReady) {
                for (service in bleDiscoveredServices) {
                    if (service.uuid.compareTo(ENVIRONMENTAL_SENSING_SERVICE_UUID) == 0) {
                        Log.i(TAG, "Environmental Sensing service found")
                        for (characteristic in service.characteristics) {
                            if (characteristic.uuid.compareTo(ESS_TEMPERATURE_CHARACTERISTIC_UUID) == 0) {
                                Log.i(TAG, "Temperature characteristic found")
                                readCharacteristic(characteristic)
                            }
                        }
                    }
                }
            }
        }

        // Notify Temperature characteristic toggle button
        view.findViewById<Button>(R.id.togglebutton_notif_temp).setOnClickListener {
            Log.i(TAG, "Press Notify characteristic toggle button")

            val toggleButtonState: Boolean = togglebutton_notif_temp.isChecked

            if (bleDeviceReady) {
                for (service in bleDiscoveredServices) {
                    if (service.uuid.compareTo(ENVIRONMENTAL_SENSING_SERVICE_UUID) == 0) {
                        Log.i(TAG, "Environmental Sensing service found")
                        for (characteristic in service.characteristics) {
                            if (characteristic.uuid.compareTo(ESS_TEMPERATURE_CHARACTERISTIC_UUID) == 0) {
                                Log.i(TAG, "Temperature characteristic found")

                                val enableNotification: Boolean = if (toggleButtonState) {
                                    Log.i(TAG, "Temperature Notify ENABLE")
                                    true
                                } else {
                                    Log.i(TAG, "Temperature Notify DISABLE")
                                    false
                                }

                                setNotify(characteristic, enableNotification)
                            }
                        }
                    }
                }
            }
        }

        // Write LED ON characteristic
        view.findViewById<Button>(R.id.button_led_on).setOnClickListener {
            Log.i(TAG, "Press LED ON button")

            for (service in bleDiscoveredServices) {
                if (service.uuid.compareTo(LED_SERVICE_UUID) == 0) {
                    Log.i(TAG, "LED service found")
                    for (characteristic in service.characteristics) {
                        if (characteristic.uuid.compareTo(LS_LED_STATUS_CHARACTERISTIC_UUID) == 0) {
                            Log.i(TAG, "Led status characteristic found")

                            var ledStatus:Byte = 1

                            writeCharacteristic(characteristic, WRITE_TYPE_DEFAULT, byteArrayOf(ledStatus))
                        }
                    }
                }
            }

        }

        // Write LED OFF characteristic
        view.findViewById<Button>(R.id.button_led_off).setOnClickListener {
            Log.i(TAG, "Press LED OFF button")

            for (service in bleDiscoveredServices) {
                if (service.uuid.compareTo(LED_SERVICE_UUID) == 0) {
                    Log.i(TAG, "LED service found")
                    for (characteristic in service.characteristics) {
                        if (characteristic.uuid.compareTo(LS_LED_STATUS_CHARACTERISTIC_UUID) == 0) {
                            Log.i(TAG, "Led status characteristic found")

                            var ledStatus:Byte = 0

                            writeCharacteristic(characteristic, WRITE_TYPE_DEFAULT, byteArrayOf(ledStatus))
                        }
                    }
                }
            }

        }

        // MQTT Connect button
        view.findViewById<Button>(R.id.button_mqtt_connect).setOnClickListener {
            Log.i(TAG, "Press MQTT connect button")

            // Connect and login to MQTT Broker
            mqttClient.connect(
                MQTT_USERNAME,
                MQTT_PWD,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d(this.javaClass.name, "MQTT Connection success")

                        Toast.makeText(context, "MQTT Connection success", Toast.LENGTH_SHORT).show()
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.d(TAG, "Connection failure: ${exception.toString()}")

                        Toast.makeText(context, "MQTT Connection fails: ${exception.toString()}", Toast.LENGTH_SHORT).show()
                    }
                },
                object : MqttCallback {
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val msg = "Receive message: ${message.toString()} from topic: $topic"
                        Log.d(TAG, msg)

                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.d(TAG, "Connection lost ${cause.toString()}")
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        Log.d(TAG, "Delivery complete")
                    }
                })
        }

        // MQTT Disconnect button
        view.findViewById<Button>(R.id.button_mqtt_disconnect).setOnClickListener {
            Log.i(TAG, "Press MQTT disconnect button")

            // MQTT Disconnection
            mqttDisconnection()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause")
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "onStop")
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    override fun onDestroyView() {
        super.onDestroyView()

        Log.i(TAG, "onDestroyView")
        bleGatt.disconnect()
    }

    private val bluetoothGattCallback = @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange")
            if(status == BluetoothGatt.GATT_SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected to device")

                        // Take action depending on the bond state
                        when(val bondState = bleDevice.bondState) {
                            BOND_NONE, BOND_BONDED -> {
                                var discoverServicesRunnable: Runnable?
                                var delayWhenBonded = 0

                                if (bondState == BOND_BONDED) {
                                    Log.i(TAG, "Bond state = BONDED")
                                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
                                        delayWhenBonded = 1000
                                    }
                                } else if (bondState == BOND_NONE) {
                                    Log.i(TAG, "Bond state = NONE")
                                }

                                discoverServicesRunnable = Runnable {
                                    Log.i(TAG, "discovering services with delay of $delayWhenBonded ms")
                                    val result = gatt.discoverServices()
                                    if (!result) {
                                        Log.e(TAG, "discoverServices failed to start")
                                    }
                                    discoverServicesRunnable = null
                                }
                                bleHandler!!.postDelayed(discoverServicesRunnable, delayWhenBonded.toLong())
                            }
                            BOND_BONDING -> {
                                Log.i(TAG, "Bond state = BONDING")
                            }
                            else -> {
                                Log.e(TAG, "Bond state = unknown")
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from device")
                        gatt.close()
                        findNavController().navigate(R.id.action_ConnectFragment_to_ScanFragment)
                    }
                    else -> {
                        Log.i(TAG, "Unknown state")
                    }
                }
            } else {
                Log.i(TAG, "GATT FAILED: $status")
                gatt.close()
                findNavController().navigate(R.id.action_ConnectFragment_to_ScanFragment)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered")
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "onServicesDiscovered success")

                    bleDeviceReady = true

                    bleDiscoveredServices = gatt.services
                    Log.i(TAG, "Discovered services size: " + bleDiscoveredServices.size)

                    for (service in bleDiscoveredServices) {
                        displayGattService(service)
                    }
                }
                else -> {
                    Log.w(TAG, "onServicesDiscovered received error: $status")
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            Log.i(TAG, "onCharacteristicRead - ${characteristic?.uuid}")
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    if (characteristic != null) {
                        when (characteristic.uuid) {
                            ESS_TEMPERATURE_CHARACTERISTIC_UUID -> {
                                prevEssTemperature = essTemperature
                                essTemperature = (characteristic.getIntValue(FORMAT_UINT16, 0) / 100).toFloat();
                                Log.i(TAG, "Temperature: $essTemperature")
                            }
                            else -> {
                                Log.e(TAG, "Error")
                            }
                        }
                    }
                    updateUIValues()

                    mqttPublish(essTemperature)

                    Log.i(TAG, "Success")
                }
                else -> {
                    Log.e(TAG, "Fail")
                }
            }

            // We done, complete the command
            completedBluetoothCommand()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.i(TAG, "onCharacteristicWrite - ${characteristic?.uuid}")
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    if (characteristic != null) {
                        when (characteristic.uuid) {
                            else -> {
                                Log.i(TAG, "Unknown Characteristic")
                            }
                        }
                    }
                    Log.i(TAG, "Success")
                }
                else -> {
                    Log.e(TAG, "Fail")
                }
            }

            // We done, complete the command
            completedBluetoothCommand()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            Log.i(TAG, "onDescriptorWrite - ${descriptor?.characteristic?.uuid}")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write descriptor failed value")
            }

            var parentCharacteristic = descriptor?.characteristic

            if (descriptor != null) {
                if(descriptor.uuid == UUID.fromString(CCC_DESCRIPTOR_UUID)) {
                    Log.i(TAG, "Write descriptor success")
                    // Check if we were turning notify on or off
                    var value = descriptor.value

                    if (value != null) {
                        if (value[0] != 0.toByte()) {
                            if(parentCharacteristic != null) bleNotifyingCharacteristicsList.add(parentCharacteristic.uuid)
                        } else {
                            if(parentCharacteristic != null) bleNotifyingCharacteristicsList.remove(parentCharacteristic.uuid);
                        }
                    }
                } else {
                    Log.e(TAG, "Write descriptor null")
                }
            }

            // We done, complete the command
            completedBluetoothCommand()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.i(TAG, "onCharacteristicChanged")

            if (characteristic != null) {
                when (characteristic.uuid) {
                    ESS_TEMPERATURE_CHARACTERISTIC_UUID -> {
                        prevEssTemperature  = essTemperature
                        essTemperature      = (characteristic.getIntValue(FORMAT_UINT16, 0) / 100).toFloat();
                        Log.i(TAG, "Temperature: $essTemperature")
                    }
                    else -> {
                        Log.e(TAG, "Error")
                    }
                }
                updateUIValues()

                if (essTemperature != prevEssTemperature) mqttPublish(essTemperature)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private fun displayGattService(service: BluetoothGattService) {
        if (service == null) return

        Log.i(TAG, "Service: " + service.uuid.toString())

        for (characteristic in service.characteristics) {
            Log.i(TAG, "Characteristic: " + characteristic.uuid.toString())
        }
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        Log.i(TAG, "readCharacteristic")
        // Check if adapter and gatt handler are properly configured
        if (bleAdapter == null || bleGatt == null) {
            return false
        }

        // Check if characteristic is valid
        if (characteristic == null) {
            return false
        }

        // Check if this characteristic actually has READ property
        if (characteristic.properties and PROPERTY_READ == 0) {
            Log.e(TAG, "Characteristic cannot be read")
            return false
        }

        // Enqueue the read command now that all checks have been passed
        val result: Boolean? = bleCommandQueue?.add(Runnable {
            if (!bleGatt.readCharacteristic(characteristic)) {
                Log.e(TAG, "readCharacteristic failed for characteristic")
                completedBluetoothCommand()
            } else {
                Log.d(TAG, "Reading characteristic")
                bleCommandNrTries++
            }
        })

        if (result!!) {
            nextBluetoothCommand()
        } else {
            Log.e(TAG, "Could not enqueue read characteristic command")
        }
        return result;
    }

    private fun nextBluetoothCommand() {
        // If there is still a command being executed then bail out
        if (bleCommandQueueBusy) {
            return
        }

        // Check if we still have a valid gatt object
        if (bleGatt == null) {
            Log.e(TAG,"GATT is 'null' for peripheral, clearing command queue")
            bleCommandQueue!!.clear()
            bleCommandQueueBusy = false
            return
        }

        // Execute the next command in the queue
        if (bleCommandQueue!!.size > 0) {
            val bluetoothCommand = bleCommandQueue!!.peek()
            bleCommandQueueBusy = true
            bleCommandNrTries = 0
            bleHandler?.post(Runnable {
                try {
                    bluetoothCommand.run()
                } catch (ex: Exception) {
                    Log.e(TAG, "Command exception for device")
                }
            })
        }
    }

    private fun completedBluetoothCommand() {
        bleCommandQueueBusy = false
        bleCommandIsRetrying = false
        bleCommandQueue!!.poll()
        nextBluetoothCommand()
    }

    private fun retryBluetoothCommand() {
        bleCommandQueueBusy = false
        val currentCommand = bleCommandQueue!!.peek()
        if (currentCommand != null) {
            if (bleCommandNrTries >= BLE_CMD_MAX_TRIES) {
                // Max retries reached, give up on this one and proceed
                Log.v(TAG, "Max number of tries reached")
                bleCommandQueue!!.poll()
            } else {
                bleCommandIsRetrying = true
            }
        }
        nextBluetoothCommand()
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic?, writeType: Int, writeData: ByteArray): Boolean {
        // Check if this characteristic actually supports this writeType
        val writeProperty: Int = when (writeType) {
            WRITE_TYPE_DEFAULT -> PROPERTY_WRITE
            WRITE_TYPE_NO_RESPONSE -> PROPERTY_WRITE_NO_RESPONSE
            WRITE_TYPE_SIGNED -> PROPERTY_SIGNED_WRITE
            else -> 0
        }
        if (characteristic!!.properties and writeProperty == 0) {
            Log.e(TAG,"Characteristic does not support write")
            return false
        }

        characteristic.value = writeData
        characteristic.writeType = writeType
        // Enqueue the write command now that all checks have been passed
        val result: Boolean? = bleCommandQueue?.add(Runnable {
            if (!bleGatt.writeCharacteristic(characteristic)) {
                Log.e(TAG, "writeCharacteristic failed for characteristic")
                completedBluetoothCommand()
            } else {
                Log.d(TAG, "Writing characteristic")
                bleCommandNrTries++
            }
        })

        if (result!!) {
            nextBluetoothCommand()
        } else {
            Log.e(TAG, "Could not enqueue write characteristic command")
        }
        return result;
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private fun setNotify(characteristic: BluetoothGattCharacteristic, enableNotification: Boolean): Boolean {
        // Check if characteristic is valid
        if (characteristic == null) {
            Log.e(TAG, "Characteristic is 'null', ignoring setNotify request")
            return false
        }

        // Get the CCC Descriptor for the characteristic
        var descriptor = characteristic.getDescriptor(UUID.fromString(CCC_DESCRIPTOR_UUID));
        if(descriptor == null) {
            Log.e(TAG, "Could not get CCC descriptor for characteristic")
            return false
        }

        // Check if characteristic has NOTIFY or INDICATE properties and set the correct byte value to be written
        val properties = characteristic.properties
        val value = when {
            (properties and PROPERTY_NOTIFY > 0) -> ENABLE_NOTIFICATION_VALUE
            (properties and PROPERTY_INDICATE > 0) -> ENABLE_INDICATION_VALUE
            else -> {
                Log.e(TAG,"Characteristic does not have notify or indicate property")
                return false
            }
        }

        // Enqueue the read command now that all checks have been passed
        val result: Boolean? = bleCommandQueue?.add(Runnable {
            if (!bleGatt.setCharacteristicNotification(descriptor.characteristic, enableNotification)) {
                Log.e(TAG, "setCharacteristicNotification failed for descriptor")
            }

            // Then write to descriptor
            descriptor.value = if (enableNotification) value else DISABLE_NOTIFICATION_VALUE
            var result: Boolean = bleGatt.writeDescriptor(descriptor)
            if (!result) {
                Log.e(TAG, "writeDescriptor failed for descriptor")
                completedBluetoothCommand()
            } else {
                bleCommandNrTries++
            }
        })

        if (result!!) {
            nextBluetoothCommand()
        } else {
            Log.e(TAG, "Could not enqueue write command")
        }

        return result;
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    fun isNotifying(characteristic: BluetoothGattCharacteristic): Boolean {
        return bleNotifyingCharacteristicsList.contains(characteristic.uuid)
    }

    private fun updateUIValues(){
        Log.i(TAG, "Update UI - Values")

        activity?.runOnUiThread(Runnable {
            textview_char_value.text = essTemperature.toString() + " °C"
        })
    }

    private fun mqttDisconnection() {
        if (mqttClient.isConnected()) {
            // Disconnect from MQTT Broker
            mqttClient.disconnect(object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "MQTT - Disconnected")

                    Toast.makeText(context, "MQTT Disconnection success", Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(TAG, "MQTT - Failed to disconnect")
                }
            })
        } else {
            Log.d(TAG, "MQTT - Impossible to disconnect, no server connected")
        }
    }

    private fun mqttPublish(temperature: Float?) {
        if (mqttClient.isConnected()) {
            mqttClient.publish(
                MQTT_TEMPERATURE_TOPIC,
                temperature.toString(),
                1,
                false,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d(TAG, "MQTT - Publish message: $MQTT_TEMPERATURE_TOPIC to topic: $temperature")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.d(TAG, "MQTT - Failed to publish message to topic")
                    }
                })
        } else {
            Log.d(TAG, "MQTT - Impossible to publish, no server connected")
        }
    }
}
