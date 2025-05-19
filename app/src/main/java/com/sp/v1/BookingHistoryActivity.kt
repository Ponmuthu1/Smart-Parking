package com.sp.v1

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.sp.v1.databinding.ActivityBookingHistoryBinding
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*
import android.widget.Button
import android.widget.RatingBar
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.firebase.Timestamp
import com.sp.v1.utils.NavigationUtils
import android.util.Log
import com.sp.v1.utils.RatingUtils

class BookingHistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBookingHistoryBinding
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var adapter: BookingHistoryAdapter
    private val bookings = mutableListOf<Booking>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookingHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
        
        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Booking History"
        
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
        
        // Setup RecyclerView
        adapter = BookingHistoryAdapter(bookings, db) { booking ->
            // Handle booking item click - show details
            val intent = Intent(this, ReceiptActivity::class.java)
            intent.putExtra("bookingId", booking.id)
            startActivity(intent)
        }
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        
        // Load bookings
        loadBookingHistory()
    }
    
    private fun loadBookingHistory() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        
        db.collection("bookings")
            .whereEqualTo("userId", userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->
                bookings.clear()
                
                if (documents.isEmpty) {
                    binding.emptyView.visibility = View.VISIBLE
                } else {
                    for (document in documents) {
                        val booking = document.toObject(Booking::class.java).copy(id = document.id)
                        bookings.add(booking)
                    }
                }
                
                adapter.notifyDataSetChanged()
                binding.progressBar.visibility = View.GONE
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error loading bookings: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.progressBar.visibility = View.GONE
            }
    }
}

class BookingHistoryAdapter(
    private val bookings: List<Booking>,
    private val db: FirebaseFirestore,
    private val onItemClick: (Booking) -> Unit
) : RecyclerView.Adapter<BookingHistoryAdapter.BookingViewHolder>() {
    
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookingViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_booking_history, parent, false)
        return BookingViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: BookingViewHolder, position: Int) {
        val booking = bookings[position]
        holder.bind(booking)
        holder.itemView.setOnClickListener { onItemClick(booking) }
    }
    
    override fun getItemCount() = bookings.size
    
    inner class BookingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvParkingSpotName: TextView = itemView.findViewById(R.id.tvParkingSpotName)
        private val tvDateTime: TextView = itemView.findViewById(R.id.tvDateTime)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val navigateButton: Button = itemView.findViewById(R.id.navigateButton)
        private val rateButton: Button = itemView.findViewById(R.id.rateButton)
        
        fun bind(booking: Booking) {
            tvParkingSpotName.text = booking.parkingSpotName
            tvDateTime.text = dateFormat.format(booking.startTime.toDate())
            tvAmount.text = "₹${String.format("%.2f", booking.totalAmount)}"
            
            val statusText = when (booking.status) {
                "completed" -> "Completed"
                "pending" -> "Pending"
                "failed" -> "Failed"
                "cancelled" -> "Cancelled"
                else -> booking.status.capitalize()
            }
            tvStatus.text = statusText
            
            // Set status color
            val statusColor = when (booking.status) {
                "completed" -> R.color.success
                "pending" -> R.color.primary
                "failed", "cancelled" -> R.color.error
                else -> R.color.text_secondary
            }
            tvStatus.setTextColor(itemView.context.getColor(statusColor))

            // Check if booking has already been rated
            val ratingValue = booking.rating
            val hasRating = ratingValue != null && ratingValue > 0.0
            
            // Enable rating button only for completed bookings
            if (booking.status == "completed") {
                rateButton.isEnabled = true
                
                // Update button text if already rated
                if (hasRating) {
                    rateButton.text = "Rated"
                    rateButton.alpha = 0.7f
                } else {
                    rateButton.text = "Rate"
                    rateButton.alpha = 1.0f
                }
            } else {
                rateButton.isEnabled = false
                rateButton.alpha = 0.5f
            }
            
            // Set up rate button click listener
            rateButton.setOnClickListener {
                if (booking.status == "completed") {
                    showRatingDialog(booking)
                }
            }

            // Set up navigation button click listener
            navigateButton.setOnClickListener {
                // Get the parking spot details from Firestore
                db.collection("parking_spots")
                    .whereEqualTo("name", booking.parkingSpotName)
                    .get()
                    .addOnSuccessListener { documents ->
                        if (!documents.isEmpty) {
                            val spot = documents.documents[0]
                            val latitude = spot.getDouble("latitude")
                            val longitude = spot.getDouble("longitude")
                            
                            if (latitude != null && longitude != null) {
                                NavigationUtils.navigateToSpot(
                                    itemView.context,
                                    latitude,
                                    longitude,
                                    booking.parkingSpotName
                                )
                            } else {
                                Toast.makeText(
                                    itemView.context,
                                    "Location information not available",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            Toast.makeText(
                                itemView.context,
                                "Parking spot not found",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            itemView.context,
                            "Failed to get location: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        }
        
        private fun showRatingDialog(booking: Booking) {
            val dialogView = LayoutInflater.from(itemView.context).inflate(R.layout.dialog_rating, null)
            val ratingBar = dialogView.findViewById<RatingBar>(R.id.ratingBar)
            val commentEditText = dialogView.findViewById<EditText>(R.id.etComment)
            val submitButton = dialogView.findViewById<Button>(R.id.btnSubmitRating)
            
            // Pre-fill with existing rating if available
            booking.rating?.let { ratingValue ->
                ratingBar.rating = ratingValue.toFloat()
            }
            
            booking.ratingComment?.let { comment ->
                commentEditText.setText(comment)
            }
            
            val dialog = AlertDialog.Builder(itemView.context)
                .setView(dialogView)
                .setCancelable(true)
                .create()
            
            submitButton.setOnClickListener {
                val rating = ratingBar.rating
                if (rating <= 0) {
                    Toast.makeText(itemView.context, "Please provide a rating", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                val comment = commentEditText.text.toString().trim()
                
                // Show loading indicator
                Toast.makeText(itemView.context, "Submitting rating...", Toast.LENGTH_SHORT).show()
                
                // Use RatingUtils to submit the rating
                RatingUtils.submitRating(
                    context = itemView.context,
                    spotName = booking.parkingSpotName,
                    rating = rating.toDouble(),
                    bookingId = booking.id,
                    comment = comment,
                    onSuccess = {
                        // Update the local booking object
                        booking.rating = rating.toDouble()
                        booking.ratingComment = comment
                        
                        // Update the UI
                        rateButton.text = "Rated"
                        rateButton.alpha = 0.7f
                        
                        dialog.dismiss()
                    },
                    onFailure = { e ->
                        Toast.makeText(
                            itemView.context,
                            "Failed to submit rating: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.e("BookingHistory", "Rating submission failed", e)
                    }
                )
            }
            
            dialog.show()
        }
    }
} 