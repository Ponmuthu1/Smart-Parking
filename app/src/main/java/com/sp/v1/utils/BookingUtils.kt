package com.sp.v1.utils

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

/**
 * Utility class for booking-related operations
 */
object BookingUtils {
    private const val TAG = "BookingUtils"
    private val db = FirebaseFirestore.getInstance()
    
    /**
     * Checks for completed bookings and updates parking spot availability
     * This can be called whenever we need to refresh the status of bookings and spots
     */
    fun updateBookingAndSpotStatus() {
        val currentTime = Timestamp.now()
        Log.d(TAG, "Running updateBookingAndSpotStatus check at ${Date(currentTime.seconds * 1000)}")
        
        // First get all parking spots to check their status
        db.collection("parking_spots")
            .get()
            .addOnSuccessListener { spotDocuments ->
                Log.d(TAG, "Found ${spotDocuments.size()} parking spots to process")
                
                // For each spot, check if it should be available or not
                for (spotDoc in spotDocuments) {
                    val spotId = spotDoc.getString("name") ?: continue
                    val currentAvailability = spotDoc.getBoolean("is_available") ?: true
                    
                    // Get all active bookings for this spot
                    db.collection("bookings")
                        .whereEqualTo("parkingSpotId", spotId)
                        .whereIn("status", listOf("pending", "completed"))
                        .get()
                        .addOnSuccessListener { bookings ->
                            Log.d(TAG, "Found ${bookings.size()} bookings for spot $spotId")
                            
                            // Check if any booking is currently active (current time is between start and end)
                            val hasActiveBooking = bookings.any { doc ->
                                val startTime = doc.getTimestamp("startTime")
                                val endTime = doc.getTimestamp("endTime")
                                
                                if (startTime == null || endTime == null) {
                                    Log.e(TAG, "Booking ${doc.id} has null timestamps")
                                    return@any false
                                }
                                
                                // This logs timestamps for debugging
                                Log.d(TAG, "Booking ${doc.id}: Start=${Date(startTime.seconds * 1000)}, End=${Date(endTime.seconds * 1000)}, Current=${Date(currentTime.seconds * 1000)}")
                                
                                // A booking is active if current time is within its time window
                                val isActive = currentTime.seconds >= startTime.seconds && 
                                               currentTime.seconds <= endTime.seconds
                                
                                if (isActive) {
                                    Log.d(TAG, "Booking ${doc.id} is currently active")
                                }
                                
                                isActive
                            }
                            
                            // Determine if the spot should be available
                            val shouldBeAvailable = !hasActiveBooking
                            
                            // Only update if needed
                            if (currentAvailability != shouldBeAvailable) {
                                Log.d(TAG, "Updating spot $spotId availability: $shouldBeAvailable")
                                
                                // Update spot availability
                                db.collection("parking_spots")
                                    .document(spotDoc.id)
                                    .update("is_available", shouldBeAvailable)
                                    .addOnSuccessListener {
                                        Log.d(TAG, "Successfully updated spot $spotId availability to $shouldBeAvailable")
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e(TAG, "Failed to update spot $spotId availability: ${e.message}")
                                    }
                            } else {
                                Log.d(TAG, "Spot $spotId already has correct availability: $shouldBeAvailable")
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Error checking bookings for spot $spotId: ${e.message}")
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error fetching parking spots: ${e.message}")
            }
    }
    
    /**
     * Updates the availability status of a parking spot
     */
    private fun updateSpotAvailability(spotId: String, isAvailable: Boolean) {
        // First check the current status to avoid unnecessary updates
        db.collection("parking_spots")
            .whereEqualTo("name", spotId)
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    Log.e(TAG, "No parking spot found with ID: $spotId")
                    return@addOnSuccessListener
                }
                
                val spotDoc = documents.documents[0]
                val currentAvailability = spotDoc.getBoolean("is_available") ?: true
                
                // Only update if the value is different
                if (currentAvailability != isAvailable) {
                    // Found the parking spot, update its availability
                    db.collection("parking_spots")
                        .document(spotDoc.id)
                        .update("is_available", isAvailable)
                        .addOnSuccessListener {
                            Log.d(TAG, "Updated parking spot $spotId availability: $isAvailable")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to update parking spot availability: ${e.message}")
                        }
                } else {
                    Log.d(TAG, "Spot $spotId already has correct availability status: $isAvailable")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error finding parking spot to update: ${e.message}")
            }
    }
} 