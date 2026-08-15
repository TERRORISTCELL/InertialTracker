package com.inertialtrack.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.inertialtrack.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TrackingService.ACTION_TELEMETRY_UPDATE) {
                val positionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(TrackingService.EXTRA_POSITION_DATA, PositionData::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(TrackingService.EXTRA_POSITION_DATA)
                }
                positionData?.let { updateUi(it) }
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineLocationGranted) {
            startTrackingService(TrackingService.ACTION_START_GPS)
        } else {
            Toast.makeText(this, "Fine Location permission is required for initial GPS lock", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
    }

    private fun setupButtons() {
        binding.btnStartGps.setOnClickListener {
            checkAndStartTracking()
        }

        binding.btnForceLock.setOnClickListener {
            val intent = Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_FORCE_LOCK
            }
            ContextCompat.startForegroundService(this, intent)
        }

        binding.btnResetOrigin.setOnClickListener {
            val intent = Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_RESET_ORIGIN
            }
            ContextCompat.startForegroundService(this, intent)
        }

        binding.btnStop.setOnClickListener {
            val intent = Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_STOP
            }
            startService(intent)
        }
    }

    private fun checkAndStartTracking() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startTrackingService(TrackingService.ACTION_START_GPS)
        }
    }

    private fun startTrackingService(actionText: String) {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = actionText
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun updateUi(data: PositionData) {
        when (data.trackingState) {
            TrackingState.IDLE -> {
                binding.tvStatusText.text = getString(R.string.status_idle)
                binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.progressGps.visibility = View.GONE
            }
            TrackingState.SEARCHING_GPS -> {
                binding.tvStatusText.text = getString(R.string.status_searching_gps)
                binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_gps_searching))
                binding.progressGps.visibility = View.VISIBLE
            }
            TrackingState.GPS_LOCKED -> {
                binding.tvStatusText.text = getString(R.string.status_gps_locked)
                binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_gps_locked))
                binding.progressGps.visibility = View.GONE
            }
            TrackingState.SENSOR_TRACKING -> {
                binding.tvStatusText.text = getString(R.string.status_sensor_tracking)
                binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_sensor_active))
                binding.progressGps.visibility = View.GONE
            }
            TrackingState.STOPPED -> {
                binding.tvStatusText.text = getString(R.string.status_stopped)
                binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_stopped))
                binding.progressGps.visibility = View.GONE
            }
        }

        // Initial GPS Reference Card
        if (data.originLat != 0.0 || data.originLng != 0.0) {
            binding.tvGpsOriginCoords.text = "Lat: %.6f, Lng: %.6f".format(data.originLat, data.originLng)
            binding.tvGpsOriginDetails.text = "Alt: %.1f m | Acc: %.1f m | GPS: %s".format(
                data.originAlt,
                data.originAccuracy,
                if (data.isGpsDitched) "DITCHED (Sensors Active)" else "Acquiring..."
            )
        }

        // Live Telemetry Card
        binding.tvLiveCoords.text = "Est. Position: %.6f, %.6f (Alt: %.1fm)".format(
            data.currentLat, data.currentLng, data.currentAlt
        )

        binding.tvDisplacement.text = "3D Vector Δ (X, Y, Z): East: %.2fm | North: %.2fm | Vert: %.2fm".format(
            data.deltaX, data.deltaY, data.deltaZ
        )

        val cardinal = getCardinalDirection(data.yaw)
        binding.tvOrientation.text = "Orientation: Heading %.1f° (%s) | Pitch: %.1f° | Roll: %.1f°".format(
            data.yaw, cardinal, data.pitch, data.roll
        )

        binding.tvMotionStats.text = "Steps: %d | Distance: %.1f m | Speed: %.2f m/s".format(
            data.stepCount, data.totalDistance, data.currentSpeed
        )

        // Continuous Error Comparison Card
        if (data.hasGpsComparison) {
            binding.tvErrorMeters.text = "Error: %.2f meters".format(data.errorDistanceMeters)

            val errorColor = when {
                data.errorDistanceMeters < 5.0f -> ContextCompat.getColor(this, R.color.status_gps_locked)
                data.errorDistanceMeters < 15.0f -> ContextCompat.getColor(this, R.color.status_gps_searching)
                else -> ContextCompat.getColor(this, R.color.status_stopped)
            }
            binding.tvErrorMeters.setTextColor(errorColor)

            binding.tvErrorDetails.text = "Horizontal Error: %.2f m | Vertical Error: %.2f m | GPS Acc: ±%.1f m".format(
                data.errorDistanceMeters,
                data.errorVerticalMeters,
                data.latestGpsAccuracy
            )

            binding.tvGpsVsSensorCoords.text = "Hardware GPS: %.6f, %.6f (Alt: %.1fm)\nSensor Guess:  %.6f, %.6f (Alt: %.1fm)".format(
                data.latestGpsLat, data.latestGpsLng, data.latestGpsAlt,
                data.currentLat, data.currentLng, data.currentAlt
            )
        } else {
            binding.tvErrorMeters.text = "Error: Waiting for GPS sample..."
            binding.tvErrorMeters.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvErrorDetails.text = "Horizontal Error: -- m | Vertical Error: -- m | GPS Acc: ±-- m"
            binding.tvGpsVsSensorCoords.text = "GPS: Waiting for comparison frame\nSensor Guess: %.6f, %.6f".format(
                data.currentLat, data.currentLng
            )
        }
    }

    private fun getCardinalDirection(yaw: Float): String {
        return when (yaw) {
            in 22.5f..67.5f -> "NE"
            in 67.5f..112.5f -> "E"
            in 112.5f..157.5f -> "SE"
            in 157.5f..202.5f -> "S"
            in 202.5f..247.5f -> "SW"
            in 247.5f..292.5f -> "W"
            in 292.5f..337.5f -> "NW"
            else -> "N"
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            telemetryReceiver, IntentFilter(TrackingService.ACTION_TELEMETRY_UPDATE)
        )
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(telemetryReceiver)
    }
}
