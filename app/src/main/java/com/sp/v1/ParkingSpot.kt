package com.sp.v1

import android.os.Parcel
import android.os.Parcelable
import com.google.firebase.Timestamp
import java.util.Date

data class ParkingSpot(
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val price_per_hour: Double = 0.0,
    var distance: Float = 0f,
    val address: String = "",
    var is_available: Boolean = true,
    var has_future_bookings: Boolean = false,
    var owner_upi: String = "",
    // Rating fields
    var averageRating: Double = 0.0,
    var ratingCount: Int = 0,
    // Category fields - support multiple categories
    var categories: MutableList<String> = mutableListOf("Standard"),
    var category: String = "Standard", // Keeping this for backward compatibility
    // New fields for tracking time slot availability
    var bookedTimeSlots: MutableList<BookedTimeSlot> = mutableListOf()
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readDouble(),
        parcel.readDouble(),
        parcel.readDouble(),
        parcel.readFloat(),
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readString() ?: "",
        parcel.readDouble(),
        parcel.readInt(),
        mutableListOf<String>().apply {
            parcel.readStringList(this)
        },
        parcel.readString() ?: "Standard"
        // Note: Complex objects like lists aren't included in the parcel constructor
        // They're initialized with default values
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeDouble(latitude)
        parcel.writeDouble(longitude)
        parcel.writeDouble(price_per_hour)
        parcel.writeFloat(distance)
        parcel.writeString(address)
        parcel.writeByte(if (is_available) 1 else 0)
        parcel.writeByte(if (has_future_bookings) 1 else 0)
        parcel.writeString(owner_upi)
        parcel.writeDouble(averageRating)
        parcel.writeInt(ratingCount)
        parcel.writeStringList(categories)
        parcel.writeString(category)
        // Complex objects aren't written to parcel
    }

    /**
     * Get the primary (first) category or default if empty
     */
    fun getPrimaryCategory(): String {
        return if (categories.isNotEmpty()) categories[0] else category
    }
    
    /**
     * Check if the spot has a specific category
     */
    fun hasCategory(categoryToCheck: String): Boolean {
        return categories.any { it.equals(categoryToCheck, ignoreCase = true) }
    }

    /**
     * Checks if this parking spot is available for the specified time slot
     * @param startTime The starting time of the requested booking
     * @param endTime The ending time of the requested booking
     * @return true if the spot is available during the requested time slot, false otherwise
     */
    fun isAvailableForTimeSlot(startTime: Timestamp, endTime: Timestamp): Boolean {
        // If the parking spot is generally unavailable (and not due to time conflicts),
        // we need to check if it's because of a current booking
        val currentTime = Timestamp.now()
        
        // Check for current bookings to see if that's why it's unavailable
        val hasCurrentBooking = bookedTimeSlots.any { slot ->
            currentTime.seconds >= slot.startTime.seconds && 
            currentTime.seconds <= slot.endTime.seconds
        }
        
        // If there's no current booking but the spot is marked as unavailable,
        // this is a database issue - we'll check time conflicts anyway
        if (!is_available && !hasCurrentBooking) {
            // Log this anomaly
            android.util.Log.w("ParkingSpot", 
                "Spot $name is marked unavailable but has no current bookings")
        }
        
        // Check if there's any overlap with existing bookings
        for (slot in bookedTimeSlots) {
            // Log the comparison for debugging
            android.util.Log.d("ParkingSpot", "Comparing booking times: " +
                    "Request: ${java.util.Date(startTime.seconds * 1000)} - ${java.util.Date(endTime.seconds * 1000)}, " +
                    "Existing: ${java.util.Date(slot.startTime.seconds * 1000)} - ${java.util.Date(slot.endTime.seconds * 1000)}")
            
            // Check for overlap: (StartA <= EndB) and (EndA >= StartB)
            if (startTime.seconds <= slot.endTime.seconds && endTime.seconds >= slot.startTime.seconds) {
                // There's an overlap, so this time slot is not available
                android.util.Log.d("ParkingSpot", "Time conflict detected - slot not available")
                return false
            }
        }
        
        // No overlaps found, the spot is available for this time slot
        // (Even if is_available is false, we'll assume it's a stale state if there are no active bookings)
        android.util.Log.d("ParkingSpot", "No time conflicts found - slot is available")
        return true
    }
    
    /**
     * Returns the next available time slot after the given time
     * @param afterTime The time to check availability after
     * @return Date representing the next available starting time, or null if spot is unavailable
     */
    fun getNextAvailableTime(afterTime: Date): Date? {
        android.util.Log.d("ParkingSpot", "Finding next available time after ${afterTime}")
        
        // If no booked time slots, the spot is available right away
        if (bookedTimeSlots.isEmpty()) {
            android.util.Log.d("ParkingSpot", "No booked slots, spot is available immediately")
            return afterTime
        }
        
        // Convert to timestamp for comparison
        val afterTimestamp = Timestamp(afterTime)
        
        // Sort slots by start time
        val sortedSlots = bookedTimeSlots.sortedBy { it.startTime.seconds }
        
        // Debug log of all bookings
        for (slot in sortedSlots) {
            android.util.Log.d("ParkingSpot", "Booking: " +
                    "Start=${java.util.Date(slot.startTime.seconds * 1000)}, " +
                    "End=${java.util.Date(slot.endTime.seconds * 1000)}")
        }
        
        // Check if any booking is happening now
        val currentBooking = sortedSlots.find { 
            afterTimestamp.seconds >= it.startTime.seconds && 
            afterTimestamp.seconds <= it.endTime.seconds 
        }
        
        // If there's a current booking, the next available time is after it ends
        if (currentBooking != null) {
            val nextTime = Date(currentBooking.endTime.seconds * 1000)
            android.util.Log.d("ParkingSpot", "Currently booked, next available: $nextTime")
            return nextTime
        }
        
        // If the requested time is already after all bookings, it's available immediately
        if (sortedSlots.isNotEmpty() && sortedSlots.last().endTime.seconds < afterTimestamp.seconds) {
            android.util.Log.d("ParkingSpot", "Requested time is after all bookings, available immediately")
            return afterTime
        }
        
        // Check if the requested time is before any bookings start
        val nextBooking = sortedSlots.find { it.startTime.seconds > afterTimestamp.seconds }
        if (nextBooking != null) {
            // If there's a booking in the future, check if there's a gap from now until then
            if (afterTimestamp.seconds < nextBooking.startTime.seconds) {
                // The spot is available now until the next booking starts
                android.util.Log.d("ParkingSpot", "Available now until next booking starts")
                return afterTime
            }
        }
        
        // Look for gaps between bookings
        for (i in 0 until sortedSlots.size - 1) {
            val currentEndTime = sortedSlots[i].endTime
            val nextStartTime = sortedSlots[i + 1].startTime
            
            // If there's a gap and it's after our requested time
            if (currentEndTime.seconds < nextStartTime.seconds && 
                currentEndTime.seconds >= afterTimestamp.seconds) {
                val nextTime = Date(currentEndTime.seconds * 1000)
                android.util.Log.d("ParkingSpot", "Found gap between bookings, available at: $nextTime")
                return nextTime
            }
        }
        
        // If we reach here and there are bookings, the next available time is after the last booking ends
        if (sortedSlots.isNotEmpty()) {
            val nextTime = Date(sortedSlots.last().endTime.seconds * 1000)
            android.util.Log.d("ParkingSpot", "Available after last booking: $nextTime")
            return nextTime
        }
        
        // Final fallback - if we somehow get here, the spot is available now
        android.util.Log.d("ParkingSpot", "No constraints found, available now")
        return afterTime
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ParkingSpot> {
        override fun createFromParcel(parcel: Parcel): ParkingSpot {
            return ParkingSpot(parcel)
        }

        override fun newArray(size: Int): Array<ParkingSpot?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * Represents a time slot that has been booked for a parking spot
 */
data class BookedTimeSlot(
    val bookingId: String = "",
    val startTime: Timestamp = Timestamp.now(),
    val endTime: Timestamp = Timestamp.now()
)
