package com.inertialtrack.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class SensorFusionEngine(private val sensorManager: SensorManager) : SensorEventListener {

    interface TelemetryListener {
        fun onTelemetryUpdated(data: PositionData)
    }

    private var listener: TelemetryListener? = null

    // State Variables
    private var currentData = PositionData()
    private var isTracking = false

    // Sensor Cache
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationVectorReading = FloatArray(5)

    private var hasRotationVector = false
    private var hasAccel = false
    private var hasMag = false

    // Reference Barometric Pressure at GPS lock
    private var referencePressure: Float? = null

    // Filter & Velocity Tracking
    private var lastTimestamp: Long = 0L
    private val velocityEarth = FloatArray(3) // [Vx, Vy, Vz]
    private val filteredAccelEarth = FloatArray(3)

    // Default step size in meters
    private val defaultStepLength = 0.72

    fun setTelemetryListener(listener: TelemetryListener?) {
        this.listener = listener
    }

    fun setGpsOrigin(lat: Double, lng: Double, alt: Double, accuracy: Float) {
        currentData = currentData.copy(
            trackingState = TrackingState.GPS_LOCKED,
            originLat = lat,
            originLng = lng,
            originAlt = alt,
            originAccuracy = accuracy,
            gpsFixTimestamp = System.currentTimeMillis(),
            isGpsDitched = true,
            currentLat = lat,
            currentLng = lng,
            currentAlt = alt,
            deltaX = 0.0,
            deltaY = 0.0,
            deltaZ = 0.0,
            stepCount = 0,
            totalDistance = 0.0,
            currentSpeed = 0.0f
        )
        // Reset velocity & pressure baseline
        velocityEarth[0] = 0f
        velocityEarth[1] = 0f
        velocityEarth[2] = 0f
        referencePressure = null
        notifyUpdate()
    }

    fun startTracking() {
        if (isTracking) return
        isTracking = true

        val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        rotSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        stepDetectorSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        currentData = currentData.copy(trackingState = TrackingState.SENSOR_TRACKING)
        notifyUpdate()
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        sensorManager.unregisterListener(this)
        currentData = currentData.copy(trackingState = TrackingState.STOPPED)
        notifyUpdate()
    }

    fun resetOrigin() {
        currentData = currentData.copy(
            originLat = currentData.currentLat,
            originLng = currentData.currentLng,
            originAlt = currentData.currentAlt,
            deltaX = 0.0,
            deltaY = 0.0,
            deltaZ = 0.0,
            stepCount = 0,
            totalDistance = 0.0
        )
        velocityEarth[0] = 0f
        velocityEarth[1] = 0f
        velocityEarth[2] = 0f
        notifyUpdate()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isTracking) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                System.arraycopy(event.values, 0, rotationVectorReading, 0, event.values.size.coerceAtMost(5))
                hasRotationVector = true
                updateOrientation()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerReading, 0, 3)
                hasAccel = true
                if (!hasRotationVector) updateOrientationFallback()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
                hasMag = true
                if (!hasRotationVector) updateOrientationFallback()
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                processLinearAcceleration(event.values, event.timestamp)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                processStep()
            }
            Sensor.TYPE_PRESSURE -> {
                processPressure(event.values[0])
            }
        }
    }

    private fun updateOrientation() {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorReading)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        // Convert azimuth (rad) to heading degrees [0..360)
        var yawDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        if (yawDeg < 0) yawDeg += 360f

        val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

        currentData = currentData.copy(
            yaw = yawDeg,
            pitch = pitchDeg,
            roll = rollDeg
        )
        notifyUpdate()
    }

    private fun updateOrientationFallback() {
        if (!hasAccel || !hasMag) return
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerReading,
            magnetometerReading
        )
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var yawDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (yawDeg < 0) yawDeg += 360f
            val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

            currentData = currentData.copy(
                yaw = yawDeg,
                pitch = pitchDeg,
                roll = rollDeg
            )
            notifyUpdate()
        }
    }

    private fun processLinearAcceleration(values: FloatArray, timestamp: Long) {
        if (lastTimestamp == 0L) {
            lastTimestamp = timestamp
            return
        }
        val dt = (timestamp - lastTimestamp) / 1_000_000_000.0f // seconds
        lastTimestamp = timestamp
        if (dt <= 0 || dt > 1.0f) return

        // Rotate linear acceleration vector into Earth coordinate frame: a_earth = R * a_device
        val ax = values[0]
        val ay = values[1]
        val az = values[2]

        val earthAx = rotationMatrix[0] * ax + rotationMatrix[1] * ay + rotationMatrix[2] * az
        val earthAy = rotationMatrix[3] * ax + rotationMatrix[4] * ay + rotationMatrix[5] * az
        val earthAz = rotationMatrix[6] * ax + rotationMatrix[7] * ay + rotationMatrix[8] * az

        // Apply threshold filter for noise suppression
        val threshold = 0.15f
        filteredAccelEarth[0] = if (kotlin.math.abs(earthAx) > threshold) earthAx else 0.0f
        filteredAccelEarth[1] = if (kotlin.math.abs(earthAy) > threshold) earthAy else 0.0f
        filteredAccelEarth[2] = if (kotlin.math.abs(earthAz) > threshold) earthAz else 0.0f

        // Instantaneous speed estimate
        val speed = kotlin.math.sqrt(
            (filteredAccelEarth[0] * dt).pow(2) + (filteredAccelEarth[1] * dt).pow(2)
        )
        currentData = currentData.copy(currentSpeed = speed)
    }

    private fun processStep() {
        val headingRad = Math.toRadians(currentData.yaw.toDouble())

        // Calculate step vector in East-North plane
        val stepEast = defaultStepLength * sin(headingRad)
        val stepNorth = defaultStepLength * cos(headingRad)

        val newDeltaX = currentData.deltaX + stepEast
        val newDeltaY = currentData.deltaY + stepNorth
        val newStepCount = currentData.stepCount + 1
        val newTotalDistance = currentData.totalDistance + defaultStepLength

        updateCoordinates(newDeltaX, newDeltaY, currentData.deltaZ, newStepCount, newTotalDistance)
    }

    private fun processPressure(pressureHpa: Float) {
        if (referencePressure == null) {
            referencePressure = pressureHpa
            return
        }
        val refP = referencePressure ?: return
        // Barometric altitude delta formula: h = 44330 * (1 - (P / P0)^(1/5.255))
        val relativeAlt = 44330.0 * (1.0 - (pressureHpa / refP).toDouble().pow(1.0 / 5.255))

        updateCoordinates(currentData.deltaX, currentData.deltaY, relativeAlt, currentData.stepCount, currentData.totalDistance)
    }

    fun updatePassiveGpsComparison(gpsLat: Double, gpsLng: Double, gpsAlt: Double, gpsAccuracy: Float) {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            currentData.currentLat, currentData.currentLng,
            gpsLat, gpsLng,
            results
        )
        val errorMeters = results[0]
        val verticalError = gpsAlt - currentData.currentAlt

        currentData = currentData.copy(
            latestGpsLat = gpsLat,
            latestGpsLng = gpsLng,
            latestGpsAlt = gpsAlt,
            latestGpsAccuracy = gpsAccuracy,
            errorDistanceMeters = errorMeters,
            errorVerticalMeters = verticalError,
            hasGpsComparison = true
        )
        notifyUpdate()
    }

    private fun updateCoordinates(
        deltaX: Double,
        deltaY: Double,
        deltaZ: Double,
        stepCount: Int,
        totalDistance: Double
    ) {
        val originLatRad = Math.toRadians(currentData.originLat)

        // Conversion factors: ~111,139 meters per degree of Latitude
        val deltaLat = deltaY / 111139.0
        val deltaLng = deltaX / (111139.0 * cos(originLatRad))

        val newLat = currentData.originLat + deltaLat
        val newLng = currentData.originLng + deltaLng
        val newAlt = currentData.originAlt + deltaZ

        var errorDist = currentData.errorDistanceMeters
        var vertError = currentData.errorVerticalMeters
        if (currentData.hasGpsComparison) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                newLat, newLng,
                currentData.latestGpsLat, currentData.latestGpsLng,
                results
            )
            errorDist = results[0]
            vertError = currentData.latestGpsAlt - newAlt
        }

        currentData = currentData.copy(
            deltaX = deltaX,
            deltaY = deltaY,
            deltaZ = deltaZ,
            currentLat = newLat,
            currentLng = newLng,
            currentAlt = newAlt,
            stepCount = stepCount,
            totalDistance = totalDistance,
            errorDistanceMeters = errorDist,
            errorVerticalMeters = vertError
        )

        notifyUpdate()
    }

    private fun notifyUpdate() {
        listener?.onTelemetryUpdated(currentData)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
