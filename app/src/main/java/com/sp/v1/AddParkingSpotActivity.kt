package com.sp.v1

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.razorpay.Checkout
import com.razorpay.PaymentResultListener
import com.sp.v1.utils.CategoryUtils
import org.json.JSONObject
import java.util.*
import kotlin.math.roundToInt

class AddParkingSpotActivity : AppCompatActivity(), PaymentResultListener {
    private lateinit var etParkingSpotName: TextInputEditText
    private lateinit var etLatitude: TextInputEditText
    private lateinit var etLongitude: TextInputEditText
    private lateinit var etPricePerHour: TextInputEditText
    private lateinit var etOwnerUpi: TextInputEditText
    private lateinit var cbStandard: CheckBox
    private lateinit var cbEvCharging: CheckBox
    private lateinit var cbCoveredRoof: CheckBox
    private lateinit var cbOpenRoof: CheckBox
    private lateinit var cbValet: CheckBox
    private lateinit var cbHandicap: CheckBox
    private lateinit var cbSecure: CheckBox
    private lateinit var btnPickLocation: Button
    private lateinit var btnProceedToPayment: Button
    
    // List of all category checkboxes for easier handling
    private lateinit var categoryCheckboxes: List<CheckBox>
    
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    
    // Selected primary category - will be set to the first checked checkbox
    private var selectedPrimaryCategory = "Standard"
    
    // Fixed fee for adding a parking spot
    private val ADD_PARKING_SPOT_FEE = 2000
    
    // Transaction ID to track payment
    private var transactionId: String? = null
    
    private val TAG = "AddParkingSpotActivity"

    // Activity result launcher for location picking
    private val pickLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Get the latitude and longitude from the map activity
            val data = result.data
            val latitude = data?.getDoubleExtra("latitude", 0.0) ?: 0.0
            val longitude = data?.getDoubleExtra("longitude", 0.0) ?: 0.0
            
            // Update the text fields
            etLatitude.setText(latitude.toString())
            etLongitude.setText(longitude.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_parking_spot)
        
        // Initialize Firebase
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
        
        // Initialize UI components
        initializeViews()
        
        // Set click listeners
        setupClickListeners()
        
