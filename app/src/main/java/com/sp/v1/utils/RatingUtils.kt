package com.sp.v1.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Utility class for handling parking spot ratings
 */
object RatingUtils {
    private const val TAG = "RatingUtils"
    private val db = FirebaseFirestore.getInstance()

    /**
     * Submit a new rating for a parking spot
     * @param context Context for displaying toasts
     * @param spotName Name of the parking spot
     * @param rating Rating value (1-5)
     * @param bookingId The ID of the booking being rated
     * @param comment Optional comment with the rating
     * @param onSuccess Callback when rating is successfully saved
     * @param onFailure Callback when rating fails
     */
    fun submitRating(
        context: Context,
        spotName: String,
        rating: Double,
        bookingId: String,
        comment: String = "",
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        Log.d(TAG, "Submitting rating $rating for spot: $spotName")
        
        // First, save rating to booking
        db.collection("bookings").document(bookingId)
            .update(
                mapOf(
                    "rating" to rating,
                    "ratingComment" to comment,
                    "ratedAt" to Timestamp.now()
                )
            )
            .addOnSuccessListener {
                Log.d(TAG, "Rating saved to booking $bookingId")
                // Now update the parking spot's average rating
                updateParkingSpotRating(context, spotName, rating, onSuccess, onFailure)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update booking with rating: ${e.message}", e)
                onFailure(e)
            }
    }

    /**
     * Directly update a parking spot's rating in the database
     */
    private fun updateParkingSpotRating(
        context: Context,
        spotName: String,
        newRating: Double,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Show a loading toast
        Toast.makeText(context, "Updating spot rating...", Toast.LENGTH_SHORT).show()
        
        // Find the parking spot document
        db.collection("parking_spots")
            .whereEqualTo("name", spotName)
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    val error = Exception("No parking spot found with name: $spotName")
                    Log.e(TAG, error.message ?: "")
                    onFailure(error)
                    return@addOnSuccessListener
                }
                
                val spotDoc = documents.documents[0]
                val spotId = spotDoc.id
                
                // Get current rating data
                val currentRating = spotDoc.getDouble("averageRating") ?: 0.0
                val currentCount = spotDoc.getLong("ratingCount")?.toInt() ?: 0
                
                Log.d(TAG, "Found spot with ID: $spotId, current rating: $currentRating, count: $currentCount")
                
                // Calculate new values
                val newCount = currentCount + 1
                val newTotalRating = (currentRating * currentCount) + newRating
                val newAverageRating = newTotalRating / newCount
                
                // Round to 1 decimal place for display
                val roundedRating = Math.round(newAverageRating * 10) / 10.0
                
                Log.d(TAG, "Calculated new rating: $roundedRating from $newCount reviews")
                
                // Update the document
                val updateData = mapOf(
                    "averageRating" to roundedRating,
                    "ratingCount" to newCount,
                    "lastRatingUpdate" to Timestamp.now()
                )
                
                db.collection("parking_spots").document(spotId)
                    .update(updateData)
                    .addOnSuccessListener {
                        Log.d(TAG, "Successfully updated spot rating to $roundedRating stars")
                        Toast.makeText(
                            context, 
                            "Spot rating updated to $roundedRating ⭐", 
                            Toast.LENGTH_SHORT
                        ).show()
                        onSuccess()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to update spot rating: ${e.message}", e)
                        onFailure(e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error finding parking spot: ${e.message}", e)
                onFailure(e)
            }
    }
    
