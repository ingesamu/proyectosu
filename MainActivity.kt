package com.surendramaran.yolov8tflite

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

const val REQUEST_ENABLE_BT = 1

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null

    private lateinit var mAddressDevices: ArrayAdapter<String>
    private lateinit var mNameDevices: ArrayAdapter<String>

    private var isConnected = false
    private val commandQueue = LinkedList<String>()

    private val BLUETOOTH_LE_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val BLUETOOTH_LE_CC254X_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val BLUETOOTH_LE_CC254X_CHAR_RW = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val gattOperationSemaphore = Semaphore(1)

    private var lastCommand: String? = null // Variable para guardar el último comando enviado

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Bluetooth setup
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        mAddressDevices = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        mNameDevices = ArrayAdapter(this, android.R.layout.simple_list_item_1)

        val idBtnDispBT = findViewById<Button>(R.id.button)
        val idBtnConect = findViewById<Button>(R.id.button2)
        val idSpinDisp = findViewById<Spinner>(R.id.spinner)

        val someActivityResultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == REQUEST_ENABLE_BT) {
                Log.i(TAG, "ACTIVIDAD REGISTRADA")
            }
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            someActivityResultLauncher.launch(enableBtIntent)
        }

        idBtnDispBT.setOnClickListener {
            if (bluetoothAdapter.isEnabled) {
                val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices
                mAddressDevices.clear()
                mNameDevices.clear()

                pairedDevices.forEach { device ->
                    val deviceName = device.name
                    val deviceHardwareAddress = device.address
                    mAddressDevices.add(deviceHardwareAddress)
                    mNameDevices.add(deviceName)
                }

                idSpinDisp.adapter = mNameDevices
            } else {
                val noDevices = "Ningún dispositivo pudo ser emparejado"
                mAddressDevices.add(noDevices)
                mNameDevices.add(noDevices)
                Toast.makeText(this, "Primero active el Bluetooth", Toast.LENGTH_LONG).show()
            }
        }

        idBtnConect.setOnClickListener {
            val IntValSpin = idSpinDisp.selectedItemPosition
            val address = mAddressDevices.getItem(IntValSpin)
            if (address != null) {
                connectToDevice(address)
            } else {
                Toast.makeText(this, "Seleccione un dispositivo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(address: String) {
        val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(address)
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Conectado al dispositivo", Toast.LENGTH_SHORT).show()
                }
                isConnected = true
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Desconectado del dispositivo", Toast.LENGTH_SHORT).show()
                }
                isConnected = false
                commandQueue.clear()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(BLUETOOTH_LE_CC254X_SERVICE)
                writeCharacteristic = service?.getCharacteristic(BLUETOOTH_LE_CC254X_CHAR_RW)

                writeCharacteristic?.let { characteristic ->
                    val cccdUuid = UUID.fromString(BLUETOOTH_LE_CCCD.toString())
                    val descriptor = characteristic.getDescriptor(cccdUuid)
                    gatt.setCharacteristicNotification(characteristic, true)
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Característica de escritura encontrada", Toast.LENGTH_SHORT).show()
                    }
                    // Enviar un mensaje de prueba después de un breve retraso
                    Handler(Looper.getMainLooper()).postDelayed({
                        queueBluetoothCommand("T")  // 'T' for Test
                    }, 1000) // 500ms delay
                } ?: run {
                    Log.e(TAG, "No se encontró una característica de escritura adecuada")
                }
            } else {
                Log.e(TAG, "Error al descubrir servicios: $status")
            }
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Característica escrita con éxito")
                commandQueue.poll() // Remove the sent command from the queue
                gattOperationSemaphore.release() // Release the semaphore
                sendNextCommand() // Send the next command if available
            } else {
                Log.e(TAG, "Error al escribir característica: $status")
                gattOperationSemaphore.release() // Release the semaphore even on failure
                // Retry sending the command after a delay
                Handler(Looper.getMainLooper()).postDelayed({
                    sendNextCommand()
                }, 1000) // 200ms delay before retry
            }
        }


        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val receivedValue = characteristic.value
            val receivedString = String(receivedValue)
            Log.d(TAG, "Received from Arduino: $receivedString")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation
        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        binding.overlay.clear()
        detector.clear()
        cameraExecutor.shutdown()
        bluetoothGatt?.close()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.apply {
                clear()
                invalidate()
            }
            binding.inferenceTime.text = "N/A"
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }

            boundingBoxes.forEach { box ->
                val objectName = box.clsName
                val command = when (objectName) {
                    "BIODEGRADABLE" -> "B"
                    "METAL" -> "M"
                    "PAPER" -> "P"
                    "PLASTIC" -> "L"
                    else -> null
                }

                // Solo enviamos el comando si es diferente del último comando enviado
                if (command != null && command != lastCommand) {
                    queueBluetoothCommand(command)
                    lastCommand = command // Actualizar el último comando enviado
                }
            }
        }
    }


    private fun queueBluetoothCommand(command: String) {
        commandQueue.offer(command)
        if (commandQueue.size == 1) {
            sendNextCommand()
        }
    }

    private fun sendNextCommand() {
        if (commandQueue.isNotEmpty() && gattOperationSemaphore.tryAcquire()) {
            val nextCommand = commandQueue.peek()
            Handler(Looper.getMainLooper()).postDelayed({
                sendBluetoothCommand(nextCommand)
            }, 1000) // 100ms delay
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendBluetoothCommand(command: String) {
        writeCharacteristic?.let { characteristic ->
            val utf8Bytes = "$command\r\n".toByteArray(Charsets.UTF_8)
            characteristic.value = utf8Bytes
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (!bluetoothGatt?.writeCharacteristic(characteristic)!!) {
                Log.e(TAG, "Failed to write characteristic")
                gattOperationSemaphore.release() // Release the semaphore on failure
                commandQueue.poll() // Remove the failed command
                sendNextCommand() // Try the next command
            } else {
                Log.d(TAG, "Sending command: $command")
            }
        } ?: run {
            Log.e(TAG, "Write characteristic not available")
            gattOperationSemaphore.release() // Release the semaphore
            commandQueue.poll() // Remove the failed command
            sendNextCommand() // Try the next command
        }
    }

}