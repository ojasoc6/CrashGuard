package com.example.crashguard

import android.hardware.SensorEventListener
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.crashguard.ui.theme.CrashGuardTheme
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue



class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var lastCrashTime: Long = 0
    private val crashCooldown = 2000 // 2 seconds

    private var lastGyroMagnitude = 0.0

    private var isCapturing = false
    private var captureStartTime: Long = 0
    private val captureDuration = 2000 // 2 seconds

    private val accelBuffer = mutableListOf<Double>()
    private val gyroBuffer = mutableListOf<Double>()

    private var impactSeverity by mutableStateOf(0.0)

    private var spikeTime: Long = 0
    private var spikeDetected = false

    private var suddenStopDetected = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        enableEdgeToEdge()
        setContent {
            CrashGuardTheme {
                CrashScreen(
                    severity = impactSeverity,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

    }
    @Composable
    fun CrashScreen(severity: Double, modifier: Modifier = Modifier) {

        val status = when {
            severity > 7 -> "Severe Crash"
            severity > 4 -> "Moderate Impact"
            severity > 1 -> "Minor Impact"
            else -> "No Crash Detected"
        }

        Scaffold(modifier = modifier.fillMaxSize()) { padding ->
            Text(
                text = """
                🚗 CrashGuard
                
                Impact Severity:
                ${"%.2f".format(severity)} / 10
                
                Status:
                $status
            """.trimIndent(),
                modifier = Modifier.padding(padding)
            )
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
                val severity = calculateImpactSeverity()
                impactSeverity = severity
                Log.d("CrashGuard", "🔥 Impact Severity Index: $severity / 10")

            }
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