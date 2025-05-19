package com.sp.v1

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*
import com.sp.v1.databinding.ActivityReceiptBinding
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import android.view.View

class ReceiptActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReceiptBinding
    private lateinit var db: FirebaseFirestore
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = FirebaseFirestore.getInstance()
        val bookingId = intent.getStringExtra("bookingId")

        if (bookingId != null) {
            loadBookingDetails(bookingId)
        } else {
            Toast.makeText(this, "Invalid booking ID", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.doneButton.setOnClickListener {
            finish()
        }
    }

    private fun loadBookingDetails(bookingId: String) {
        db.collection("bookings")
            .document(bookingId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val booking = document.toObject(Booking::class.java)?.copy(id = bookingId)
                    
                    if (booking != null) {
                        // Now fetch the parking spot details to get rating in the same call
                        db.collection("parking_spots")
                            .whereEqualTo("name", booking.parkingSpotName)
                            .get()
                            .addOnSuccessListener { spotDocuments ->
                                if (!spotDocuments.isEmpty) {
                                    val spotDoc = spotDocuments.documents[0]
                                    val averageRating = spotDoc.getDouble("averageRating") ?: 0.0
                                    val ratingCount = spotDoc.getLong("ratingCount")?.toInt() ?: 0
                                    
                                    // Create a parking spot object with rating data
                                    val parkingSpot = ParkingSpot(
                                        name = booking.parkingSpotName,
                                        averageRating = averageRating,
                                        ratingCount = ratingCount
                                    )
                                    
                                    // Display all information together
                                    displayBookingDetails(booking, parkingSpot)
                                } else {
                                    // If we can't find the spot, just display the booking with default values
                                    displayBookingDetails(booking, ParkingSpot(name = booking.parkingSpotName))
                                }
                            }
                            .addOnFailureListener { e ->
                                // Just show the booking without ratings if spot fetch fails
                                displayBookingDetails(booking, ParkingSpot(name = booking.parkingSpotName))
                            }
                    } else {
                        Toast.makeText(this, "Booking not found", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    Toast.makeText(this, "Booking not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load booking details", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun displayBookingDetails(booking: Booking, parkingSpot: ParkingSpot) {
        binding.parkingSpotName.text = "Parking Spot: ${booking.parkingSpotName}"
        
        // Display rating information from the parking spot object
        binding.ratingBarSpot.rating = parkingSpot.averageRating.toFloat()
        binding.tvRatingCount.text = "(${parkingSpot.ratingCount})"
        
        binding.bookingDate.text = "Date: ${dateFormat.format(booking.startTime.toDate())}"
        binding.bookingTime.text = "Time: ${timeFormat.format(booking.startTime.toDate())}"
        
        val duration = (booking.endTime.toDate().time - booking.startTime.toDate().time) / (1000 * 60 * 60)
        binding.duration.text = "Duration: $duration hours"
        
        // Hide discount section as the totalAmount is already the final price
        binding.discountSection.visibility = View.GONE
        
        binding.totalAmount.text = "Total Amount: ₹${String.format("%.2f", booking.totalAmount)}"
        
        // Display payment status with more details
        val statusText = when (booking.status) {
            "completed" -> "Payment Status: Completed\nPayment ID: ${booking.paymentId}"
            "failed" -> {
                // Format error message to be more user-friendly
                val errorMessage = if (booking.paymentError.contains("BAD_REQUEST_ERROR")) {
                    "Payment authentication failed. Please try again with a different payment method."
                } else {
                    "Payment failed. Please try again later."
                }
                "Payment Status: Failed\nError: $errorMessage"
            }
            "pending" -> "Payment Status: Pending"
            "cancelled" -> "Payment Status: Cancelled"
            else -> "Payment Status: ${booking.status}"
        }
        binding.paymentStatus.text = statusText
    }
} 