        // Preload Razorpay checkout
        Checkout.preload(applicationContext)
    }
    
    private fun initializeViews() {
        etParkingSpotName = findViewById(R.id.etParkingSpotName)
        etLatitude = findViewById(R.id.etLatitude)
        etLongitude = findViewById(R.id.etLongitude)
        etPricePerHour = findViewById(R.id.etPricePerHour)
        etOwnerUpi = findViewById(R.id.etOwnerUpi)
        
        // Initialize all checkboxes
        cbStandard = findViewById(R.id.cbStandard)
        cbEvCharging = findViewById(R.id.cbEvCharging)
        cbCoveredRoof = findViewById(R.id.cbCoveredRoof)
        cbOpenRoof = findViewById(R.id.cbOpenRoof)
        cbValet = findViewById(R.id.cbValet)
        cbHandicap = findViewById(R.id.cbHandicap)
        cbSecure = findViewById(R.id.cbSecure)
        
        btnPickLocation = findViewById(R.id.btnPickLocation)
        btnProceedToPayment = findViewById(R.id.btnProceedToPayment)
        
        // Group all checkboxes for easier handling
        categoryCheckboxes = listOf(
            cbStandard, cbEvCharging, cbCoveredRoof, cbOpenRoof, 
            cbValet, cbHandicap, cbSecure
        )
        
        // Set up category selection
        setupCategorySelection()
    }
    
    private fun setupCategorySelection() {
        // Standard is checked by default
        cbStandard.isChecked = true
        selectedPrimaryCategory = "Standard"
        
        // Add listeners to all checkboxes
        val checkboxListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            updatePrimaryCategory()
        }
        
        // Add the listener to each checkbox
        for (checkbox in categoryCheckboxes) {
            checkbox.setOnCheckedChangeListener(checkboxListener)
        }
    }
    
    /**
     * Updates the primary category based on checkboxes
     * The primary category is the first checked checkbox
     */
    private fun updatePrimaryCategory() {
        // Find the first checked checkbox
        val firstChecked = categoryCheckboxes.firstOrNull { it.isChecked }
        
        // If at least one is checked, update the primary category
        if (firstChecked != null) {
            selectedPrimaryCategory = firstChecked.text.toString()
        } else {
            // If none are checked, default to Standard and check it
            selectedPrimaryCategory = "Standard"
            cbStandard.isChecked = true
        }
    }
    
    private fun setupClickListeners() {
        btnPickLocation.setOnClickListener {
            // Open LocationPickerActivity instead of MapsActivity
            val intent = Intent(this, LocationPickerActivity::class.java)
            pickLocationLauncher.launch(intent)
        }
        
        btnProceedToPayment.setOnClickListener {
            if (validateInputs()) {
                // Confirm with dialog
                showConfirmationDialog()
            }
        }
    }
    
    private fun validateInputs(): Boolean {
        var isValid = true
        
        // Check name
        if (etParkingSpotName.text.toString().trim().isEmpty()) {
            etParkingSpotName.error = "Please enter a name"
            isValid = false
        }
        
        // Check latitude
        if (etLatitude.text.toString().trim().isEmpty()) {
            etLatitude.error = "Please enter latitude"
            isValid = false
        } else {
            try {
                val lat = etLatitude.text.toString().toDouble()
                // Latitude must be a number between -90 and 90
                if (!isValidLatitude(lat)) {
                    etLatitude.error = "Latitude must be between -90 and 90"
                    isValid = false
                }
            } catch (e: NumberFormatException) {
                etLatitude.error = "Invalid latitude format"
                isValid = false
            }
        }
        
        // Check longitude
        if (etLongitude.text.toString().trim().isEmpty()) {
            etLongitude.error = "Please enter longitude"
            isValid = false
        } else {
            try {
                val lng = etLongitude.text.toString().toDouble()
                // Longitude must be a number between -180 and 180
                if (!isValidLongitude(lng)) {
                    etLongitude.error = "Longitude must be between -180 and 180"
                    isValid = false
                }
            } catch (e: NumberFormatException) {
                etLongitude.error = "Invalid longitude format"
                isValid = false
            }
        }
        
        // Check price per hour
        if (etPricePerHour.text.toString().trim().isEmpty()) {
            etPricePerHour.error = "Please enter price per hour"
            isValid = false
        } else {
            try {
                val price = etPricePerHour.text.toString().toDouble()
                if (price <= 0) {
                    etPricePerHour.error = "Price must be greater than 0"
                    isValid = false
                }
            } catch (e: NumberFormatException) {
                etPricePerHour.error = "Invalid price format"
                isValid = false
            }
        }
        
        // Check UPI ID
        if (etOwnerUpi.text.toString().trim().isEmpty()) {
            etOwnerUpi.error = "Please enter UPI ID"
            isValid = false
        }
        
        // Check if at least one category is selected
        if (categoryCheckboxes.none { it.isChecked }) {
            Toast.makeText(this, "Please select at least one category", Toast.LENGTH_SHORT).show()
            isValid = false
        }
        
        return isValid
    }
    
    /**
     * Validates if a number is a valid latitude (-90 to 90)
     */
    private fun isValidLatitude(num: Double): Boolean {
        return num.isFinite() && Math.abs(num) <= 90
    }
    
    /**
     * Validates if a number is a valid longitude (-180 to 180)
     */
    private fun isValidLongitude(num: Double): Boolean {
        return num.isFinite() && Math.abs(num) <= 180
    }
    
    private fun showConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Confirm Addition")
            .setMessage("You are about to add a new parking spot with a fee of ₹$ADD_PARKING_SPOT_FEE. Do you want to proceed?")
            .setPositiveButton("Yes") { _, _ ->
                // Create a transaction record
                createTransaction()
            }
            .setNegativeButton("No", null)
            .show()
    }
    
    private fun createTransaction() {
        // Get current user ID
        val currentUserId = auth.currentUser?.uid
        
        if (currentUserId == null) {
            Toast.makeText(this, "You must be logged in to add a parking spot", Toast.LENGTH_LONG).show()
            return
        }
        
        // Create a transaction record
        val transaction = hashMapOf(
            "userId" to currentUserId,
            "amount" to ADD_PARKING_SPOT_FEE,
            "type" to "add_parking_spot",
            "status" to "pending",
            "createdAt" to Timestamp.now()
        )
        
        // Disable the payment button
        btnProceedToPayment.isEnabled = false
        btnProceedToPayment.text = "Processing..."
        
        // Add to Firestore
        db.collection("transactions")
            .add(transaction)
            .addOnSuccessListener { documentReference ->
                transactionId = documentReference.id
                // Start the payment process
                startPayment(ADD_PARKING_SPOT_FEE.toDouble(), documentReference.id)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to create transaction", e)
                Toast.makeText(this, "Failed to create transaction: ${e.message}", Toast.LENGTH_SHORT).show()
                // Re-enable the button
                btnProceedToPayment.isEnabled = true
                btnProceedToPayment.text = "Proceed to Payment (₹$ADD_PARKING_SPOT_FEE)"
            }
    }
    
    private fun startPayment(amount: Double, orderId: String) {
        // First check if Google Play Services is available as Razorpay depends on it
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
        
        if (resultCode != ConnectionResult.SUCCESS) {
            // Handle the Google Play Services issue
            handleGooglePlayServicesError(resultCode, orderId)
            return
        }
        
        val checkout = Checkout()
        checkout.setKeyID(getString(R.string.razorpay_key_id))
        
        try {
            // rounding off the amount to the nearest integer
            val amountInPaise = (amount * 100).roundToInt()
            
            val options = JSONObject()
            options.put("name", "Parking App")
            options.put("description", "Adding Parking Spot - ${etParkingSpotName.text}")
            options.put("theme.color", "#3399cc")
            options.put("currency", "INR")
            options.put("amount", amountInPaise)
            options.put("image", "https://s3.amazonaws.com/rzp-mobile/images/rzp.png")
            
            // Save the transaction ID in notes for reference
            val notes = JSONObject()
            notes.put("transaction_id", orderId)
            options.put("notes", notes)
            
            // Add retry options
            val retryObj = JSONObject()
            retryObj.put("enabled", true)
            retryObj.put("max_count", 4)
            options.put("retry", retryObj)
            
            // Add customer prefill info
            val prefill = JSONObject()
            auth.currentUser?.email?.let { prefill.put("email", it) }
            prefill.put("contact", "9999999999") // You should collect actual contact in a real app
            options.put("prefill", prefill)
            
            // Log for debugging
            Log.d(TAG, "Payment options: $options")
            
            // Open checkout form
            checkout.open(this, options)
        } catch (e: Exception) {
            Log.e(TAG, "Error in payment: ${e.message}", e)
            Toast.makeText(this, "Error in payment: ${e.message}", Toast.LENGTH_LONG).show()
            
            // Mark transaction as failed
            updateTransactionStatus(orderId, "failed", "Payment initialization failed: ${e.message}")
            
            // Re-enable the button
            btnProceedToPayment.isEnabled = true
            btnProceedToPayment.text = "Proceed to Payment (₹$ADD_PARKING_SPOT_FEE)"
        }
    }
    
    /**
     * Handles issues with Google Play Services during payment
     */
    private fun handleGooglePlayServicesError(resultCode: Int, orderId: String) {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        
        if (googleApiAvailability.isUserResolvableError(resultCode)) {
            // Show dialog to resolve the error
            googleApiAvailability.getErrorDialog(
                this, 
                resultCode,
                9000
            ) { 
                // Dialog was cancelled, update transaction as failed
                updateTransactionStatus(orderId, "cancelled", "Google Play Services error not resolved")
                Toast.makeText(this, "Payment cancelled: Google Play Services is required", Toast.LENGTH_LONG).show()
            }?.show()
        } else {
            // Error is not resolvable
            Toast.makeText(
                this,
                "Google Play Services are required for payment processing",
                Toast.LENGTH_LONG
            ).show()
            
            // Create a simple alert dialog to inform the user
            AlertDialog.Builder(this)
                .setTitle("Payment Not Available")
                .setMessage("Online payments require Google Play Services. Please install or update Google Play Services and try again.")
                .setPositiveButton("OK") { _, _ ->
                    // Update transaction as cancelled
                    updateTransactionStatus(orderId, "cancelled", "Payment cancelled - Google Play Services unavailable")
                }
                .setCancelable(false)
                .show()
        }
        
        // Re-enable the button
        btnProceedToPayment.isEnabled = true
        btnProceedToPayment.text = "Proceed to Payment (₹$ADD_PARKING_SPOT_FEE)"
    }
    
    /**
     * Updates the transaction status in Firestore
     */
    private fun updateTransactionStatus(transactionId: String, status: String, message: String = "") {
        val updates = hashMapOf<String, Any>(
            "status" to status,
            "updatedAt" to Timestamp.now()
        )
        
        if (message.isNotEmpty()) {
            updates["message"] = message
        }
        
        db.collection("transactions")
            .document(transactionId)
            .update(updates)
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update transaction status: ${e.message}")
            }
    }
    
    private fun addParkingSpotToDatabase() {
        // Get all input values
        val name = etParkingSpotName.text.toString().trim()
        val latitude = etLatitude.text.toString().toDouble()
        val longitude = etLongitude.text.toString().toDouble()
        val pricePerHour = etPricePerHour.text.toString()
        val ownerUpi = etOwnerUpi.text.toString().trim()
        
        // Get current user ID
        val currentUserId = auth.currentUser?.uid
        
        if (currentUserId == null) {
            Toast.makeText(this, "You must be logged in to add a parking spot", Toast.LENGTH_LONG).show()
            return
        }
        
        // Get selected categories using our helper method
        val categories = getSelectedCategories()
        
        // Create a map with the parking spot data
        val parkingSpotData = hashMapOf(
            "name" to name,
            "latitude" to latitude,
            "longitude" to longitude,
            "price_per_hour" to pricePerHour,
            "owner_upi" to ownerUpi,
            "category" to selectedPrimaryCategory,
            "categories" to categories,
            "averageRating" to 0.0,
            "ratingCount" to 0,
            "is_available" to true,
            "lastRatingUpdate" to Timestamp.now(),
            "ownerId" to currentUserId,  // Add the user's ID as the owner
            "transactionId" to transactionId
        )
        
        // Show loading state
        btnProceedToPayment.isEnabled = false
        btnProceedToPayment.text = "Adding Parking Spot..."
        
        // Add the parking spot to Firestore
        db.collection("parking_spots")
            .add(parkingSpotData)
            .addOnSuccessListener { _ ->
                Toast.makeText(this, "Parking spot added successfully!", Toast.LENGTH_LONG).show()
                
                // Return to previous screen
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error adding parking spot: ${e.message}", Toast.LENGTH_LONG).show()
                
                // Reset button state
                btnProceedToPayment.isEnabled = true
                btnProceedToPayment.text = "Proceed to Payment (₹$ADD_PARKING_SPOT_FEE)"
            }
    }
    
    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        Log.d(TAG, "Payment successful: $razorpayPaymentId")
        
        // Update transaction status
        transactionId?.let { id ->
            val updates = hashMapOf<String, Any>(
                "status" to "completed",
                "paymentId" to (razorpayPaymentId ?: ""),
                "updatedAt" to Timestamp.now()
            )
            
            db.collection("transactions")
                .document(id)
                .update(updates)
                .addOnSuccessListener {
                    // Now add the parking spot to the database
                    addParkingSpotToDatabase()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update transaction status", e)
                    Toast.makeText(this, "Payment succeeded but failed to update transaction: ${e.message}", Toast.LENGTH_LONG).show()
                    // Still try to add the parking spot
                    addParkingSpotToDatabase()
                }
        } ?: run {
            // No transaction ID, but payment succeeded
            Toast.makeText(this, "Payment successful! Adding parking spot...", Toast.LENGTH_SHORT).show()
            addParkingSpotToDatabase()
        }
    }

    override fun onPaymentError(code: Int, response: String?) {
        Log.e(TAG, "Payment failed: Code=$code, Response=$response")
        
        // Update transaction status
        transactionId?.let { id ->
            val updates = hashMapOf<String, Any>(
                "status" to "failed",
                "paymentError" to (response ?: "Unknown error"),
                "updatedAt" to Timestamp.now()
            )
            
            db.collection("transactions")
                .document(id)
                .update(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, "Payment failed. Please try again.", Toast.LENGTH_LONG).show()
                    
                    // Re-enable the button
                    btnProceedToPayment.isEnabled = true
                    btnProceedToPayment.text = "Proceed to Payment (₹$ADD_PARKING_SPOT_FEE)"
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update transaction status", e)
                    Toast.makeText(this, "Payment failed and could not update transaction: ${e.message}", Toast.LENGTH_LONG).show()
                    
                    // Re-enable the button
                    btnProceedToPayment.isEnabled = true
                    btnProceedToPayment.text = "Proceed to Payment (₹$ADD_PARKING_SPOT_FEE)"
                }
        } ?: run {
            // No transaction ID
            Toast.makeText(this, "Payment failed. Please try again.", Toast.LENGTH_LONG).show()
            
            // Re-enable the button
            btnProceedToPayment.isEnabled = true
            btnProceedToPayment.text = "Proceed to Payment (₹$ADD_PARKING_SPOT_FEE)"
        }
    }

    /**
     * Gets selected categories from UI
     */
    private fun getSelectedCategories(): List<String> {
        val categories = mutableListOf<String>()
        
        // Add checkboxes in order, starting with the primary category
        if (cbStandard.isChecked) categories.add("Standard")
        if (cbEvCharging.isChecked) categories.add("EV Charging")
        if (cbCoveredRoof.isChecked) categories.add("Covered Roof")
        if (cbOpenRoof.isChecked) categories.add("Open Roof")
        if (cbValet.isChecked) categories.add("Valet Parking")
        if (cbHandicap.isChecked) categories.add("Handicap Accessible")
        if (cbSecure.isChecked) categories.add("Secure 24/7")
        
        // If no categories are checked (shouldn't happen due to validation), add Standard
        if (categories.isEmpty()) {
            categories.add("Standard")
        }
        
        return categories
    }
} 