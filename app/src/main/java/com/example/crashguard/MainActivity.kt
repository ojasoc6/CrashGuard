package com.example.crashguard

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.telephony.SmsManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.crashguard.ui.theme.CrashGuardTheme
import java.io.File
import java.io.FileWriter
import java.util.Locale

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var lastCrashTime: Long = 0
    private val crashCooldown = 2000 // 2 seconds

    private var lastGyroMagnitude = 0.0

    private var isCapturing by mutableStateOf(false)
    private var captureStartTime: Long = 0
    private val captureDuration = 2000 // 2 seconds

    private val accelBuffer = mutableListOf<Double>()
    private val gyroBuffer = mutableListOf<Double>()

    private var impactSeverity by mutableStateOf(0.0)
    private var recordLabel by mutableStateOf(0)
    private var emergencyContactNumber by mutableStateOf("YOUR_PHONE_NUMBER_HERE")

    private var isCountdownActive by mutableStateOf(false)
    private var countdownSecondsLeft by mutableStateOf(10)
    private var countdownSeverity by mutableStateOf(0.0)
    private var emergencyTimer: CountDownTimer? = null

    private var spikeTime: Long = 0
    private var spikeDetected = false

    private var suddenStopDetected = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, isGranted) ->
            Log.d("CrashGuard", "Permission $permission granted: $isGranted")
        }
    }

    fun startManualCapture() {
        if (isCapturing) return
        accelBuffer.clear()
        gyroBuffer.clear()
        suddenStopDetected = false
        captureStartTime = System.currentTimeMillis()
        isCapturing = true
        Log.d("CrashGuard", "🎙️ Manual 2s capture initiated")
    }

    private fun getLastKnownLocation(): Pair<Double, Double>? {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val hasFine = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w("CrashGuard", "Location permissions not granted.")
            return null
        }

        var bestLocation: Location? = null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null) {
                        if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                            bestLocation = loc
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e("CrashGuard", "SecurityException getting location from $provider", e)
            } catch (e: Exception) {
                Log.e("CrashGuard", "Error getting location from $provider", e)
            }
        }

        return bestLocation?.let { Pair(it.latitude, it.longitude) }
    }

    fun sendEmergencyAlert(severity: Double) {
        if (emergencyContactNumber.isBlank() || emergencyContactNumber == "YOUR_PHONE_NUMBER_HERE") {
            Log.w("CrashGuard", "Emergency alert not sent: contact number is not configured.")
            return
        }

        val hasSmsPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasSmsPermission) {
            Log.e("CrashGuard", "Cannot send SMS: SEND_SMS permission is not granted.")
            return
        }

        val location = getLastKnownLocation()
        val message = if (location != null) {
            "EMERGENCY ALERT: Crash detected with severity %.2f/10. Location: https://maps.google.com/?q=%f,%f"
                .format(Locale.US, severity, location.first, location.second)
        } else {
            "EMERGENCY ALERT: Crash detected with severity %.2f/10. Location unavailable."
                .format(Locale.US, severity)
        }

        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(emergencyContactNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(emergencyContactNumber, null, message, null, null)
            }
            Log.d("CrashGuard", "✅ Emergency SMS dispatched to $emergencyContactNumber: $message")
        } catch (e: Exception) {
            Log.e("CrashGuard", "❌ Failed to dispatch emergency SMS alert", e)
        }
    }

    fun startEmergencyCountdown(severity: Double) {
        countdownSeverity = severity
        countdownSecondsLeft = 10
        isCountdownActive = true

        emergencyTimer?.cancel()
        emergencyTimer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                countdownSecondsLeft = kotlin.math.ceil(millisUntilFinished / 1000.0).toInt().coerceAtLeast(1)
            }

            override fun onFinish() {
                countdownSecondsLeft = 0
                isCountdownActive = false
                Log.d("CrashGuard", "⏰ Emergency countdown finished. Dispatching emergency alert...")
                sendEmergencyAlert(countdownSeverity)
            }
        }.start()
        Log.d("CrashGuard", "🚨 Emergency countdown started for severity: $severity")
    }

    fun cancelEmergencyCountdown() {
        emergencyTimer?.cancel()
        emergencyTimer = null
        isCountdownActive = false
        Log.d("CrashGuard", "Emergency alert cancelled by user (False Alarm).")
    }

    override fun onDestroy() {
        super.onDestroy()
        emergencyTimer?.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        requestPermissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        enableEdgeToEdge()
        setContent {
            CrashGuardTheme {
                CrashScreen(
                    severity = impactSeverity,
                    currentLabel = recordLabel,
                    isCapturing = isCapturing,
                    isCountdownActive = isCountdownActive,
                    countdownSecondsLeft = countdownSecondsLeft,
                    countdownSeverity = countdownSeverity,
                    emergencyContact = emergencyContactNumber,
                    onEmergencyContactChange = { emergencyContactNumber = it },
                    onLabelChange = { recordLabel = it },
                    onRecordManual = { startManualCapture() },
                    onTestCountdown = { startEmergencyCountdown(if (impactSeverity > 0.0) impactSeverity else 8.5) },
                    onCancelCountdown = { cancelEmergencyCountdown() },
                    onTestSms = { sendEmergencyAlert(if (impactSeverity > 0.0) impactSeverity else 8.5) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    @Composable
    fun CrashScreen(
        severity: Double,
        currentLabel: Int,
        isCapturing: Boolean,
        isCountdownActive: Boolean,
        countdownSecondsLeft: Int,
        countdownSeverity: Double,
        emergencyContact: String,
        onEmergencyContactChange: (String) -> Unit,
        onLabelChange: (Int) -> Unit,
        onRecordManual: () -> Unit,
        onTestCountdown: () -> Unit,
        onCancelCountdown: () -> Unit,
        onTestSms: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val status = when {
            severity > 7 -> "Severe Crash"
            severity > 4 -> "Moderate Impact"
            severity > 1 -> "Minor Impact"
            else -> "No Crash Detected"
        }

        if (isCountdownActive) {
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFB71C1C))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "⚠️ SEVERE IMPACT DETECTED!",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            ),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Severity: ${"%.2f".format(countdownSeverity)} / 10",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD54F)
                            )
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.35f)),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Sending Emergency Alert in:",
                                    style = MaterialTheme.typography.bodyLarge.copy(color = Color.White)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "${countdownSecondsLeft}s",
                                    style = MaterialTheme.typography.displayLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = onCancelCountdown,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        ) {
                            Text(
                                text = "I'M SAFE (CANCEL ALERT)",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB71C1C)
                                )
                            )
                        }
                    }
                }
            }
        }

        Scaffold(modifier = modifier.fillMaxSize()) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = """
                    🚗 CrashGuard
                    
                    Impact Severity:
                    ${"%.2f".format(severity)} / 10
                    
                    Status:
                    $status
                    
                    Active Label: ${if (currentLabel == 1) "1 (Crash)" else "0 (Normal)"}
                    
                    State: ${if (isCapturing) "Recording... Please perform activity" else "Ready"}
                """.trimIndent()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onRecordManual,
                    enabled = !isCapturing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isCapturing) "Recording in progress..." else "Record 2s Sample Now")
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onLabelChange(0) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Set Label: Normal")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onLabelChange(1) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Set Label: Crash")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Emergency Dispatch Settings",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = emergencyContact,
                    onValueChange = onEmergencyContactChange,
                    label = { Text("Emergency Contact Phone") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onTestCountdown,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test 10s Countdown")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onTestSms,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test Emergency SMS")
                }
            }
        }
    }


    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val magnitude = kotlin.math.sqrt((x * x + y * y + z * z).toDouble())
            if (spikeDetected) {
                val currentTime = System.currentTimeMillis()

                // Check within 500ms after spike
                if (currentTime - spikeTime < 500) {

                    if (magnitude < 12) {
                        Log.d("CrashGuard", "🛑 Sudden Stop Detected!")
                        suddenStopDetected = true
                        spikeDetected = false
                    }
                } else {
                    spikeDetected = false
                }
            }

            if (isCapturing) {
                accelBuffer.add(magnitude)
            }
            if (magnitude > 20) {
                Log.d("CrashGuard", "High Acceleration: $magnitude")
            }

            if (magnitude > 40 && lastGyroMagnitude > 8) {

                spikeDetected = true
                spikeTime = System.currentTimeMillis()

                val currentTime = System.currentTimeMillis()

                if (currentTime - lastCrashTime > crashCooldown) {
                    Log.d("CrashGuard", "🚨 POSSIBLE CRASH DETECTED!")

                    lastCrashTime = currentTime

                    // Start capturing data
                    isCapturing = true
                    captureStartTime = currentTime

                    accelBuffer.clear()
                    gyroBuffer.clear()
                }
            }


        }

        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            val gx = event.values[0]
            val gy = event.values[1]
            val gz = event.values[2]

            val gyroMag = kotlin.math.sqrt((gx * gx + gy * gy + gz * gz).toDouble())
            lastGyroMagnitude = gyroMag
            if (isCapturing) {
                gyroBuffer.add(gyroMag)
            }
            if (gyroMag > 5) {
                Log.d("CrashGuard", "High Rotation: $gyroMag")
            }
        }
        if (isCapturing) {
            val currentTime = System.currentTimeMillis()

            if (currentTime - captureStartTime >= captureDuration) {
                isCapturing = false
                Log.d("CrashGuard", "📊 Data Capture Complete")
                Log.d("CrashGuard", "Accel Samples: ${accelBuffer.size}")
                Log.d("CrashGuard", "Gyro Samples: ${gyroBuffer.size}")

                val maxAccel = accelBuffer.maxOrNull() ?: 0.0
                val meanAccel = if (accelBuffer.isNotEmpty()) accelBuffer.average() else 0.0
                val maxGyro = gyroBuffer.maxOrNull() ?: 0.0
                val meanGyro = if (gyroBuffer.isNotEmpty()) gyroBuffer.average() else 0.0
                val suddenStop = suddenStopDetected

                val severity = calculateImpactSeverity()
                impactSeverity = severity
                Log.d("CrashGuard", "🔥 Impact Severity Index: $severity / 10")

                logTelemetryToCsv(
                    maxAccel = maxAccel,
                    meanAccel = meanAccel,
                    maxGyro = maxGyro,
                    meanGyro = meanGyro,
                    suddenStop = suddenStop,
                    severity = severity,
                    label = recordLabel
                )

                if (severity > 7.0) {
                    Log.d("CrashGuard", "🚨 Severe crash detected ($severity / 10)! Starting emergency countdown...")
                    startEmergencyCountdown(severity)
                }
            }
        }

    }

    fun logTelemetryToCsv(
        maxAccel: Double,
        meanAccel: Double,
        maxGyro: Double,
        meanGyro: Double,
        suddenStop: Boolean,
        severity: Double,
        label: Int
    ) {
        try {
            val file = File(filesDir, "crash_telemetry.csv")
            val isNew = !file.exists()
            val timestamp = System.currentTimeMillis()
            val writer = FileWriter(file, true)
            if (isNew) {
                writer.append("timestamp,max_accel,mean_accel,max_gyro,mean_gyro,sudden_stop,severity_index,label\n")
            }
            writer.append("$timestamp,$maxAccel,$meanAccel,$maxGyro,$meanGyro,$suddenStop,$severity,$label\n")
            writer.flush()
            writer.close()
            Log.d("CrashGuard", "Telemetry logged to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("CrashGuard", "Failed to write telemetry to CSV", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onResume() {
        super.onResume()
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }
    private fun calculateImpactSeverity(): Double {

        if (accelBuffer.isEmpty() || gyroBuffer.isEmpty()) return 0.0

        val maxAccel = accelBuffer.maxOrNull() ?: 0.0
        val maxGyro = gyroBuffer.maxOrNull() ?: 0.0

        val accelScore = (maxAccel / 120.0) * 5.0
        val gyroScore = (maxGyro / 30.0) * 5.0

        val totalScore = accelScore + gyroScore

        var finalScore = totalScore

        if (suddenStopDetected) {
            finalScore += 1.5   // bonus confidence
        }

        suddenStopDetected = false

        return finalScore.coerceIn(0.0, 10.0)

    }

}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CrashGuardTheme {
        Greeting("Android")
    }
}