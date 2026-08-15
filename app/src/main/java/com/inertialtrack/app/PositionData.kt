package com.inertialtrack.app

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class TrackingState {
    IDLE,
    SEARCHING_GPS,
    GPS_LOCKED,
    SENSOR_TRACKING,
    STOPPED
}

@Parcelize
data class PositionData(
    val trackingState: TrackingState = TrackingState.IDLE,
    val originLat: Double = 0.0,
    val originLng: Double = 0.0,
    val originAlt: Double = 0.0,
    val originAccuracy: Float = 0.0f,
    val gpsFixTimestamp: Long = 0L,
    val isGpsDitched: Boolean = false,
    val currentLat: Double = 0.0,
    val currentLng: Double = 0.0,
    val currentAlt: Double = 0.0,
    val deltaX: Double = 0.0, // East displacement (meters)
    val deltaY: Double = 0.0, // North displacement (meters)
    val deltaZ: Double = 0.0, // Vertical displacement (meters)
    val yaw: Float = 0.0f,    // Heading degrees [0..360)
    val pitch: Float = 0.0f,
    val roll: Float = 0.0f,
    val stepCount: Int = 0,
    val totalDistance: Double = 0.0,
    val currentSpeed: Float = 0.0f,
    val latestGpsLat: Double = 0.0,
    val latestGpsLng: Double = 0.0,
    val latestGpsAlt: Double = 0.0,
    val latestGpsAccuracy: Float = 0.0f,
    val errorDistanceMeters: Float = 0.0f,
    val errorVerticalMeters: Double = 0.0,
    val hasGpsComparison: Boolean = false
) : Parcelable