    /**
     * Check if a document field exists in a collection
     * @param collectionPath The Firestore collection path
     * @param documentId The document ID
     * @param fieldName The field name to check
     * @param onResult Callback with whether the field exists (true/false)
     */
    fun checkIfFieldExists(
        collectionPath: String,
        documentId: String,
        fieldName: String,
        onResult: (Boolean) -> Unit
    ) {
        db.collection(collectionPath).document(documentId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists() && document.contains(fieldName)) {
                    onResult(true)
                } else {
                    onResult(false)
                }
            }
            .addOnFailureListener {
                onResult(false)
            }
    }

    /**
     * Directly set the rating fields for a parking spot in a single atomic operation
     * This is useful to ensure the rating fields exist or to fix missing ratings
     */
    fun setRatingFields(
        context: Context,
        spotName: String,
        averageRating: Double,
        ratingCount: Int,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Find the parking spot document
        db.collection("parking_spots")
            .whereEqualTo("name", spotName)
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    val error = Exception("No parking spot found with name: $spotName")
                    Log.e(TAG, error.message ?: "")
                    onFailure(error)
                    return@addOnSuccessListener
                }
                
                val spotDoc = documents.documents[0]
                val spotId = spotDoc.id
                
                // Directly set the rating fields
                val updateData = mapOf(
                    "averageRating" to averageRating,
                    "ratingCount" to ratingCount,
                    "lastRatingUpdate" to Timestamp.now()
                )
                
                db.collection("parking_spots").document(spotId)
                    .update(updateData)
                    .addOnSuccessListener {
                        Log.d(TAG, "Successfully set rating fields for $spotName")
                        Toast.makeText(
                            context, 
                            "Rating fields updated for $spotName", 
                            Toast.LENGTH_SHORT
                        ).show()
                        onSuccess()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to set rating fields: ${e.message}", e)
                        
                        // If update fails (e.g., fields don't exist), try set operation instead
                        db.collection("parking_spots").document(spotId)
                            .set(updateData, com.google.firebase.firestore.SetOptions.merge())
                            .addOnSuccessListener {
                                Log.d(TAG, "Successfully set rating fields using merge for $spotName")
                                Toast.makeText(
                                    context, 
                                    "Rating fields created for $spotName", 
                                    Toast.LENGTH_SHORT
                                ).show()
                                onSuccess()
                            }
                            .addOnFailureListener { ex ->
                                Log.e(TAG, "Failed to set rating fields with merge: ${ex.message}", ex)
                                onFailure(ex)
                            }
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error finding parking spot: ${e.message}", e)
                onFailure(e)
            }
    }

    /**
     * Initialize rating fields for all parking spots in the database
     * This is useful to batch initialize or fix ratings
     */
    fun initializeAllSpotRatings(
        context: Context,
        onComplete: (Int) -> Unit
    ) {
        Log.d(TAG, "Initializing ratings for all parking spots")
        Toast.makeText(context, "Initializing ratings for all spots...", Toast.LENGTH_SHORT).show()
        
        db.collection("parking_spots")
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    Log.d(TAG, "No parking spots found to initialize")
                    Toast.makeText(context, "No spots found to initialize", Toast.LENGTH_SHORT).show()
                    onComplete(0)
                    return@addOnSuccessListener
                }
                
                var updatedCount = 0
                var totalToUpdate = documents.size()
                
                for (spotDoc in documents) {
                    val spotId = spotDoc.id
                    val spotName = spotDoc.getString("name") ?: continue
                    
                    // Check if rating fields already exist
                    val hasRating = spotDoc.contains("averageRating")
                    val hasCount = spotDoc.contains("ratingCount")
                    
                    // If fields don't exist, add them with default values
                    if (!hasRating || !hasCount) {
                        val updateData = mapOf(
                            "averageRating" to 0.0,
                            "ratingCount" to 0,
                            "lastRatingUpdate" to Timestamp.now()
                        )
                        
                        db.collection("parking_spots").document(spotId)
                            .update(updateData)
                            .addOnSuccessListener {
                                updatedCount++
                                Log.d(TAG, "Initialized rating fields for spot: $spotName")
                                
                                if (updatedCount >= totalToUpdate) {
                                    Log.d(TAG, "All spots initialized: $updatedCount")
                                    Toast.makeText(
                                        context, 
                                        "Successfully initialized $updatedCount spots", 
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onComplete(updatedCount)
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Failed to initialize rating for $spotName: ${e.message}", e)
                                
                                // Try using set with merge instead
                                db.collection("parking_spots").document(spotId)
                                    .set(updateData, com.google.firebase.firestore.SetOptions.merge())
                                    .addOnSuccessListener {
                                        updatedCount++
                                        Log.d(TAG, "Initialized rating fields with merge for spot: $spotName")
                                        
                                        if (updatedCount >= totalToUpdate) {
                                            Log.d(TAG, "All spots initialized: $updatedCount")
                                            Toast.makeText(
                                                context, 
                                                "Successfully initialized $updatedCount spots", 
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            onComplete(updatedCount)
                                        }
                                    }
                                    .addOnFailureListener { ex ->
                                        Log.e(TAG, "Failed to initialize with merge for $spotName: ${ex.message}", ex)
                                        // Count this spot as processed even though it failed
                                        updatedCount++
                                        
                                        if (updatedCount >= totalToUpdate) {
                                            onComplete(updatedCount)
                                        }
                                    }
                            }
                    } else {
                        // Already has rating fields, count as processed
                        updatedCount++
                        
                        if (updatedCount >= totalToUpdate) {
                            Log.d(TAG, "All spots checked: $updatedCount")
                            Toast.makeText(
                                context, 
                                "All $updatedCount spots already initialized", 
                                Toast.LENGTH_SHORT
                            ).show()
                            onComplete(updatedCount)
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error getting parking spots: ${e.message}", e)
                Toast.makeText(
                    context, 
                    "Error initializing ratings: ${e.message}", 
                    Toast.LENGTH_SHORT
                ).show()
                onComplete(0)
            }
    }
} 