package com.inertialtrack.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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

    // --- Velocity integration state ---
    private var lastAccelTimestamp: Long = 0L
    private val velocityEarth = DoubleArray(3) // [Vx_east, Vy_north, Vz_up] in m/s
    private val positionEarth = DoubleArray(3) // accumulated [east, north, up] in meters
    private var stationaryCounter = 0
    private val ZUPT_THRESHOLD = 0.25f   // m/s^2 below which we consider "stationary"
    private val ZUPT_SAMPLES = 12        // consecutive quiet samples to trigger ZUPT
    private val ACCEL_THRESHOLD = 0.08f  // m/s^2 dead zone for noise floor

    // --- Accelerometer-based step detection fallback ---
    private var hasHardwareStepDetector = false
    private var accelMagnitudeHistory = FloatArray(20) // ring buffer
    private var accelHistoryIdx = 0
    private var accelHistoryFilled = false
    private var lastStepTimestamp: Long = 0L
    private val MIN_STEP_INTERVAL_NS = 250_000_000L // 250ms minimum between steps
    private var peakDetected = false
    private val STEP_PEAK_THRESHOLD = 11.5f  // m/s^2 magnitude peak (gravity ~9.81)
    private val STEP_VALLEY_THRESHOLD = 8.5f // m/s^2 magnitude valley

    // Step size in meters
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
        // Reset integration state
        velocityEarth[0] = 0.0; velocityEarth[1] = 0.0; velocityEarth[2] = 0.0
        positionEarth[0] = 0.0; positionEarth[1] = 0.0; positionEarth[2] = 0.0
        lastAccelTimestamp = 0L
        stationaryCounter = 0
        referencePressure = null
        // Reset step detection
        accelHistoryIdx = 0
        accelHistoryFilled = false
        lastStepTimestamp = 0L
        peakDetected = false
        notifyUpdate()
    }

    fun startTracking() {
        if (isTracking) return
        isTracking = true

        // Rotation sensor (best available)
        val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        // Linear acceleration (gravity-compensated) — preferred for integration
        val linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        // Raw accelerometer — always needed for orientation fallback + step detection fallback
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Step detector (hardware)
        val stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        hasHardwareStepDetector = (stepDetectorSensor != null)

        // Barometer
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        // Magnetometer for orientation fallback
        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // Register sensors — no double-registration
        rotSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        stepDetectorSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

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
        velocityEarth[0] = 0.0; velocityEarth[1] = 0.0; velocityEarth[2] = 0.0
        positionEarth[0] = 0.0; positionEarth[1] = 0.0; positionEarth[2] = 0.0
        stationaryCounter = 0
        lastAccelTimestamp = 0L
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
                // Fallback step detection from raw accel if no hardware step detector
                if (!hasHardwareStepDetector) {
                    detectStepFromAccelerometer(event.timestamp)
                }
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

    // ========== Orientation ==========

    private fun updateOrientation() {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorReading)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        var yawDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        if (yawDeg < 0) yawDeg += 360f

        val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

        currentData = currentData.copy(yaw = yawDeg, pitch = pitchDeg, roll = rollDeg)
        notifyUpdate()
    }

    private fun updateOrientationFallback() {
        if (!hasAccel || !hasMag) return
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var yawDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (yawDeg < 0) yawDeg += 360f
            val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

            currentData = currentData.copy(yaw = yawDeg, pitch = pitchDeg, roll = rollDeg)
            notifyUpdate()
        }
    }

    // ========== Linear Acceleration → Velocity → Displacement (double integration) ==========

    private fun processLinearAcceleration(values: FloatArray, timestamp: Long) {
        if (lastAccelTimestamp == 0L) {
            lastAccelTimestamp = timestamp
            return
        }
        val dt = (timestamp - lastAccelTimestamp) / 1_000_000_000.0 // seconds
        lastAccelTimestamp = timestamp
        if (dt <= 0.0 || dt > 0.5) return // reject garbage deltas

        // Rotate device-frame linear acceleration into Earth frame using rotation matrix
        val ax = values[0]; val ay = values[1]; val az = values[2]
        val earthAx = (rotationMatrix[0] * ax + rotationMatrix[1] * ay + rotationMatrix[2] * az).toDouble()
        val earthAy = (rotationMatrix[3] * ax + rotationMatrix[4] * ay + rotationMatrix[5] * az).toDouble()
        val earthAz = (rotationMatrix[6] * ax + rotationMatrix[7] * ay + rotationMatrix[8] * az).toDouble()

        // Dead zone filter: suppress sensor noise floor
        val fAx = if (abs(earthAx) > ACCEL_THRESHOLD) earthAx else 0.0
        val fAy = if (abs(earthAy) > ACCEL_THRESHOLD) earthAy else 0.0
        val fAz = if (abs(earthAz) > ACCEL_THRESHOLD) earthAz else 0.0

        // ZUPT: Zero Velocity Update — if acceleration is negligible for N consecutive samples,
        // the device is stationary so slam velocity to zero to stop drift accumulation.
        val accelMag = sqrt(fAx * fAx + fAy * fAy + fAz * fAz)
        if (accelMag < ZUPT_THRESHOLD) {
            stationaryCounter++
            if (stationaryCounter >= ZUPT_SAMPLES) {
                velocityEarth[0] = 0.0
                velocityEarth[1] = 0.0
                velocityEarth[2] = 0.0
            }
        } else {
            stationaryCounter = 0
        }

        // Integrate acceleration → velocity (trapezoidal would be better but euler is fine at high rates)
        velocityEarth[0] += fAx * dt
        velocityEarth[1] += fAy * dt
        velocityEarth[2] += fAz * dt

        // Exponential velocity decay to fight unbounded drift
        val decay = 0.98
        velocityEarth[0] *= decay
        velocityEarth[1] *= decay
        velocityEarth[2] *= decay

        // Integrate velocity → position
        positionEarth[0] += velocityEarth[0] * dt
        positionEarth[1] += velocityEarth[1] * dt
        positionEarth[2] += velocityEarth[2] * dt

        // Speed for display
        val speed = sqrt(velocityEarth[0] * velocityEarth[0] + velocityEarth[1] * velocityEarth[1]).toFloat()
        currentData = currentData.copy(currentSpeed = speed)

        // Push integrated displacement (this is ADDITIVE to step-based displacement)
        updateCoordinates(
            currentData.deltaX + velocityEarth[0] * dt,
            currentData.deltaY + velocityEarth[1] * dt,
            currentData.deltaZ,
            currentData.stepCount,
            currentData.totalDistance
        )
    }

    // ========== Step Detection ==========

    private fun processStep() {
        val headingRad = Math.toRadians(currentData.yaw.toDouble())

        val stepEast = defaultStepLength * sin(headingRad)
        val stepNorth = defaultStepLength * cos(headingRad)

        val newDeltaX = currentData.deltaX + stepEast
        val newDeltaY = currentData.deltaY + stepNorth
        val newStepCount = currentData.stepCount + 1
        val newTotalDistance = currentData.totalDistance + defaultStepLength

        updateCoordinates(newDeltaX, newDeltaY, currentData.deltaZ, newStepCount, newTotalDistance)
    }

    /**
     * Software step detector: peak-valley detection on raw accelerometer magnitude.
     * This fires when there is no hardware TYPE_STEP_DETECTOR available.
     */
    private fun detectStepFromAccelerometer(timestamp: Long) {
        val magnitude = sqrt(
            accelerometerReading[0] * accelerometerReading[0] +
            accelerometerReading[1] * accelerometerReading[1] +
            accelerometerReading[2] * accelerometerReading[2]
        )

        // Store in ring buffer for smoothing
        accelMagnitudeHistory[accelHistoryIdx] = magnitude
        accelHistoryIdx = (accelHistoryIdx + 1) % accelMagnitudeHistory.size
        if (accelHistoryIdx == 0) accelHistoryFilled = true

        // Need at least a full buffer to detect peaks
        val count = if (accelHistoryFilled) accelMagnitudeHistory.size else accelHistoryIdx
        if (count < 5) return

        // Simple moving average for smoothing
        var sum = 0f
        for (i in 0 until count) sum += accelMagnitudeHistory[i]
        val smoothed = sum / count

        // Peak-valley state machine
        if (!peakDetected && smoothed > STEP_PEAK_THRESHOLD) {
            peakDetected = true
        } else if (peakDetected && smoothed < STEP_VALLEY_THRESHOLD) {
            peakDetected = false
            // Debounce: enforce minimum time between steps
            if (timestamp - lastStepTimestamp > MIN_STEP_INTERVAL_NS) {
                lastStepTimestamp = timestamp
                processStep()
            }
        }
    }

    // ========== Barometric Altitude ==========

    private fun processPressure(pressureHpa: Float) {
        if (referencePressure == null) {
            referencePressure = pressureHpa
            return
        }
        val refP = referencePressure ?: return
        val relativeAlt = 44330.0 * (1.0 - (pressureHpa / refP).toDouble().pow(1.0 / 5.255))

        updateCoordinates(currentData.deltaX, currentData.deltaY, relativeAlt, currentData.stepCount, currentData.totalDistance)
    }

    // ========== GPS Comparison ==========

    fun updatePassiveGpsComparison(gpsLat: Double, gpsLng: Double, gpsAlt: Double, gpsAccuracy: Float) {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            currentData.currentLat, currentData.currentLng,
            gpsLat, gpsLng,
            results
        )
        currentData = currentData.copy(
            latestGpsLat = gpsLat,
            latestGpsLng = gpsLng,
            latestGpsAlt = gpsAlt,
            latestGpsAccuracy = gpsAccuracy,
            errorDistanceMeters = results[0],
            errorVerticalMeters = gpsAlt - currentData.currentAlt,
            hasGpsComparison = true
        )
        notifyUpdate()
    }

    // ========== Coordinate Projection ==========

    private fun updateCoordinates(
        deltaX: Double,
        deltaY: Double,
        deltaZ: Double,
        stepCount: Int,
        totalDistance: Double
    ) {
        val originLatRad = Math.toRadians(currentData.originLat)

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
