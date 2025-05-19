package com.sp.v1

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.sp.v1.databinding.ActivityMainBinding
import com.sp.v1.utils.NavigationUtils
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private val PLAY_SERVICES_RESOLUTION_REQUEST = 9000
    private val dateTimeFormat = SimpleDateFormat("EEE, MMM dd • HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firebaseAuth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Check if Google Play Services is available
        checkPlayServicesAvailability()

        val currentUser = firebaseAuth.currentUser
        if (currentUser != null) {
            val email = currentUser.email ?: ""
            val username = email.substringBefore("@")
            val capitalizedUsername = username.replaceFirstChar { it.uppercase() }
            binding.tvUserEmail.text = "Welcome, $capitalizedUsername"
            // Load active bookings
            loadActiveBookings()
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
        
        binding.btnViewMap.setOnClickListener {
            startActivity(Intent(this, MapsActivity::class.java))
        }

        binding.btnViewHistory.setOnClickListener {
            startActivity(Intent(this, BookingHistoryActivity::class.java))
        }

        binding.btnLogout.setOnClickListener {
            firebaseAuth.signOut()
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
        
        // Add Parking Spot button
        binding.btnAddParkingSpot.setOnClickListener {
            startActivity(Intent(this, AddParkingSpotActivity::class.java))
        }

        binding.btnSubscription.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }
    }
    
    /**
     * Loads active bookings for the current user and displays them
     */
    private fun loadActiveBookings() {
        val currentTime = Timestamp.now()
        val userId = firebaseAuth.currentUser?.uid ?: return
        
        // By default, hide the active bookings section
        binding.activeBookingsCard.visibility = View.VISIBLE // Initially visible to show progress
        binding.bookingsProgressBar.visibility = View.VISIBLE
        
        // Add debug logs
        android.util.Log.d("MainActivity", "Loading active bookings for user: $userId")
        android.util.Log.d("MainActivity", "Current time: ${Date(currentTime.seconds * 1000)}")
        
        try {
            // Simpler query that's less likely to fail
            // Just get all the user's bookings and filter in code if needed
            db.collection("bookings")
                .whereEqualTo("userId", userId)
                .get()
                .addOnSuccessListener { documents ->
                    binding.bookingsProgressBar.visibility = View.GONE
                    
                    android.util.Log.d("MainActivity", "Query successful, found ${documents.size()} total bookings")
                    
                    // Filter active bookings (completed status and end time in the future)
                    val activeBookings = documents.filter { doc -> 
                        val status = doc.getString("status")
                        val endTime = doc.getTimestamp("endTime")
                        
                        status == "completed" && endTime != null && endTime.compareTo(currentTime) > 0
                    }
                    
                    android.util.Log.d("MainActivity", "After filtering: ${activeBookings.size} active bookings")
                    
                    if (activeBookings.isEmpty()) {
                        // No active bookings
                        binding.activeBookingsCard.visibility = View.VISIBLE
                        binding.noBookingsText.visibility = View.VISIBLE
                        binding.bookingsContainer.visibility = View.GONE
                        binding.btnViewAllBookings.visibility = View.GONE
                        android.util.Log.d("MainActivity", "No active bookings found")
                        
                        // Set text for no bookings
                        binding.noBookingsText.text = "You don't have any active bookings"
                    } else {
                        // Show the active bookings section
                        binding.activeBookingsCard.visibility = View.VISIBLE
                        binding.noBookingsText.visibility = View.GONE
                        binding.bookingsContainer.visibility = View.VISIBLE
                        
                        // Clear existing bookings
                        binding.bookingsContainer.removeAllViews()
                        
                        android.util.Log.d("MainActivity", "Processing ${activeBookings.size} bookings")
                        
                        // Sort bookings by end time (soonest first)
                        val sortedBookings = activeBookings.sortedBy { 
                            it.getTimestamp("endTime")?.seconds ?: Long.MAX_VALUE 
                        }.take(5) // Limit to 5 bookings
                        
                        for (document in sortedBookings) {
                            val spotName = document.getString("parkingSpotName") ?: "Unknown Spot"
                            val startTime = document.getTimestamp("startTime")
                            val endTime = document.getTimestamp("endTime")
                            val bookingId = document.id
                            
                            android.util.Log.d("MainActivity", "Processing booking: $bookingId, spot: $spotName")
                            android.util.Log.d("MainActivity", "Start time: ${startTime?.toDate()}, End time: ${endTime?.toDate()}")
                            
                            if (startTime != null && endTime != null) {
                                try {
                                    // Create booking item view
                                    val bookingView = layoutInflater.inflate(
                                        R.layout.item_active_booking, 
                                        binding.bookingsContainer, 
                                        false
                                    )
                                    
                                    // Set data in the view
                                    val nameTextView = bookingView.findViewById<TextView>(R.id.bookingSpotName)
                                    val timeTextView = bookingView.findViewById<TextView>(R.id.bookingTime)
                                    val endTextView = bookingView.findViewById<TextView>(R.id.bookingEndTime)
                                    val navigateButton = bookingView.findViewById<Button>(R.id.navigateButton)
                                    
                                    // Format the text nicely
                                    nameTextView.text = spotName
                                    
                                    // Show current date for reference
                                    val isToday = isSameDay(startTime.toDate(), Date())
                                    
                                    // Format the time text
                                    val timeFormat = SimpleDateFormat("EEE, MMM dd • HH:mm", Locale.getDefault())
                                    timeTextView.text = "From: ${timeFormat.format(startTime.toDate())}"
                                    endTextView.text = "Until: ${timeFormat.format(endTime.toDate())}"
                                    
                                    // Set up navigation button click listener
                                    navigateButton.setOnClickListener {
                                        // Get the parking spot details from Firestore
                                        db.collection("parking_spots")
                                            .whereEqualTo("name", spotName)
                                            .get()
                                            .addOnSuccessListener { documents ->
                                                if (!documents.isEmpty) {
                                                    val spot = documents.documents[0]
                                                    val latitude = spot.getDouble("latitude")
                                                    val longitude = spot.getDouble("longitude")
                                                    
                                                    if (latitude != null && longitude != null) {
                                                        NavigationUtils.navigateToSpot(
                                                            this@MainActivity,
                                                            latitude,
                                                            longitude,
                                                            spotName
                                                        )
                                                    } else {
                                                        Toast.makeText(
                                                            this@MainActivity,
                                                            "Location information not available",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                } else {
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "Parking spot not found",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                            .addOnFailureListener { e ->
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Failed to get location: ${e.message}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                    }
                                    
                                    // Add click listener to view details
                                    bookingView.setOnClickListener {
                                        val intent = Intent(this@MainActivity, ReceiptActivity::class.java)
                                        intent.putExtra("bookingId", bookingId)
                                        startActivity(intent)
                                    }
                                    
                                    // Add the booking view to the container
                                    binding.bookingsContainer.addView(bookingView)
                                    android.util.Log.d("MainActivity", "Added booking view for: $bookingId")
                                } catch (e: Exception) {
                                    android.util.Log.e("MainActivity", "Error creating booking view: ${e.message}", e)
                                    Toast.makeText(this, "Error displaying booking: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        
                        // Show "View all" button if there are bookings
                        binding.btnViewAllBookings.visibility = View.VISIBLE
                        binding.btnViewAllBookings.setOnClickListener {
                            startActivity(Intent(this, BookingHistoryActivity::class.java))
                        }
                    }
                }
                .addOnFailureListener { e ->
                    binding.bookingsProgressBar.visibility = View.GONE
                    binding.activeBookingsCard.visibility = View.VISIBLE
                    binding.noBookingsText.visibility = View.VISIBLE
                    binding.noBookingsText.text = "Failed to load bookings. Tap to retry."
                    binding.noBookingsText.setOnClickListener {
                        // Retry loading
                        loadActiveBookings()
                    }
                    
                    android.util.Log.e("MainActivity", "Error loading bookings: ${e.message}", e)
                    Toast.makeText(this, "Failed to load active bookings: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            binding.bookingsProgressBar.visibility = View.GONE
            binding.activeBookingsCard.visibility = View.VISIBLE
            binding.noBookingsText.visibility = View.VISIBLE
            binding.noBookingsText.text = "Error: ${e.message}. Tap to retry."
            binding.noBookingsText.setOnClickListener {
                // Retry loading
                loadActiveBookings()
            }
            
            android.util.Log.e("MainActivity", "Exception in loadActiveBookings: ${e.message}", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Check if two dates are on the same day
     */
    private fun isSameDay(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH) &&
               cal1.get(Calendar.DAY_OF_MONTH) == cal2.get(Calendar.DAY_OF_MONTH)
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh active bookings when returning to the app
        if (firebaseAuth.currentUser != null) {
            loadActiveBookings()
        }
    }
    
    /**
     * Checks if Google Play Services are available and up to date.
     * If not, it shows a dialog to resolve the issue if possible.
     */
    private fun checkPlayServicesAvailability() {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
        
        if (resultCode != ConnectionResult.SUCCESS) {
            if (googleApiAvailability.isUserResolvableError(resultCode)) {
                // Show dialog to resolve the error
                googleApiAvailability.getErrorDialog(
                    this, 
                    resultCode,
                    PLAY_SERVICES_RESOLUTION_REQUEST
                )?.show()
            } else {
                // Error is not resolvable, inform the user
                Toast.makeText(
                    this,
                    "This device doesn't support Google Play Services, some features may not work properly",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == PLAY_SERVICES_RESOLUTION_REQUEST) {
            // Check if the user resolved the issue
            checkPlayServicesAvailability()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_subscription -> {
                startActivity(Intent(this, SubscriptionActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
