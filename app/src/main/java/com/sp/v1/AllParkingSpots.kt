package com.sp.v1

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.sp.v1.utils.CategoryUtils
import com.sp.v1.utils.NavigationUtils
import java.text.SimpleDateFormat
import java.util.*

class AllParkingSpots : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var categoryRecyclerView: RecyclerView
    private lateinit var db: FirebaseFirestore
    private val parkingSpotsList = mutableListOf<ParkingSpot>()
    private val filteredParkingSpotsList = mutableListOf<ParkingSpot>()
    private var selectedCategory: String? = null
    
    private val TAG = "AllParkingSpots"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_parking_spots)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        categoryRecyclerView = findViewById(R.id.categoryRecyclerView)
        // LinearLayoutManager is set in XML

        db = FirebaseFirestore.getInstance()
        
        // Set up category filters
        setupCategoryFilters()

        loadParkingSpots()
    }
    
    private fun setupCategoryFilters() {
        val categories = CategoryUtils.getAllCategories(this)
        
        // Add "All" as the first option
        val filterCategories = arrayOf("All") + categories
        
        categoryRecyclerView.adapter = CategoryFilterAdapter(filterCategories) { category ->
            filterSpotsByCategory(category)
        }
    }
    
    private fun filterSpotsByCategory(category: String) {
        selectedCategory = if (category == "All") null else category
        
        // Apply filter
        applyFilters()
    }
    
    private fun applyFilters() {
        // Start with all spots
        filteredParkingSpotsList.clear()
        
        // Apply category filter if selected
        if (selectedCategory != null) {
            filteredParkingSpotsList.addAll(parkingSpotsList.filter { spot -> 
                CategoryUtils.containsCategory(spot.categories, selectedCategory!!)
            })
        } else {
            // No filter, show all
            filteredParkingSpotsList.addAll(parkingSpotsList)
        }
        
        // Update the adapter
        recyclerView.adapter = ParkingSpotAdapter(filteredParkingSpotsList) { spot ->
            val intent = Intent(this, BookingActivity::class.java)
            intent.putExtra("parkingSpot", spot)
            startActivity(intent)
        }
    }

    private fun loadParkingSpots() {
        // Show loading UI
        findViewById<View>(R.id.progressBar).visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        
        // Fetch all parking spots
        db.collection("parking_spots").get()
            .addOnSuccessListener { documents ->
                findViewById<View>(R.id.progressBar).visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                
                if (documents.isEmpty) {
                    Toast.makeText(this, "No parking spots found!", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                
                // Clear and recreate list
                parkingSpotsList.clear()
                
                // Process parking spot documents
                for (doc in documents) {
                    val lat = doc.getDouble("latitude")
                    val lng = doc.getDouble("longitude")
                    val name = doc.getString("name")
                    
                    // Handle price_per_hour field which might be a number or string
                    val price = try {
                        // Get price as string and convert to double
                        doc.getString("price_per_hour")?.toDoubleOrNull() ?: 0.0
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing price: ${e.message}")
                        0.0
                    }
                    
                    val ownerUpi = doc.getString("owner_upi") ?: ""
                    val averageRating = doc.getDouble("averageRating") ?: 0.0
                    val ratingCount = doc.getLong("ratingCount")?.toInt() ?: 0
                    
                    // Handle categories - can be single string or array
                    val categoryList = mutableListOf<String>()
                    val defaultCategory = "Standard"
                    
                    try {
                        // Try to get categories as array
                        val categoriesArray = doc.get("categories") as? List<*>
                        if (categoriesArray != null && categoriesArray.isNotEmpty()) {
                            // Convert to list of strings
                            categoriesArray.forEach { category ->
                                if (category is String) {
                                    categoryList.add(category)
                                }
                            }
                        } else {
                            // Try to get single category string
                            val category = doc.getString("category") ?: defaultCategory
                            categoryList.add(category)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing categories: ${e.message}")
                        // Fallback to default
                        categoryList.add(defaultCategory)
                    }
                    
                    // If list is still empty, add default
                    if (categoryList.isEmpty()) {
                        categoryList.add(defaultCategory)
                    }
                    
                    if (lat != null && lng != null && name != null) {
                        val spot = ParkingSpot(
                            name = name,
                            latitude = lat,
                            longitude = lng,
                            price_per_hour = price,
                            address = doc.getString("address") ?: "",
                            is_available = doc.getBoolean("is_available") ?: true,
                            owner_upi = ownerUpi,
                            averageRating = averageRating,
                            ratingCount = ratingCount,
                            categories = categoryList,
                            category = if (categoryList.isNotEmpty()) categoryList[0] else defaultCategory
                        )
                        parkingSpotsList.add(spot)
                    }
                }
                
                // Apply initial filters
                applyFilters()
                
                // Fetch booking information for each spot
                checkCurrentBookings()
            }
            .addOnFailureListener { e ->
                findViewById<View>(R.id.progressBar).visibility = View.GONE
                Toast.makeText(this, "Failed to load parking spots: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun checkCurrentBookings() {
        // Current time for availability checks
        val currentTime = Timestamp.now()
        
        // Query ALL active and upcoming bookings
        db.collection("bookings")
            .whereIn("status", listOf("pending", "completed"))
            .get()
            .addOnSuccessListener { bookingDocs ->
                // Maps to track booking information by spot ID
                val spotBookings = mutableMapOf<String, MutableList<BookedTimeSlot>>()
                val currentlyBookedSpots = mutableMapOf<String, Boolean>()
                val hasBookingsSpots = mutableMapOf<String, Boolean>()
                
                // Process all bookings
                for (doc in bookingDocs) {
                    val bookingId = doc.id
                    val startTime = doc.getTimestamp("startTime")
                    val endTime = doc.getTimestamp("endTime")
                    val spotId = doc.getString("parkingSpotId") ?: continue
                    
                    if (startTime != null && endTime != null) {
                        // Create a time slot object
                        val timeSlot = BookedTimeSlot(bookingId, startTime, endTime)
                        
                        // Initialize the list if needed
                        if (!spotBookings.containsKey(spotId)) {
                            spotBookings[spotId] = mutableListOf()
                        }
                        
                        // Add this booking
                        spotBookings[spotId]?.add(timeSlot)
                        
                        // Check if booking is current or future
                        if (currentTime.seconds in startTime.seconds..endTime.seconds) {
                            currentlyBookedSpots[spotId] = true
                        }
                        
                        if (currentTime.seconds <= endTime.seconds) {
                            hasBookingsSpots[spotId] = true
                        }
                    }
                }
                
                // Update parking spot objects with booking information
                for (spot in parkingSpotsList) {
                    // Update availability based on current bookings
                    spot.is_available = spot.is_available && !currentlyBookedSpots.containsKey(spot.name)
                    
                    // Mark spots with future bookings
                    if (hasBookingsSpots.containsKey(spot.name)) {
                        spot.has_future_bookings = true
                    }
                    
                    // Add all booked time slots to the spot
                    spotBookings[spot.name]?.let { slots ->
                        spot.bookedTimeSlots.clear()
                        spot.bookedTimeSlots.addAll(slots)
                    }
                }
                
                // Reapply filters after updating availability
                applyFilters()
            }
            .addOnFailureListener { e ->
                // If we fail to check bookings, still show the spots with their database availability
                applyFilters()
                
                Toast.makeText(this, "Could not check current bookings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
    
    inner class CategoryFilterAdapter(
        private val categories: Array<String>,
        private val onCategorySelected: (String) -> Unit
    ) : RecyclerView.Adapter<CategoryFilterAdapter.ViewHolder>() {
        
        private var selectedPosition = 0 // "All" is selected by default
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val categoryText: TextView = itemView.findViewById(R.id.categoryText)
            
            init {
                itemView.setOnClickListener {
                    val oldPosition = selectedPosition
                    selectedPosition = adapterPosition
                    
                    // Update the UI for the previously selected item
                    notifyItemChanged(oldPosition)
                    // Update the UI for the newly selected item
                    notifyItemChanged(selectedPosition)
                    
                    // Notify callback
                    onCategorySelected(categories[selectedPosition])
                }
            }
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category_filter, parent, false)
            return ViewHolder(view)
        }
        
        override fun getItemCount() = categories.size
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val category = categories[position]
            holder.categoryText.text = category
            
            // Set background based on selection state
            if (position == selectedPosition) {
                // Selected item
                if (category == "All") {
                    // Special case for "All" category
                    holder.itemView.background = ContextCompat.getDrawable(
                        this@AllParkingSpots, 
                        R.drawable.category_background
                    )
                    holder.categoryText.setTextColor(ContextCompat.getColor(
                        this@AllParkingSpots,
                        R.color.white
                    ))
                } else {
                    // Use category-specific color
                    holder.itemView.background = CategoryUtils.getCategoryBackground(
                        this@AllParkingSpots,
                        category
                    )
                    holder.categoryText.setTextColor(ContextCompat.getColor(
                        this@AllParkingSpots,
                        R.color.white
                    ))
                }
            } else {
                // Unselected item - use light gray background
                (holder.itemView as androidx.cardview.widget.CardView).setCardBackgroundColor(
                    ContextCompat.getColor(this@AllParkingSpots, R.color.blue_light)
                )
                holder.categoryText.setTextColor(
                    ContextCompat.getColor(this@AllParkingSpots, R.color.text_primary)
                )
            }
        }
    }
    
    inner class ParkingSpotAdapter(
        private val spots: List<ParkingSpot>,
        private val onItemClick: (ParkingSpot) -> Unit
    ) : RecyclerView.Adapter<ParkingSpotAdapter.ViewHolder>() {
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val nameText: TextView = itemView.findViewById(R.id.text1)
            val detailsText: TextView = itemView.findViewById(R.id.text2)
            val categoryText: TextView = itemView.findViewById(R.id.tvCategory)
            val bookButton: Button = itemView.findViewById(R.id.btn_book)
            val navigateButton: Button = itemView.findViewById(R.id.btn_navigate)
            
            init {
                bookButton.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onItemClick(spots[position])
                    }
                }
                
                navigateButton.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        val spot = spots[position]
                        NavigationUtils.navigateToSpot(this@AllParkingSpots, spot.latitude, spot.longitude, spot.name)
                    }
                }
            }
        }
        
        private fun navigateToSpot(spot: ParkingSpot) {
            val intent = Intent(this@AllParkingSpots, MapsActivity::class.java).apply {
                putExtra("latitude", spot.latitude)
                putExtra("longitude", spot.longitude)
                putExtra("spotName", spot.name)
                putExtra("navigate", true)
            }
            this@AllParkingSpots.startActivity(intent)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.parking_spot_item, parent, false)
            return ViewHolder(view)
        }
        
        override fun getItemCount() = spots.size
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val spot = spots[position]
            
            // Set the name
            holder.nameText.text = spot.name
            
            // Set rating bar and count
            val ratingBar = holder.itemView.findViewById<android.widget.RatingBar>(R.id.ratingBarSpot)
            val ratingCount = holder.itemView.findViewById<TextView>(R.id.tvRatingCount)
            
            ratingBar.rating = spot.averageRating.toFloat()
            ratingCount.text = "(${spot.ratingCount})"
            
            // Prepare availability information
            val availabilityText = when {
                !spot.is_available -> "Currently Booked"
                spot.has_future_bookings -> {
                    // Get the next available time
                    val nextAvailable = spot.getNextAvailableTime(Date())
                    val formattedTime = nextAvailable?.let { 
                        SimpleDateFormat("EEE, MMM dd, HH:mm", Locale.getDefault()).format(it)
                    } ?: "unknown time"
                    
                    "Limited Availability\nNext fully available: $formattedTime"
                }
                else -> "Fully Available"
            }
            
            // Set the details text
            holder.detailsText.text = "${spot.address}\nPrice: ₹${spot.price_per_hour}/hour\n$availabilityText"
            
            // Set primary category text and background
            if (spot.categories.isNotEmpty()) {
                // Display the primary category (first in the list)
                val primaryCategory = spot.categories[0]
                holder.categoryText.text = primaryCategory
                holder.categoryText.background = CategoryUtils.getCategoryBackground(this@AllParkingSpots, primaryCategory)
                
                // If there are multiple categories, show "+" indicator
                if (spot.categories.size > 1) {
                    holder.categoryText.text = "${primaryCategory} +${spot.categories.size - 1}"
                }
            } else {
                // Fallback to single category field
                holder.categoryText.text = spot.category
                holder.categoryText.background = CategoryUtils.getCategoryBackground(this@AllParkingSpots, spot.category)
            }
            
            // Always enable the button but change its appearance based on availability
            if (!spot.is_available) {
                holder.bookButton.text = "Currently Booked"
                holder.bookButton.alpha = 0.7f
                holder.bookButton.setBackgroundColor(android.graphics.Color.RED)
            } else if (spot.has_future_bookings) {
                holder.bookButton.text = "Book (Limited)"
                holder.bookButton.setBackgroundColor(android.graphics.Color.rgb(255, 165, 0))
            } else {
                holder.bookButton.text = "Book Now"
                holder.bookButton.alpha = 1.0f
            }
        }
    }
}
