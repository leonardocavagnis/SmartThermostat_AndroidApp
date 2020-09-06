package com.example.blesample

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kotlinx.android.synthetic.main.fragment_scan.*
import java.util.*
import kotlin.collections.ArrayList

private const val TAG               = "ScanFragment"
private const val BLE_SCAN_PERIOD: Long = 10000

/**
 * Scan fragment
 */
class ScanFragment : Fragment() {
    private var bleAdapter: BluetoothAdapter? = null
    private var bleHandler: Handler? = null
    private var bleDevice: ArrayList<BluetoothDevice>? = null
    private var bleDeviceName: ArrayList<String>? = null
    private var bleScanDeviceList: ArrayAdapter<String>? = null
    private var isScanning: Boolean = false
    private val bleScanServiceUUIDs: Array<UUID> = arrayOf(ENVIRONMENTAL_SENSING_SERVICE_UUID)

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_scan, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.button_scan).setOnClickListener {
            if(!isScanning)
                scanLeDevice(true)
        }

        view.findViewById<ListView>(R.id.list_scan_result).setOnItemClickListener { _: AdapterView<*>, _: View, i: Int, _: Long ->
            val selectedDeviceAddress = bleDevice?.get(i)?.address
            val selectedDevice = bleAdapter!!.getRemoteDevice(selectedDeviceAddress)

            if(selectedDevice != null) {
                Log.i(TAG, "Selected device (Address: $selectedDeviceAddress)")

                // Stop scanning
                scanLeDevice(false)

                // Start "Connect fragment"
                val bundle = bundleOf("deviceAddressFromScan" to selectedDeviceAddress)
                findNavController().navigate(R.id.action_ScanFragment_to_ConnectFragment, bundle)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    override fun onResume() {
        super.onResume()
        // BLE Device list
        bleDevice = ArrayList()
        bleDeviceName = ArrayList()

        bleScanDeviceList = ArrayAdapter(activity, android.R.layout.simple_list_item_1, bleDeviceName)

        list_scan_result.adapter = bleScanDeviceList

        // Initializes handler
        bleHandler = Handler()

        // Initializes a Bluetooth adapter
        val bluetoothManager = activity?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bleAdapter = bluetoothManager.adapter
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onPause() {
        super.onPause()
        scanLeDevice(false)
        bleDevice!!.clear()
        bleScanDeviceList!!.clear()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun scanLeDevice(enable: Boolean) {
        val bleScanner = bleAdapter?.bluetoothLeScanner

        // Scan settings
        val bleScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0)
            .build()

        // Scan filters
        var bleScanFilter: MutableList<ScanFilter?>? = null
        if (bleScanServiceUUIDs != null) {
            bleScanFilter = ArrayList()
            for (serviceUUID in bleScanServiceUUIDs) {
                val filter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(serviceUUID))
                    .build()
                bleScanFilter.add(filter)
            }
        }

        when (enable) {
            true -> {
                // Stops scanning after a pre-defined scan period.
                bleHandler!!.postDelayed({
                    isScanning = false
                    bleScanner?.stopScan(mScanCallback)
                    Log.i(TAG, "stop scan - SCAN_PERIOD")
                }, BLE_SCAN_PERIOD)

                isScanning = true
                bleDevice?.clear()
                bleScanDeviceList?.clear()
                bleScanner?.startScan(bleScanFilter, bleScanSettings, mScanCallback)
                Log.i(TAG, "start scan")
            }
            else -> {
                isScanning = false
                bleScanner?.stopScan(mScanCallback)
                Log.i(TAG, "stop scan")
            }
        }
    }

    private val mScanCallback: ScanCallback = @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.i(TAG,"onScanResult - Result: $result")

            if(!bleDevice!!.contains(result.device)) { // only add new devices
                bleDevice!!.add(result.device)
                val deviceName = result.device.name
                val macAddress = result.device.address
                if (deviceName != null) {
                    bleDeviceName!!.add("$deviceName\r\n$macAddress")
                } else {
                    bleDeviceName!!.add("N/A\r\n$macAddress")
                }

                bleScanDeviceList?.notifyDataSetChanged() // Update the list on the screen
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            Log.i(TAG,"onBatchScanResults")
            for (sr in results) {
                Log.i(TAG,"ScanResults - Results: $sr")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG,"onScanFailed - Error Code: $errorCode")
        }
    }
}
