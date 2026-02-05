package com.example.bleuploader

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

private const val REQUEST_PERMISSIONS = 1001
private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var isScanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        scanButton.setOnClickListener { ensurePermissionsAndStart() }
    }

    override fun onDestroy() {
        stopScanIfNeeded()
        gatt?.close()
        executor.shutdown()
        super.onDestroy()
    }

    private fun ensurePermissionsAndStart() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            updateStatus("Bluetooth está desligado")
            return
        }

        startScan()
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScan()
        } else {
            updateStatus("Permissões necessárias não concedidas")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanning) return
        val scanner = bluetoothLeScanner ?: run {
            updateStatus("BLE não disponível neste aparelho")
            return
        }

        updateStatus("Procurando dispositivos BLE...")
        isScanning = true
        scanner.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanIfNeeded() {
        if (!isScanning) return
        bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(result: ScanResult) {
        stopScanIfNeeded()
        updateStatus("Conectando a ${result.device.address}")
        gatt?.close()
        gatt = result.device.connectGatt(this, false, gattCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            connectToDevice(result)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    updateStatus("Falha na conexão BLE (status $status)")
                    gatt.close()
                    startScan()
                }
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    updateStatus("Conectado. Descobrindo serviços...")
                    gatt.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    updateStatus("Desconectado. Reiniciando varredura...")
                    gatt.close()
                    startScan()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateStatus("Falha ao descobrir serviços")
                return
            }

            var configured = false
            for (service in gatt.services) {
                for (characteristic in service.characteristics) {
                    val properties = characteristic.properties
                    val canNotify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                    val canIndicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                    val canRead = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0

                    if (canNotify || canIndicate) {
                        enableNotifications(gatt, characteristic, canIndicate)
                        configured = true
                        break
                    }

                    if (canRead) {
                        gatt.readCharacteristic(characteristic)
                        configured = true
                        break
                    }
                }
                if (configured) break
            }

            updateStatus(if (configured) "Aguardando dados BLE..." else "Nenhuma característica útil encontrada")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleBleData(value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleBleData(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        useIndication: Boolean
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) ?: return
        descriptor.value = if (useIndication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        gatt.writeDescriptor(descriptor)
    }

    private fun handleBleData(data: ByteArray?) {
        if (data.isNullOrEmpty()) return

        val payload = data.joinToString(separator = " ") { byte -> "%02X".format(byte) }
        updateStatus("Enviando: $payload")
        sendToServer(payload)
    }

    private fun sendToServer(payload: String) {
        val urlString = getString(R.string.upload_url)
        executor.execute {
            try {
                val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    doOutput = true
                }

                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                }
                val responseCode = connection.responseCode
                updateStatus("Upload concluído (HTTP $responseCode)")
                connection.disconnect()
            } catch (ex: Exception) {
                updateStatus("Falha no upload: ${ex.message}")
            }
        }
    }

    private fun updateStatus(message: String) {
        mainHandler.post { statusText.text = message }
    }
}
