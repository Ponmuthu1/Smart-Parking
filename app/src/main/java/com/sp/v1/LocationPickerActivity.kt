package com.sp.v1

import android.app.Activity
import android.content.Intent
import android.location.Geocoder
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import java.io.IOException
import java.util.Locale

/**
 * A dedicated activity for picking a location on Google Maps or entering coordinates manually.
 * This provides a more reliable experience than using MapsActivity in location picker mode.
 */
class LocationPickerActivity : AppCompatActivity(), OnMapReadyCallback {
    
    private var googleMap: GoogleMap? = null
    private var selectedLocation: LatLng? = null
    
    private lateinit var mapFragment: SupportMapFragment
    private lateinit var mapCardView: CardView
    private lateinit var manualEntryCard: CardView
    private lateinit var confirmButton: Button
    private lateinit var searchButton: Button
    private lateinit var etLatitude: EditText
    private lateinit var etLongitude: EditText
    private lateinit var etAddressSearch: EditText
    private lateinit var tvMapStatus: TextView
    private lateinit var progressBar: ProgressBar
    
    private val TAG = "LocationPickerActivity"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_picker)
        
        // Initialize views
        mapCardView = findViewById(R.id.mapCardView)
        manualEntryCard = findViewById(R.id.manualEntryCard)
        confirmButton = findViewById(R.id.btnConfirmLocation)
        searchButton = findViewById(R.id.btnSearchAddress)
        etLatitude = findViewById(R.id.etLatitude)
        etLongitude = findViewById(R.id.etLongitude)
        etAddressSearch = findViewById(R.id.etAddressSearch)
        tvMapStatus = findViewById(R.id.tvMapStatus)
        progressBar = findViewById(R.id.progressBar)
        
        // Set the title
        supportActionBar?.title = "Pick Location"
        
        // Try to load Google Maps
        if (checkGooglePlayServicesAvailability()) {
            initGoogleMap()
        } else {
            // Google Play Services not available, show only manual entry
            showManualEntryOnly("Google Maps is not available on this device")
        }
        
        // Set up text change listeners
        setupCoordinateChangeListeners()
        
        // Set up button click listeners
        setupButtonListeners()
    }
    
    private fun initGoogleMap() {
        try {
            mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
            mapFragment.getMapAsync(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing map: ${e.message}")
            showManualEntryOnly("Error initializing map: ${e.message}")
        }
    }
    
    private fun setupCoordinateChangeListeners() {
        // Update the map when coordinates change
        val coordinateWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                try {
                    val lat = etLatitude.text.toString().toDoubleOrNull()
                    val lng = etLongitude.text.toString().toDoubleOrNull()
                    
                    if (lat != null && lng != null && 
                        lat >= -90 && lat <= 90 && 
                        lng >= -180 && lng <= 180) {
                        
                        selectedLocation = LatLng(lat, lng)
                        updateMapWithLocation(selectedLocation)
                        confirmButton.isEnabled = true
                    } else {
                        confirmButton.isEnabled = false
                    }
                } catch (e: Exception) {
                    confirmButton.isEnabled = false
                }
            }
        }
        
        etLatitude.addTextChangedListener(coordinateWatcher)
        etLongitude.addTextChangedListener(coordinateWatcher)
    }
    
    private fun setupButtonListeners() {
        // Confirm button
        confirmButton.setOnClickListener {
            returnSelectedLocation()
        }
        
        // Search address button
        searchButton.setOnClickListener {
            val address = etAddressSearch.text.toString().trim()
            if (address.isNotEmpty()) {
                progressBar.visibility = View.VISIBLE
                searchAddressAndUpdateMap(address)
            } else {
                Toast.makeText(this, "Please enter an address to search", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun returnSelectedLocation() {
        if (selectedLocation != null) {
            val resultIntent = Intent()
            resultIntent.putExtra("latitude", selectedLocation!!.latitude)
            resultIntent.putExtra("longitude", selectedLocation!!.longitude)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } else {
            Toast.makeText(this, "Please select a location first", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun searchAddressAndUpdateMap(address: String) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())
            
            Thread {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        // Use the new API for Android 13+
                        geocoder.getFromLocationName(address, 1) { addresses ->
                            runOnUiThread {
                                progressBar.visibility = View.GONE
                                
                                if (addresses.isNotEmpty()) {
                                    val location = addresses[0]
                                    val latLng = LatLng(location.latitude, location.longitude)
                                    
                                    // Update UI
                                    etLatitude.setText(latLng.latitude.toString())
                                    etLongitude.setText(latLng.longitude.toString())
                                    selectedLocation = latLng
                                    updateMapWithLocation(latLng)
                                    confirmButton.isEnabled = true
                                } else {
                                    Toast.makeText(this, "Address not found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        // Use the old API
                        val addresses = geocoder.getFromLocationName(address, 1)
                        
                        runOnUiThread {
                            progressBar.visibility = View.GONE
                            
                            if (addresses != null && addresses.isNotEmpty()) {
                                val location = addresses[0]
                                val latLng = LatLng(location.latitude, location.longitude)
                                
                                // Update UI
                                etLatitude.setText(latLng.latitude.toString())
                                etLongitude.setText(latLng.longitude.toString())
                                selectedLocation = latLng
                                updateMapWithLocation(latLng)
                                confirmButton.isEnabled = true
                            } else {
                                Toast.makeText(this, "Address not found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Geocoding error: ${e.message}")
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        Toast.makeText(this, "Error finding location: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
            
        } catch (e: Exception) {
            progressBar.visibility = View.GONE
            Log.e(TAG, "Error in geocoding: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        
        // Show map is ready
        mapCardView.visibility = View.VISIBLE
        tvMapStatus.visibility = View.GONE
        
        // Set initial camera position (centered on India)
        val defaultLocation = LatLng(20.5937, 78.9629) // Center of India
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 5f))
        
        // Set click listener on map
        googleMap?.setOnMapClickListener { latLng ->
            selectedLocation = latLng
            
            // Update manual entry fields
            etLatitude.setText(latLng.latitude.toString())
            etLongitude.setText(latLng.longitude.toString())
            
            // Update map
            updateMapWithLocation(latLng)
            
            // Enable confirm button
            confirmButton.isEnabled = true
        }
    }
    
    private fun updateMapWithLocation(latLng: LatLng?) {
        latLng?.let {
            googleMap?.clear()
            googleMap?.addMarker(MarkerOptions().position(it).title("Selected Location"))
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 15f))
        }
    }
    
    private fun showManualEntryOnly(message: String) {
        // Hide map card and show manual entry
        mapCardView.visibility = View.GONE
        manualEntryCard.visibility = View.VISIBLE
        tvMapStatus.visibility = View.VISIBLE
        tvMapStatus.text = message
        
        // Adjust layout
        val params = manualEntryCard.layoutParams
        params.height = resources.getDimensionPixelSize(R.dimen.manual_entry_expanded_height)
        manualEntryCard.layoutParams = params
    }
    
    private fun checkGooglePlayServicesAvailability(): Boolean {
        try {
            val googleApiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
            
            if (resultCode != ConnectionResult.SUCCESS) {
                if (googleApiAvailability.isUserResolvableError(resultCode)) {
                    googleApiAvailability.getErrorDialog(this, resultCode, 9001)?.show()
                }
                Log.d(TAG, "Google Play Services not available, code: $resultCode")
                return false
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Google Play Services: ${e.message}")
            return false
        }
    }
} 