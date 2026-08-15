package com.inertialtrack.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class TrackingService : Service(), SensorFusionEngine.TelemetryListener {

    companion object {
        const val ACTION_START_GPS = "com.inertialtrack.app.START_GPS"
        const val ACTION_FORCE_LOCK = "com.inertialtrack.app.FORCE_LOCK"
        const val ACTION_RESET_ORIGIN = "com.inertialtrack.app.RESET_ORIGIN"
        const val ACTION_STOP = "com.inertialtrack.app.STOP"

        const val ACTION_TELEMETRY_UPDATE = "com.inertialtrack.app.TELEMETRY_UPDATE"
        const val EXTRA_POSITION_DATA = "extra_position_data"

        private const val CHANNEL_ID = "tracking_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    private val binder = LocalBinder()
    private lateinit var sensorFusionEngine: SensorFusionEngine
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var latestLocation: Location? = null
    private var currentPositionData = PositionData()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            handleNewLocation(location)
        }
    }

    private val locationListener = LocationListener { location ->
        handleNewLocation(location)
    }

    override fun onCreate() {
        super.onCreate()
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorFusionEngine = SensorFusionEngine(sensorManager)
        sensorFusionEngine.setTelemetryListener(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InertialTracker::WakeLock")
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes fallback*/)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Acquiring initial location…"))

        when (intent?.action) {
            ACTION_START_GPS -> startGpsAcquisition()
            ACTION_FORCE_LOCK -> forceGpsLock()
            ACTION_RESET_ORIGIN -> sensorFusionEngine.resetOrigin()
            ACTION_STOP -> stopSelf()
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startGpsAcquisition() {
        currentPositionData = currentPositionData.copy(trackingState = TrackingState.SEARCHING_GPS)
        broadcastTelemetry(currentPositionData)
        updateNotification("Searching high-precision GPS lock…")

        // 1. Google Play Services FusedLocationProvider
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. LocationManager GPS fallback
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleNewLocation(location: Location) {
        latestLocation = location
        broadcastTelemetry(
            currentPositionData.copy(
                trackingState = TrackingState.SEARCHING_GPS,
                originLat = location.latitude,
                originLng = location.longitude,
                originAlt = location.altitude,
                originAccuracy = location.accuracy
            )
        )

        // If accuracy is high enough (<= 8 meters), ditch GPS immediately!
        if (location.accuracy <= 8.0f) {
            lockGpsAndSwitchToSensors(location)
        }
    }

    private fun forceGpsLock() {
        latestLocation?.let { location ->
            lockGpsAndSwitchToSensors(location)
        } ?: run {
            // Use last known location if available
            try {
                val lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (lastLoc != null) {
                    lockGpsAndSwitchToSensors(lastLoc)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun lockGpsAndSwitchToSensors(location: Location) {
        // DITCH GPS COMPLETELY! Unregister all GPS updates.
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Initialize Sensor Fusion Engine with GPS reference point
        sensorFusionEngine.setGpsOrigin(
            lat = location.latitude,
            lng = location.longitude,
            alt = location.altitude,
            accuracy = location.accuracy
        )

        // Switch tracking mode to autonomous background sensor fusion
        sensorFusionEngine.startTracking()
        updateNotification("GPS Ditched → 3D Sensor Tracking Active")
    }

    override fun onTelemetryUpdated(data: PositionData) {
        currentPositionData = data
        broadcastTelemetry(data)
        updateNotification(
            "3D Delta: X:%.1fm Y:%.1fm Z:%.1fm | Head:%.0f°".format(
                data.deltaX, data.deltaY, data.deltaZ, data.yaw
            )
        )
    }

    private fun broadcastTelemetry(data: PositionData) {
        val intent = Intent(ACTION_TELEMETRY_UPDATE).apply {
            putExtra(EXTRA_POSITION_DATA, data)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground Service for 3D Motion Tracking"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        sensorFusionEngine.stopTracking()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }
}
