package com.sp.v1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import java.util.*
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import android.view.ViewGroup
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import com.sp.v1.utils.NavigationUtils
import android.widget.RatingBar
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.EditText



class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private var userLocation: LatLng? = null
    private lateinit var focusButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var mapContainer: View
    
    // Navigation parameters
    private var targetLatitude: Double = 0.0
    private var targetLongitude: Double = 0.0
    private var targetSpotName: String? = null
    private var shouldNavigateToTarget: Boolean = false
    
    // List to hold parking spots loaded from Firestore.
    private val parkingSpotsList: MutableList<ParkingSpot> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        // Get view references (verify these IDs exist in your activity_maps.xml)
        mapContainer = findViewById(R.id.map_container)
        progressBar = findViewById(R.id.progress_loading)
        focusButton = findViewById(R.id.btn_focus_location)
        focusButton.setOnClickListener { showNearestParkingSpots() }
        
        targetLatitude = intent.getDoubleExtra("latitude", 0.0)
        targetLongitude = intent.getDoubleExtra("longitude", 0.0)
        targetSpotName = intent.getStringExtra("spotName")
        shouldNavigateToTarget = intent.getBooleanExtra("navigate", false)

        // Check if Google Play Services is available before initializing map
        if (checkGooglePlayServicesAvailability()) {
            try {
                // Initialize the Fused Location Client.
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

                // Set up the map fragment.
                val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
                if (mapFragment != null) {
                    mapFragment.getMapAsync(this)
                } else {
                    Log.e("MapsActivity", "Map fragment not found")
                    showFallbackUI()
                }
            } catch (e: Exception) {
                Log.e("MapsActivity", "Error initializing map: ${e.message}", e)
                showFallbackUI()
            }
        } else {
            // Google Play Services not available, show fallback UI
            Log.w("MapsActivity", "Google Play Services not available")
            showFallbackUI()
        }

        val allSpotsButton: Button = findViewById(R.id.btn_all_spots)
        allSpotsButton.setOnClickListener {
            startActivity(Intent(this, AllParkingSpots::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // If returning from settings and we still don't have a location, re-check.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED && userLocation == null) {
            enableMyLocation()
        }
        
        // Trigger the application-wide availability check
        (application as ParkingApplication).checkAndUpdateSpotAvailability()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true

        enableMyLocation()
        
        // Load parking spots
        googleMap.setOnMapLoadedCallback { loadParkingSpots() }

        // When a marker is clicked, show options
        googleMap.setOnMarkerClickListener { marker ->
            // Find the corresponding parking spot
            val spot = parkingSpotsList.find { it.name == marker.title }
            if (spot != null) {
                showSpotOptionsBottomSheet(spot)
            }
            true
        }
        
        // Check if we should navigate to a specific spot
        if (shouldNavigateToTarget && targetLatitude != 0.0 && targetLongitude != 0.0) {
            val targetLocation = LatLng(targetLatitude, targetLongitude)
            
            // Add a marker for the target spot
            val marker = MarkerOptions()
                .position(targetLocation)
                .title(targetSpotName ?: "Parking Spot")
            googleMap.addMarker(marker)
            
            // Animate camera to the target location
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(targetLocation, 15f))
            
            // If requested to navigate, initiate navigation
            if (shouldNavigateToTarget) {
                Toast.makeText(this, "Navigating to $targetSpotName", Toast.LENGTH_SHORT).show()
                showRoute(targetLocation)
            }
        }
    }

    // Enable My Location feature with permission and GPS check.
    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            // Check if permission has been permanently denied (Don't ask again).
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                Toast.makeText(this, "Location permission denied permanently. Please enable it in settings.", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
            return
        }

        // Build location request.
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateDistanceMeters(5f)
            .build()

        // Build settings request for checking GPS.
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .build()

        val settingsClient = LocationServices.getSettingsClient(this)
        settingsClient.checkLocationSettings(settingsRequest)
            .addOnSuccessListener {
                // GPS is enabled.
                googleMap.isMyLocationEnabled = true
                getCurrentLocation()
            }
            .addOnFailureListener {
                Toast.makeText(this, "GPS is turned off, please enable it.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
    }

    // Get current location; if not available, request a new update.
    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                userLocation = LatLng(location.latitude, location.longitude)
                val loc = userLocation // Local immutable copy for safe smart cast.
                if (loc != null) {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 14f))
                    mapContainer.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                }
            } else {
                Toast.makeText(this, "Last location not available, requesting update...", Toast.LENGTH_SHORT).show()
                requestNewLocationData()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get location", Toast.LENGTH_SHORT).show()
        }
    }

    // Request a fresh location update if lastLocation is null.
    private fun requestNewLocationData() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateDistanceMeters(5f)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val location = locationResult.lastLocation
                    if (location != null) {
                        userLocation = LatLng(location.latitude, location.longitude)
                        val loc = userLocation
                        if (loc != null) {
                            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 14f))
                            mapContainer.visibility = View.VISIBLE
                            progressBar.visibility = View.GONE
                        }
                    }
                    fusedLocationClient.removeLocationUpdates(this)
                }
            },
            null
        )
    }

    // Show route: Check if Google Maps is installed before launching navigation.
    private fun showRoute(destination: LatLng) {
        NavigationUtils.navigateToSpot(this, destination.latitude, destination.longitude)
    }

    // Load parking spots from Firestore; add markers and store them in the local list.
    private fun loadParkingSpots() {
        db.collection("parking_spots").get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    Toast.makeText(this, "No parking spots found!", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                
                // Clear map and list
                googleMap.clear()
                parkingSpotsList.clear()
                
                // Temporary list for spots before availability check
                val tempSpots = mutableListOf<ParkingSpot>()
                
                for (doc in documents) {
                    val lat = doc.getDouble("latitude")
                    val lng = doc.getDouble("longitude")
                    val name = doc.getString("name")
                    
                    // Handle price_per_hour that could be a string or number in Firestore
                    val price = try {
                        // Get price as string and convert to double
                        doc.getString("price_per_hour")?.toDoubleOrNull() ?: 0.0
                    } catch (e: Exception) {
                        Log.e("MapsActivity", "Error parsing price: ${e.message}")
                        0.0
                    }
                    
                    // Get rating fields
                    val averageRating = doc.getDouble("averageRating") ?: 0.0
                    val ratingCount = doc.getLong("ratingCount")?.toInt() ?: 0
                    
                    if (lat != null && lng != null && name != null) {
                        val spot = ParkingSpot(
                            name = name,
                            latitude = lat,
                            longitude = lng,
                            price_per_hour = price,
                            address = doc.getString("address") ?: "",
                            is_available = doc.getBoolean("is_available") ?: true,
                            owner_upi = doc.getString("owner_upi") ?: "",
                            averageRating = averageRating,
                            ratingCount = ratingCount,
                            bookedTimeSlots = mutableListOf()
                        )
                        tempSpots.add(spot)
                    }
                }
                
                // Check current bookings to update availability
                checkCurrentBookings(tempSpots)
            }
            .addOnFailureListener { e ->
                println("Firestore error: ${e.message}")
                Toast.makeText(this, "Failed to load parking spots: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
    
    private fun checkCurrentBookings(spots: List<ParkingSpot>) {
        // Get current time
        val currentTime = Timestamp.now()
        Log.d("MapsActivity", "Checking current bookings at ${Date(currentTime.seconds * 1000)}")
        
        // Query ALL active and upcoming bookings
        db.collection("bookings")
            .whereIn("status", listOf("pending", "completed"))
            .get()
            .addOnSuccessListener { bookingDocs ->
                Log.d("MapsActivity", "Found ${bookingDocs.size()} active/pending bookings to process")
                
                // Maps to track which spots are booked for different time periods
                val currentlyBookedSpots = mutableMapOf<String, Boolean>() // Spots booked right now
                val hasBookingsSpots = mutableMapOf<String, Boolean>() // Spots that have any future bookings
                
                // Map to track bookings for each spot
                val spotBookings = mutableMapOf<String, MutableList<BookedTimeSlot>>()
                
                for (doc in bookingDocs) {
                    val bookingId = doc.id
                    val startTime = doc.getTimestamp("startTime")
                    val endTime = doc.getTimestamp("endTime")
                    val spotId = doc.getString("parkingSpotId") ?: continue
                    
                    // Add this booking to the spot's list of booked time slots
                    if (startTime != null && endTime != null) {
                        val timeSlot = BookedTimeSlot(bookingId, startTime, endTime)
                        
                        // Initialize the list if it doesn't exist yet
                        if (!spotBookings.containsKey(spotId)) {
                            spotBookings[spotId] = mutableListOf()
                        }
                        
                        // Add the booking to the spot's list
                        spotBookings[spotId]?.add(timeSlot)
                        
                        // Debug log
                        Log.d("MapsActivity", "Booking $bookingId for spot $spotId: " +
                                "Start=${Date(startTime.seconds * 1000)}, End=${Date(endTime.seconds * 1000)}")
                        
                        // Check if this booking is active now (current time is between start and end)
                        val isCurrentlyActive = currentTime.seconds >= startTime.seconds && 
                                               currentTime.seconds <= endTime.seconds
                        
                        if (isCurrentlyActive) {
                            Log.d("MapsActivity", "Booking $bookingId is currently active")
                            currentlyBookedSpots[spotId] = true
                        }
                        
                        // Also mark spots with any future bookings
                        if (endTime.seconds > currentTime.seconds) {
                            hasBookingsSpots[spotId] = true
                        }
                    }
                }
                
                // Update availability status based on bookings and add to map
                for (spot in spots) {
                    // Check current database value for availability
                    val wasAvailableInDB = spot.is_available
                    
                    // A spot is considered available if:
                    // 1. It is marked as available in the database
                    // 2. It's not currently booked
                    val shouldBeAvailable = !currentlyBookedSpots.containsKey(spot.name)
                    spot.is_available = shouldBeAvailable
                    
                    // Add a visual indicator for spots with future bookings
                    if (hasBookingsSpots.containsKey(spot.name)) {
                        spot.has_future_bookings = true
                    } else {
                        spot.has_future_bookings = false
                    }
                    
                    // Add the booked time slots to the parking spot
                    spotBookings[spot.name]?.let { timeSlots ->
                        spot.bookedTimeSlots.clear()
                        spot.bookedTimeSlots.addAll(timeSlots)
                    }
                    
                    // Update the availability in Firestore if it has changed
                    // This is the key fix: update spots that should be available but are marked unavailable
                    if (wasAvailableInDB != shouldBeAvailable) {
                        // Spot availability in DB doesn't match what it should be
                        updateSpotAvailabilityInFirestore(spot.name, shouldBeAvailable)
                        Log.d("MapsActivity", "Updating spot ${spot.name} availability to $shouldBeAvailable")
                    }
                    
                    parkingSpotsList.add(spot)
                    
                    // Add marker with color based on availability
                    val spotLatLng = LatLng(spot.latitude, spot.longitude)
                    val marker = MarkerOptions()
                        .position(spotLatLng)
                        .title(spot.name)
                        .snippet(getAvailabilitySnippet(spot))
                        .icon(
                            BitmapDescriptorFactory.defaultMarker(
                                when {
                                    !spot.is_available -> BitmapDescriptorFactory.HUE_RED
                                    spot.has_future_bookings -> BitmapDescriptorFactory.HUE_ORANGE
                                    else -> BitmapDescriptorFactory.HUE_BLUE
                                }
                            )
                        )
                    googleMap.addMarker(marker)
                }
            }
            .addOnFailureListener { e ->
                // If we fail to check bookings, still show the spots with their database availability
                for (spot in spots) {
                    parkingSpotsList.add(spot)
                    
                    // Add marker
                    val spotLatLng = LatLng(spot.latitude, spot.longitude)
                    googleMap.addMarker(
                        MarkerOptions()
                            .position(spotLatLng)
                            .title(spot.name)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                    )
                }
                
                Toast.makeText(this, "Failed to check current bookings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
    
    /**
     * Updates the availability status of a parking spot in Firestore
     */
    private fun updateSpotAvailabilityInFirestore(spotId: String, isAvailable: Boolean) {
        db.collection("parking_spots")
            .whereEqualTo("name", spotId)
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    Log.e("MapsActivity", "No parking spot found with ID: $spotId")
                    return@addOnSuccessListener
                }
                
                // Found the parking spot, update its availability
                val spotDoc = documents.documents[0]
                db.collection("parking_spots")
                    .document(spotDoc.id)
                    .update("is_available", isAvailable)
                    .addOnSuccessListener {
                        Log.d("MapsActivity", "Updated parking spot $spotId availability: $isAvailable")
                    }
                    .addOnFailureListener { e ->
                        Log.e("MapsActivity", "Failed to update spot $spotId availability: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                Log.e("MapsActivity", "Error finding parking spot to update: ${e.message}")
            }
    }
    
    private fun getAvailabilitySnippet(spot: ParkingSpot): String {
        return when {
            !spot.is_available -> {
                // Show when it will become available again
                val nextAvailable = spot.getNextAvailableTime(Date())
                val timeStr = if (nextAvailable != null) {
                    // Format with appropriate time reference
                    if (isToday(nextAvailable)) {
                        // Today - show as "Today at HH:mm"
                        "Today at " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextAvailable)
                    } else if (isTomorrow(nextAvailable)) {
                        // Tomorrow - show as "Tomorrow at HH:mm"
                        "Tomorrow at " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextAvailable)
                    } else {
                        // Some other day - show day and time
                        SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(nextAvailable)
                    }
                } else {
                    "not determined"
                }
                "Booked • Available: $timeStr"
            }
            spot.has_future_bookings -> {
                // Get the next available time
                val nextAvailable = spot.getNextAvailableTime(Date())
                val formattedTime = if (nextAvailable != null) {
                    // Format with appropriate time reference
                    if (isToday(nextAvailable)) {
                        // Today - show as "Today at HH:mm"
                        "Today at " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextAvailable)
                    } else if (isTomorrow(nextAvailable)) {
                        // Tomorrow - show as "Tomorrow at HH:mm"
                        "Tomorrow at " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextAvailable)
                    } else {
                        // Some other day - show day and time
                        SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(nextAvailable)
                    }
                } else {
                    "available soon"
                }
                
                "Limited Availability\nFull slots starting: $formattedTime"
            }
            else -> "Fully Available"
        }
    }

    /**
     * Checks if the given date is today
     */
    private fun isToday(date: Date): Boolean {
        val today = Calendar.getInstance()
        val cal = Calendar.getInstance()
        cal.time = date
        
        return today.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
               today.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Checks if the given date is tomorrow
     */
    private fun isTomorrow(date: Date): Boolean {
        val tomorrow = Calendar.getInstance()
        tomorrow.add(Calendar.DAY_OF_YEAR, 1)
        
        val cal = Calendar.getInstance()
        cal.time = date
        
        return tomorrow.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
               tomorrow.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    }

    // When the "My Location" button is clicked, calculate the three nearest parking spots and show them.
    private fun showNearestParkingSpots() {
        val currentLoc = userLocation
        if (currentLoc == null) {
            Toast.makeText(this, "User location not available", Toast.LENGTH_SHORT).show()
            return
        }
        if (parkingSpotsList.isEmpty()) {
            Toast.makeText(this, "No parking spots loaded", Toast.LENGTH_SHORT).show()
            return
        }
        // Calculate the distance from the current location for each parking spot.
        for (spot in parkingSpotsList) {
            val result = FloatArray(1)
            Location.distanceBetween(
                currentLoc.latitude,
                currentLoc.longitude,
                spot.latitude,
                spot.longitude,
                result
            )
            spot.distance = result[0]
        }
        // Sort by distance and get the three nearest spots.
        val nearestThree = parkingSpotsList.sortedBy { it.distance }.take(3)
        showNearestSpotsBottomSheet(nearestThree)
    }

    // Display a BottomSheetDialog listing the nearest parking spots using the bottom_sheet_parking.xml layout.
    private fun showNearestSpotsBottomSheet(spots: List<ParkingSpot>) {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_parking, null)
        bottomSheetDialog.setContentView(view)

        val container = view.findViewById<LinearLayout>(R.id.bottomSheetContainer)
        container.removeAllViews()

        for (spot in spots) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.parking_spot_item, container, false)
            val text1 = itemView.findViewById<TextView>(R.id.text1)
            val text2 = itemView.findViewById<TextView>(R.id.text2)
            val bookButton = itemView.findViewById<Button>(R.id.btn_book)
            val navigateButton = itemView.findViewById<Button>(R.id.btn_navigate)
            val ratingBar = itemView.findViewById<RatingBar>(R.id.ratingBarSpot)
            val ratingCount = itemView.findViewById<TextView>(R.id.tvRatingCount)

            text1.text = spot.name
            
            // Set rating display
            ratingBar.rating = spot.averageRating.toFloat()
            ratingCount.text = "(${spot.ratingCount})"
            
            // Display distance and availability status
            val statusText = when {
                !spot.is_available -> {
                    "\nBooked"
                }
                spot.has_future_bookings -> {
                    // Get the next available time
                    val nextAvailable = spot.getNextAvailableTime(Date())
                    val formattedTime = nextAvailable?.let { 
                        java.text.SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(it)
                    } ?: "unknown time"
                    
                    "\nHas future bookings\nNext available: $formattedTime"
                }
                else -> ""
            }
            
            text2.text = if (spot.distance < 1000) {
                "${spot.address}\nPrice: ₹${spot.price_per_hour}/hour\nDistance: ${spot.distance.toInt()} m$statusText"
            } else {
                "${spot.address}\nPrice: ₹${spot.price_per_hour}/hour\nDistance: ${String.format("%.1f km", spot.distance / 1000)}$statusText"
            }
            
            // Always keep the Book button enabled, only change visual appearance
            if (!spot.is_available) {
                    bookButton.text = "Booked"
                bookButton.alpha = 0.7f
                bookButton.setBackgroundColor(android.graphics.Color.RED)
            } else if (spot.has_future_bookings) {
                bookButton.text = "Book (Limited)"
                bookButton.setBackgroundColor(android.graphics.Color.rgb(255, 165, 0)) // Orange
            } else {
                bookButton.text = "Book"
                bookButton.alpha = 1.0f
            }

            bookButton.setOnClickListener {
                try {
                    val intent = Intent(this@MapsActivity, BookingActivity::class.java)
                    intent.putExtra("parkingSpot", spot)
                    startActivity(intent)
                    bottomSheetDialog.dismiss()
                } catch (e: Exception) {
                    Log.e("MapsActivity", "Error starting booking activity: ${e.message}", e)
                    Toast.makeText(this@MapsActivity, "Error starting booking: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            navigateButton.setOnClickListener {
                val destination = LatLng(spot.latitude, spot.longitude)
                NavigationUtils.navigateToSpot(this@MapsActivity, destination.latitude, destination.longitude, spot.name)
                bottomSheetDialog.dismiss()
            }

            container.addView(itemView)
        }
        bottomSheetDialog.show()
    }

    private fun createBooking(spot: ParkingSpot) {
        // Create a new booking document
        val booking = hashMapOf(
            "parkingSpotId" to spot.name,
            "parkingSpotName" to spot.name,
            "startTime" to Timestamp.now(),
            "endTime" to Timestamp(Date(System.currentTimeMillis() + 3600000)), // 1 hour from now
            "totalAmount" to spot.price_per_hour,
            "status" to "pending",
            "createdAt" to Timestamp.now()
        )

        db.collection("bookings")
            .add(booking)
            .addOnSuccessListener { documentReference ->
                Toast.makeText(
                    this,
                    "Booking created successfully! Booking ID: ${documentReference.id}",
                    Toast.LENGTH_LONG
                ).show()
                // Refresh the parking spots list
                loadParkingSpots()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Failed to create booking: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    /**
     * Checks if Google Play Services is available and up to date
     * @return true if available and up to date, false otherwise
     */
    private fun checkGooglePlayServicesAvailability(): Boolean {
        try {
            // Get Google Play Services status
            val googleApiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
            
            if (resultCode != ConnectionResult.SUCCESS) {
                Log.e("MapsActivity", "Google Play Services not available, result code: $resultCode")
                
                if (googleApiAvailability.isUserResolvableError(resultCode)) {
                    // Show dialog to resolve the error
                    try {
                        // Use getErrorDialog instead of directly showing to avoid potential crashes
                        val dialog = googleApiAvailability.getErrorDialog(this, resultCode, 9001)
                        if (dialog != null) {
                            dialog.show()
                        } else {
                            Toast.makeText(
                                this,
                                "Google Play Services needs to be updated. Using fallback mode.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        Log.e("MapsActivity", "Failed to show Google Play Services error dialog: ${e.message}", e)
                        Toast.makeText(
                            this,
                            "Google Play Services error. Using fallback mode.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this,
                        "This device doesn't support Google Play Services. Using fallback mode.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                
                // Set application-wide flag
                ParkingApplication.googlePlayServicesAvailable = false
                return false
            }
            
            // Services are available
            ParkingApplication.googlePlayServicesAvailable = true
            return true
        } catch (e: Exception) {
            // Handle any exceptions that might occur
            Log.e("MapsActivity", "Error checking Google Play Services: ${e.message}", e)
            Toast.makeText(
                this,
                "Error with Google Play Services. Using fallback mode.",
                Toast.LENGTH_LONG
            ).show()
            
            // Set application-wide flag
            ParkingApplication.googlePlayServicesAvailable = false
            return false
        }
    }
    
    /**
     * Shows a fallback UI when Google Maps is not available
     */
    private fun showFallbackUI() {
        // Hide map container and show a fallback UI
        mapContainer.visibility = View.GONE
        progressBar.visibility = View.GONE
        
        // Create a simple UI for location input
        val layout = RelativeLayout(this)
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        
        val scrollView = ScrollView(this)
        scrollView.layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        container.setPadding(32, 32, 32, 32)
        
        // Add title
        val titleText = TextView(this)
        titleText.text = "Maps Unavailable"
        titleText.textSize = 20f
        titleText.setPadding(0, 0, 0, 32)
        container.addView(titleText)
        
        // Message about fallback mode
        val messageText = TextView(this)
        messageText.text = "Google Maps is currently unavailable. Please try again later or contact support if the issue persists."
        messageText.setPadding(0, 0, 0, 32)
        container.addView(messageText)
        
        // Add a button to retry
        val retryButton = Button(this)
        retryButton.text = "Retry"
        retryButton.setOnClickListener {
            recreate() // Restart the activity to try again
        }
        container.addView(retryButton)
        
        // Add a button to go back
        val backButton = Button(this)
        backButton.text = "Go Back"
        backButton.setOnClickListener {
            finish()
        }
        container.addView(backButton)
        
        scrollView.addView(container)
        layout.addView(scrollView)
        
        // Add the fallback UI to the activity
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.removeAllViews()
        rootView.addView(layout)
    }

    // Show options for a parking spot when its marker is clicked
    private fun showSpotOptionsBottomSheet(spot: ParkingSpot) {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_parking, null)
        bottomSheetDialog.setContentView(view)

        val container = view.findViewById<LinearLayout>(R.id.bottomSheetContainer)
        container.removeAllViews()

        val itemView = LayoutInflater.from(this).inflate(R.layout.parking_spot_item, container, false)
        val text1 = itemView.findViewById<TextView>(R.id.text1)
        val text2 = itemView.findViewById<TextView>(R.id.text2)
        val bookButton = itemView.findViewById<Button>(R.id.btn_book)
        val navigateButton = itemView.findViewById<Button>(R.id.btn_navigate)
        val ratingBar = itemView.findViewById<RatingBar>(R.id.ratingBarSpot)
        val ratingCount = itemView.findViewById<TextView>(R.id.tvRatingCount)

        text1.text = spot.name
        
        // Set rating
        ratingBar.rating = spot.averageRating.toFloat()
        ratingCount.text = "(${spot.ratingCount})"
        
        // Display availability status
        val statusText = when {
            !spot.is_available -> {
                "\nCurrently booked"
            }
            spot.has_future_bookings -> {
                // Get the next available time
                val nextAvailable = spot.getNextAvailableTime(Date())
                val formattedTime = nextAvailable?.let { 
                    java.text.SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(it)
                } ?: "unknown time"
                
                "\nHas future bookings\nNext available: $formattedTime"
            }
            else -> ""
        }
        
        text2.text = "${spot.address}\nPrice: ₹${spot.price_per_hour}/hour$statusText"
        
        // Set button states based on availability
        if (!spot.is_available) {
              bookButton.text = "Booked"
            bookButton.setBackgroundColor(android.graphics.Color.RED)
        } else if (spot.has_future_bookings) {
            bookButton.text = "Book (Limited)"
            bookButton.setBackgroundColor(android.graphics.Color.rgb(255, 165, 0)) // Orange
        } else {
            bookButton.text = "Book"
            bookButton.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50")) // Green
        }

        // Set up click listeners
        bookButton.setOnClickListener {
            try {
                val intent = Intent(this@MapsActivity, BookingActivity::class.java)
                intent.putExtra("parkingSpot", spot)
                startActivity(intent)
                bottomSheetDialog.dismiss()
            } catch (e: Exception) {
                Log.e("MapsActivity", "Error starting booking activity: ${e.message}", e)
                Toast.makeText(this@MapsActivity, "Error starting booking: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        navigateButton.setOnClickListener {
            val destination = LatLng(spot.latitude, spot.longitude)
            NavigationUtils.navigateToSpot(this@MapsActivity, destination.latitude, destination.longitude, spot.name)
            bottomSheetDialog.dismiss()
        }

        container.addView(itemView)
        bottomSheetDialog.show()
    }

    /**
     * Handles exceptions that might occur during map operations and shows fallback UI if needed
     */
    private fun handleMapException(e: Exception, operation: String) {
        Log.e("MapsActivity", "Error during $operation: ${e.message}", e)
        Toast.makeText(this, "Unable to complete $operation. Using fallback mode.", Toast.LENGTH_SHORT).show()
        
        // For certain operations, we might want to show the fallback UI
        if (operation == "initialization" || operation == "loading") {
            showFallbackUI()
        }
    }
}
