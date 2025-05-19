package com.sp.v1

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*
import com.sp.v1.databinding.ActivityBookingBinding
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.util.Log
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultListener
import com.razorpay.PaymentResultWithDataListener
import org.json.JSONObject
import kotlin.math.roundToInt
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import android.app.AlertDialog
import com.sp.v1.utils.CategoryUtils
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

class BookingActivity : AppCompatActivity(), PaymentResultListener {
    private lateinit var binding: ActivityBookingBinding
    private lateinit var parkingSpot: ParkingSpot
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var selectedDate: Calendar = Calendar.getInstance()
    private var selectedTime: Calendar = Calendar.getInstance()
    private var duration: Double = 1.0 // Default 1 hour duration
    private var bookingId: String? = null
    private var discountPercent: Int = 0
    private var hasSubscription: Boolean = false
    
    private val TAG = "BookingActivity"
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        
        // Set up toolbar navigation (X button) click listener
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        
        // Check if user has premium subscription
        checkPremiumSubscription()
        
        // Preload payment resources
        Checkout.preload(applicationContext)
        
        // Check if any spots need their availability status updated
        (application as ParkingApplication).checkAndUpdateSpotAvailability()

        // Get parking spot from intent
        parkingSpot = intent.getParcelableExtra<ParkingSpot>("parkingSpot") ?: run {
            Toast.makeText(this, "Invalid parking spot", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Set up UI
        binding.parkingSpotName.text = "${parkingSpot.name}\n${parkingSpot.address}"
        binding.pricePerHour.text = "Price per hour: ₹${parkingSpot.price_per_hour}"
        
        // Display categories
        if (parkingSpot.categories.isNotEmpty()) {
            // Use primary category for the main badge
            val primaryCategory = parkingSpot.categories[0]
            binding.categoryText.text = primaryCategory
            binding.categoryText.background = CategoryUtils.getCategoryBackground(this, primaryCategory)
            
            // If there are additional categories, create and show them
            if (parkingSpot.categories.size > 1) {
                // Only if container exists (checking in case layout was modified)
                val container = binding.categoriesContainer
                if (container != null) {
                    // Clear any existing views first
                    container.removeAllViews()
                    
                    // Add additional categories (skip first one as it's already shown)
                    for (i in 1 until parkingSpot.categories.size) {
                        val category = parkingSpot.categories[i]
                        
                        // Create a TextView for this category
                        val categoryView = TextView(this).apply {
                            text = category
                            background = CategoryUtils.getCategoryBackground(this@BookingActivity, category)
                            
                            // Apply style programmatically to match CategoryBadge style
                            setTextAppearance(R.style.CategoryBadge)
                            
                            // Apply specific style properties to ensure visual consistency
                            setTextColor(ContextCompat.getColor(this@BookingActivity, R.color.white))
                            textSize = 14f
                            setPadding(
                                resources.getDimensionPixelSize(R.dimen.category_padding_horizontal),
                                resources.getDimensionPixelSize(R.dimen.category_padding_vertical),
                                resources.getDimensionPixelSize(R.dimen.category_padding_horizontal),
                                resources.getDimensionPixelSize(R.dimen.category_padding_vertical)
                            )
                            
                            // Add margins between items
                            val params = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                marginStart = resources.getDimensionPixelSize(R.dimen.category_margin)
                            }
                            layoutParams = params
                        }
                        
                        // Add to container
                        container.addView(categoryView)
                    }
                }
            }
        } else {
            // Fallback to single category
            binding.categoryText.text = parkingSpot.category
            binding.categoryText.background = CategoryUtils.getCategoryBackground(this, parkingSpot.category)
        }
        
        // Set rating bar and count
        binding.ratingBarSpot.rating = parkingSpot.averageRating.toFloat()
        binding.tvRatingCount.text = "(${parkingSpot.ratingCount})"

        // Check availability and load booking timeframes
        if (!parkingSpot.is_available) {
            // Spot is currently booked, but we still allow booking attempts
            binding.availabilityInfo.text = "This parking spot is currently booked. You can still book for a future time slot."
            binding.availabilityInfo.visibility = View.VISIBLE
        }
        
        // Load existing bookings for this spot to populate the booking time slots
        loadExistingBookings()

        setupDatePicker()
        setupTimePicker()
        setupDurationInput()
        setupPayButton()
        
        // Set default values
        setDefaultValues()
    }
    
    private fun setDefaultValues() {
        // Set current date
        binding.datePicker.setText(dateFormat.format(selectedDate.time))
        
        // Set current time (rounded to nearest 5 minutes for better UX)
        val minutes = selectedTime.get(Calendar.MINUTE)
        val roundedMinutes = ((minutes + 2) / 5) * 5
        selectedTime.set(Calendar.MINUTE, roundedMinutes)
        binding.timePicker.setText(timeFormat.format(selectedTime.time))
        
        // Set default duration to 1 hour
        binding.durationInput.setText("1.0")
        
        // Calculate initial amount
        calculateAmount()
    }

    override fun onBackPressed() {
        // If Razorpay checkout is showing, let it handle the back press
        if (!isFinishing) {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save important state
        outState.putDouble("duration", duration)
        outState.putString("bookingId", bookingId)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Restore important state
        duration = savedInstanceState.getDouble("duration")
        bookingId = savedInstanceState.getString("bookingId")
    }

    private fun setupDatePicker() {
        binding.datePicker.setOnClickListener {
            val minDate = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            DatePickerDialog(
                this,
                { _, year, month, day ->
                    selectedDate.set(year, month, day)
                    binding.datePicker.setText(dateFormat.format(selectedDate.time))
                    userModifiedTime = true
                    validateAndCalculate()
                },
                selectedDate.get(Calendar.YEAR),
                selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)
            ).apply {
                datePicker.minDate = minDate.timeInMillis
            }.show()
        }
    }

    private fun setupTimePicker() {
        binding.timePicker.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    selectedTime.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    selectedTime.set(Calendar.MINUTE, minute)
                    binding.timePicker.setText(timeFormat.format(selectedTime.time))
                    userModifiedTime = true
                    validateAndCalculate()
                },
                selectedTime.get(Calendar.HOUR_OF_DAY),
                selectedTime.get(Calendar.MINUTE),
                true
            ).show()
        }
    }

    private fun setupDurationInput() {
        binding.durationInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateAndCalculate()
            }
        })
    }

    private fun validateAndCalculate() {
        // Validate date
        val isDateValid = !binding.datePicker.text.isNullOrEmpty()
        binding.datePicker.error = if (!isDateValid) "Please select a date" else null

        // Validate time
        val isTimeValid = !binding.timePicker.text.isNullOrEmpty()
        binding.timePicker.error = if (!isTimeValid) "Please select a time" else null

        // Validate duration
        val durationText = binding.durationInput.text.toString()
        val isDurationValid = durationText.isNotEmpty() && durationText.toDoubleOrNull()?.let { it > 0 } == true
        binding.durationInput.error = if (!isDurationValid) "Please enter a valid duration" else null

        // Calculate amount if all inputs are valid
        if (isDateValid && isTimeValid && isDurationValid) {
            duration = durationText.toDouble()
            calculateAmount()
            
            // Check availability for the selected time
            checkRealTimeAvailability()
        } else {
            // Clear amount display if inputs are invalid
            binding.totalAmount.text = "Total Amount: ₹0.00"
        }
    }

    private fun calculateAmount() {
        // Start with the base amount
        var total = parkingSpot.price_per_hour * duration
        
        Log.d(TAG, "Calculating amount: base amount = ₹$total, hasSubscription = $hasSubscription, discountPercent = $discountPercent")
        
        // Apply discount if user has premium subscription
        if (hasSubscription && discountPercent > 0) {
            val discount = total * (discountPercent / 100.0)
            total -= discount
            
            Log.d(TAG, "Applied discount: -${discountPercent}% (₹${String.format("%.2f", discount)}), new total: ₹$total")
            
            // Show discount information
            binding.discountInfo.text = "Premium discount: -${discountPercent}% (₹${String.format("%.2f", discount)})"
            binding.discountInfo.visibility = View.VISIBLE
        } else {
            Log.d(TAG, "No discount applied")
            binding.discountInfo.visibility = View.GONE
        }
        
        binding.totalAmount.text = "Total Amount: ₹${String.format("%.2f", total)}"
    }

    private fun setupPayButton() {
        binding.payButton.setOnClickListener {
            if (validateInputs()) {
                createBookingAndInitiatePayment()
            }
        }
    }

    private fun validateInputs(): Boolean {
        var isValid = true

        // Validate date
        if (binding.datePicker.text.isNullOrEmpty()) {
            binding.datePicker.error = "Please select a date"
            isValid = false
        }

        // Validate time
        if (binding.timePicker.text.isNullOrEmpty()) {
            binding.timePicker.error = "Please select a time"
            isValid = false
        }

        // Validate duration
        val durationText = binding.durationInput.text.toString()
        if (durationText.isEmpty() || durationText.toDoubleOrNull()?.let { it <= 0 } == true) {
            binding.durationInput.error = "Please enter a valid duration"
            isValid = false
        }

        return isValid
    }

    private fun createBookingAndInitiatePayment() {
        // Calculate original amount
        val originalAmount = parkingSpot.price_per_hour * duration
        
        // Apply discount if applicable
        var finalAmount = originalAmount
        if (hasSubscription && discountPercent > 0) {
            val discount = originalAmount * (discountPercent / 100.0)
            finalAmount = originalAmount - discount
        }
        
        Log.d(TAG, "Creating booking with original amount: $originalAmount, final amount: $finalAmount")

        // Get the timestamps using our helper methods for consistency
        val startTimestamp = createTimestampFromSelection()
        val endTimestamp = createEndTimestampFromSelection()
        
        // Check if spot is available for the selected time slot
        checkSpotAvailability(parkingSpot.name, startTimestamp, endTimestamp) { isAvailable ->
            if (isAvailable) {
                // Spot is available for this time slot, proceed with booking
                proceedWithBooking(originalAmount, startTimestamp, endTimestamp)
            } else {
                // Spot is already booked for this time slot
                Toast.makeText(this, "This parking spot is not available for the selected time. Please choose a different time or duration.", Toast.LENGTH_LONG).show()
                binding.payButton.isEnabled = true
            }
        }
    }
    
    private fun loadExistingBookings() {
        // If the parking spot already has loaded bookings, use those
        // But ensure our collection is up-to-date by checking for new bookings
        
        Log.d(TAG, "Loading existing bookings for spot ${parkingSpot.name}")
        
        // Load from Firestore
        db.collection("bookings")
            .whereEqualTo("parkingSpotId", parkingSpot.name)
            .whereIn("status", listOf("pending", "completed"))
            .get()
            .addOnSuccessListener { documents ->
                Log.d(TAG, "Loaded ${documents.size()} bookings for spot ${parkingSpot.name}")
                
                // Clear any existing bookings to ensure we're working with fresh data
                parkingSpot.bookedTimeSlots.clear()
                
                // Flag to track if we have any current or future bookings
                var hasCurrentOrFutureBookings = false
                val currentTime = Timestamp.now()
                
                for (doc in documents) {
                    val bookingId = doc.id
                    val startTime = doc.getTimestamp("startTime")
                    val endTime = doc.getTimestamp("endTime")
                    
                    if (startTime != null && endTime != null) {
                        // Add this booking to the parking spot's list
                        val timeSlot = BookedTimeSlot(bookingId, startTime, endTime)
                        parkingSpot.bookedTimeSlots.add(timeSlot)
                        
                        // Check if this booking is current or future
                        if (endTime.seconds >= currentTime.seconds) {
                            hasCurrentOrFutureBookings = true
                            
                            // Log booking details for debugging
                            Log.d(TAG, "Booking $bookingId: Start=${Date(startTime.seconds * 1000)}, End=${Date(endTime.seconds * 1000)}")
                        }
                        
                        // Check if currently active (current time is within booking period)
                        if (currentTime.seconds >= startTime.seconds && currentTime.seconds <= endTime.seconds) {
                            Log.d(TAG, "Booking $bookingId is currently active")
                            
                            // Make sure spot is marked as unavailable while there's an active booking
                            if (parkingSpot.is_available) {
                                Log.d(TAG, "Spot incorrectly marked as available during active booking")
                                
                                // Update spot locally for immediate UI feedback
                                parkingSpot.is_available = false
                                
                                // Update in database
                                updateSpotAvailabilityInFirestore(parkingSpot.name, false) {
                                    Log.d(TAG, "Updated spot availability in Firestore")
                                }
                            }
                        }
                    }
                }
                
                // Update the has_future_bookings flag based on what we found
                parkingSpot.has_future_bookings = hasCurrentOrFutureBookings
                
                // If there are no current bookings but the spot is marked unavailable, fix this
                if (!hasCurrentBooking() && !parkingSpot.is_available) {
                    Log.d(TAG, "Spot is incorrectly marked as unavailable with no active bookings")
                    
                    // Only update if we're sure there are no active bookings
                    updateSpotAvailabilityInFirestore(parkingSpot.name, true) {
                        Log.d(TAG, "Updated spot availability to available in Firestore")
                        
                        // Update local state
                        parkingSpot.is_available = true
                    }
                }
                
                // Update UI to reflect any time slot availability issues
                updateAvailabilityInfo()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error loading existing bookings", e)
                Toast.makeText(this, "Could not load booking information: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
    
    /**
     * Checks if the spot has any current active bookings
     */
    private fun hasCurrentBooking(): Boolean {
        val currentTime = Timestamp.now()
        
        return parkingSpot.bookedTimeSlots.any { slot ->
            currentTime.seconds >= slot.startTime.seconds && 
            currentTime.seconds <= slot.endTime.seconds
        }
    }
    
    private fun updateAvailabilityInfo() {
        // If the spot has future bookings, show the next available time
        if (parkingSpot.has_future_bookings || !parkingSpot.is_available) {
            val currentTime = Date()
            val nextAvailable = parkingSpot.getNextAvailableTime(currentTime)
            
            val formattedTime = if (nextAvailable != null) {
                // Format with appropriate time reference
                if (isToday(nextAvailable)) {
                    // Today - show as "Today at HH:mm"
                    "Today at " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextAvailable)
                } else if (isTomorrow(nextAvailable)) {
                    // Tomorrow - show as "Tomorrow at HH:mm"
                    "Tomorrow at " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextAvailable)
                } else {
                    // Some other day - show full date and time
                    SimpleDateFormat("EEE, MMM dd, HH:mm", Locale.getDefault()).format(nextAvailable)
                }
            } else {
                // If next available time is null, show "Not determined" with booking advice
                "Not determined - contact support"
            }
            
            // Show this information to the user
            if (!parkingSpot.is_available) {
                binding.availabilityInfo.text = "This spot is currently booked.\nNext available time: $formattedTime"
            } else {
                binding.availabilityInfo.text = "This spot has some reserved times.\nNext guaranteed availability: $formattedTime"
            }
            binding.availabilityInfo.visibility = View.VISIBLE
            
            // Suggest this time if better than default and it's in the future
            if (nextAvailable != null && nextAvailable.after(currentTime)) {
                suggestAvailableTimeSlot(nextAvailable)
            }
        } else {
            binding.availabilityInfo.visibility = View.GONE
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
    
    private fun suggestAvailableTimeSlot(suggestedTime: Date) {
        // Only suggest if we haven't manually set times yet
        if (!userModifiedTime) {
            val calendar = Calendar.getInstance()
            calendar.time = suggestedTime
            
            // Add 15 minutes buffer to the suggested time
            calendar.add(Calendar.MINUTE, 15)
            
            // Update the date and time pickers
            selectedDate.set(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            
            // Set hour and minute separately
            selectedTime.set(Calendar.HOUR_OF_DAY, calendar.get(Calendar.HOUR_OF_DAY))
            selectedTime.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE))
            
            // Update the UI
            binding.datePicker.setText(dateFormat.format(selectedDate.time))
            binding.timePicker.setText(timeFormat.format(selectedTime.time))
            
            // Show a message to the user
            Toast.makeText(this, "We've suggested a convenient time slot!", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Track if user has modified the time/date
    private var userModifiedTime = false
    
    private fun checkSpotAvailability(spotId: String, startTime: Timestamp, endTime: Timestamp, callback: (Boolean) -> Unit) {
        binding.payButton.isEnabled = false
        
        // If the parking spot object already has booking information, use it directly
        if (parkingSpot.bookedTimeSlots.isNotEmpty()) {
            Log.d(TAG, "Checking availability using loaded time slots (${parkingSpot.bookedTimeSlots.size} slots)")
            
            // Use the ParkingSpot class method to check availability
            val isAvailable = parkingSpot.isAvailableForTimeSlot(startTime, endTime)
            
            if (!isAvailable) {
                Log.d(TAG, "Spot $spotId is not available for the requested time slot")
                
                // Show dialog with alternative time suggestions
                showTimeConflictDialog(startTime, endTime, callback)
                return
            } else {
                // Still check if the spot itself is marked as unavailable in the database
                checkSpotBaseAvailability(spotId) { isBaseAvailable ->
                    callback(isBaseAvailable)
                }
            }
            
            return
        }
        
        // Fallback to the original implementation that queries Firestore directly
        // Query existing bookings for this parking spot that overlap with the requested time slot
        db.collection("bookings")
            .whereEqualTo("parkingSpotId", spotId)
            .whereIn("status", listOf("pending", "completed")) // Only consider active bookings
            .get()
            .addOnSuccessListener { documents ->
                // Check if any existing booking overlaps with the requested time slot
                val overlappingBookings = documents.filter { doc ->
                    val existingStart = doc.getTimestamp("startTime")
                    val existingEnd = doc.getTimestamp("endTime")
                    
                    // Check if there's an overlap
                    existingStart != null && existingEnd != null &&
                    (startTime.compareTo(existingStart) >= 0 && startTime.compareTo(existingEnd) < 0) || // New start time is during existing booking
                    (endTime.compareTo(existingStart) > 0 && endTime.compareTo(existingEnd) <= 0) || // New end time is during existing booking
                    (startTime.compareTo(existingStart) <= 0 && endTime.compareTo(existingEnd) >= 0) // New booking spans the entire existing booking
                }
                
                if (overlappingBookings.isNotEmpty()) {
                    Log.d(TAG, "Found ${overlappingBookings.size} overlapping bookings for spot $spotId")
                    // Show dialog with alternative time suggestions
                    showTimeConflictDialog(startTime, endTime, callback)
                } else {
                    // Check if the spot itself is marked as unavailable in the database
                    checkSpotBaseAvailability(spotId) { isBaseAvailable ->
                        callback(isBaseAvailable)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error checking spot availability", e)
                // Assume available in case of error (though show a warning)
                Toast.makeText(this, "Could not verify spot availability: ${e.message}", Toast.LENGTH_SHORT).show()
                callback(true)
            }
    }
    
    private fun showTimeConflictDialog(requestedStart: Timestamp, requestedEnd: Timestamp, callback: (Boolean) -> Unit) {
        // Find next available time after the requested start time
        val requestedDate = Date(requestedStart.seconds * 1000)
        val nextAvailable = parkingSpot.getNextAvailableTime(requestedDate)
        
        val formattedRequestedTime = SimpleDateFormat("EEE, MMM dd HH:mm", Locale.getDefault())
            .format(requestedDate)
        
        val formattedSuggestedTime = if (nextAvailable != null) {
            // Format with appropriate time reference
            if (isToday(nextAvailable)) {
                // Today - show as "Today at HH:mm"
                "Today at " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextAvailable)
            } else if (isTomorrow(nextAvailable)) {
                // Tomorrow - show as "Tomorrow at HH:mm"
                "Tomorrow at " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextAvailable)
            } else {
                // Some other day - show full date and time
                SimpleDateFormat("EEE, MMM dd, HH:mm", Locale.getDefault()).format(nextAvailable)
            }
        } else {
            "No available slots soon"
        }
        
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Time Slot Conflict")
        
        if (nextAvailable != null) {
            builder.setMessage("The selected time ($formattedRequestedTime) is already booked. " +
                    "Would you like to:\n\n" +
                    "1. Book for the next available time: $formattedSuggestedTime\n" +
                    "2. Select a different time yourself")
            
            builder.setPositiveButton("Use Suggested Time") { _, _ ->
                // Use the suggested time
                val calendar = Calendar.getInstance()
                calendar.time = nextAvailable
                
                // Add 15 minutes buffer
                calendar.add(Calendar.MINUTE, 15)
                
                // Update the UI
                selectedDate.set(
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                )
                
                // Set hour and minute separately
                selectedTime.set(Calendar.HOUR_OF_DAY, calendar.get(Calendar.HOUR_OF_DAY))
                selectedTime.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE))
                
                binding.datePicker.setText(dateFormat.format(selectedDate.time))
                binding.timePicker.setText(timeFormat.format(selectedTime.time))
                
                // Re-enable the button
                binding.payButton.isEnabled = true
                
                // Tell user to proceed
                Toast.makeText(this, "New time selected. Proceed with booking.", Toast.LENGTH_SHORT).show()
            }
        } else {
            builder.setMessage("The selected time ($formattedRequestedTime) is already booked " +
                "and no available time slots were found soon.\n\n" +
                "Please select a different date or time for your booking.")
        }
        
        builder.setNegativeButton("Select Myself") { _, _ ->
            // Let the user choose a different time
            binding.payButton.isEnabled = true
            Toast.makeText(this, "Please select a different time for your booking.", Toast.LENGTH_SHORT).show()
        }
        
        builder.setCancelable(false)
        builder.show()
    }
    
    private fun checkSpotBaseAvailability(spotId: String, callback: (Boolean) -> Unit) {
        db.collection("parking_spots")
            .whereEqualTo("name", spotId)
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    Log.e(TAG, "Spot with ID $spotId not found in database")
                    callback(false)
                    return@addOnSuccessListener
                }
                
                val spotDoc = documents.documents[0]
                val isAvailable = spotDoc.getBoolean("is_available") ?: true
                callback(isAvailable)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error checking base spot availability: ${e.message}")
                callback(true) // Assume available in case of error
            }
    }
    
    private fun proceedWithBooking(totalAmount: Double, startTimestamp: Timestamp, endTimestamp: Timestamp) {
        // Calculate the final amount with any applicable discount
        var finalAmount = totalAmount
        var appliedDiscount = 0.0
        
        // Apply discount if user has premium subscription
        if (hasSubscription && discountPercent > 0) {
            Log.d(TAG, "Applying premium discount: $discountPercent% to amount: $totalAmount")
            appliedDiscount = totalAmount * (discountPercent / 100.0)
            finalAmount = totalAmount - appliedDiscount
            Log.d(TAG, "Final amount after discount: $finalAmount, discount applied: $appliedDiscount")
        }
        
        // Create booking in Firestore
        val booking = hashMapOf(
            "userId" to (auth.currentUser?.uid ?: ""),
            "parkingSpotId" to parkingSpot.name,
            "parkingSpotName" to parkingSpot.name,
            "startTime" to startTimestamp,
            "endTime" to endTimestamp,
            "originalAmount" to totalAmount,
            "discountPercent" to discountPercent,
            "discountAmount" to appliedDiscount,
            "totalAmount" to finalAmount, // Use the discounted amount
            "status" to "pending",
            "hasSubscription" to hasSubscription,
            "createdAt" to Timestamp.now()
        )

        Log.d(TAG, "Creating booking document: $booking")

        db.collection("bookings")
            .add(booking)
            .addOnSuccessListener { documentReference ->
                Log.d(TAG, "Booking created successfully with ID: ${documentReference.id}")
                bookingId = documentReference.id
                // Initiate Razorpay payment with discounted amount
                startPayment(finalAmount, documentReference.id)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to create booking", e)
                showError("Failed to create booking: ${e.message}")
                binding.payButton.isEnabled = true
            }
    }
    
    private fun startPayment(amount: Double, orderId: String) {
        // First check if Google Play Services is available as Razorpay depends on it
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
        
        if (resultCode != ConnectionResult.SUCCESS) {
            // Handle the Google Play Services issue
            handleGooglePlayServicesError(resultCode, amount, orderId)
            return
        }
        
        val checkout = Checkout()
        checkout.setKeyID(getString(R.string.razorpay_key_id))
        
        try {
            // rounding off the amount to the nearest integer
            val amountInPaise = (amount * 100).roundToInt()
            
            val options = JSONObject()
            options.put("name", "Parking App")
            options.put("description", "Parking Booking - ${parkingSpot.name}")
            options.put("theme.color", "#3399cc")
            options.put("currency", "INR")
            options.put("amount", amountInPaise)
            options.put("image", "https://s3.amazonaws.com/rzp-mobile/images/rzp.png")
            
            // Save the bookingId in notes for reference
            val notes = JSONObject()
            notes.put("booking_id", orderId)
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
            
            // Mark booking as failed since payment couldn't be initiated
            updateBookingStatus(orderId, "failed", "Payment initialization failed: ${e.message}")
        }
    }
    
    /**
     * Handles issues with Google Play Services during payment
     */
    private fun handleGooglePlayServicesError(resultCode: Int, amount: Double, orderId: String) {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        
        if (googleApiAvailability.isUserResolvableError(resultCode)) {
            // Show dialog to resolve the error
            googleApiAvailability.getErrorDialog(
                this, 
                resultCode,
                9000
            ) { 
                // Dialog was cancelled, update booking as failed
                updateBookingStatus(orderId, "cancelled", "Google Play Services error not resolved")
                Toast.makeText(this, "Payment cancelled: Google Play Services is required", Toast.LENGTH_LONG).show()
                finish()
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
                    // Update booking as cancelled
                    updateBookingStatus(orderId, "cancelled", "Payment cancelled - Google Play Services unavailable")
                    finish()
                }
                .setCancelable(false)
                .show()
        }
    }
    
    /**
     * Updates the booking status in Firestore
     */
    private fun updateBookingStatus(bookingId: String, status: String, message: String) {
        val updates = hashMapOf<String, Any>(
            "status" to status,
            "paymentError" to message,
            "updatedAt" to Timestamp.now()
        )
        
        db.collection("bookings")
            .document(bookingId)
            .update(updates)
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update booking status: ${e.message}")
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        Log.d(TAG, "Payment successful: $razorpayPaymentId")
        
        // Update booking status in Firestore
        bookingId?.let { id ->
            val updates = hashMapOf<String, Any>(
                "status" to "completed",
                "paymentId" to (razorpayPaymentId ?: ""),
                "updatedAt" to Timestamp.now()
            )
            
            db.collection("bookings")
                .document(id)
                .update(updates)
                .addOnSuccessListener {
                    // Immediately mark spot as unavailable to prevent double booking
                    markSpotAsBooked(id)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update booking status", e)
                    showError("Payment succeeded but failed to update booking: ${e.message}")
                }
        }
    }

    override fun onPaymentError(code: Int, response: String?) {
        Log.e(TAG, "Payment failed: Code=$code, Response=$response")
        
        // Update booking status to failed in Firestore
        bookingId?.let { id ->
            val updates = hashMapOf<String, Any>(
                "status" to "failed",
                "paymentError" to (response ?: "Unknown error"),
                "updatedAt" to Timestamp.now()
            )
            
            db.collection("bookings")
                .document(id)
                .update(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, "Payment failed. Please try again.", Toast.LENGTH_LONG).show()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update booking status", e)
                    showError("Payment failed and could not update booking: ${e.message}")
                }
        }
    }

    private fun showError(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun updateSpotAvailabilityStatus(spotId: String, isAvailable: Boolean, onComplete: () -> Unit) {
        // First check if there are any active bookings for this spot
        if (!isAvailable) {
            // We want to mark as unavailable, so just update directly
            updateSpotAvailabilityInFirestore(spotId, isAvailable, onComplete)
        } else {
            // We want to mark as available, but should first verify there are no other active bookings
            checkActiveBookingsForSpot(spotId) { hasActiveBookings ->
                if (hasActiveBookings) {
                    // There are other active bookings, so don't mark as available yet
                    Log.d(TAG, "Not marking spot $spotId as available because it has other active bookings")
                    onComplete()
                } else {
                    // No active bookings, safe to mark as available
                    updateSpotAvailabilityInFirestore(spotId, true, onComplete)
                }
            }
        }
    }

    /**
     * Checks if there are any active bookings for a specific spot
     */
    private fun checkActiveBookingsForSpot(spotId: String, callback: (Boolean) -> Unit) {
        val currentTime = Timestamp.now()
        
        db.collection("bookings")
            .whereEqualTo("parkingSpotId", spotId)
            .whereIn("status", listOf("pending", "completed"))
            .get()
            .addOnSuccessListener { documents ->
                // Check if any existing booking overlaps with current time
                val hasActiveBookings = documents.any { doc ->
                    val startTime = doc.getTimestamp("startTime")
                    val endTime = doc.getTimestamp("endTime")
                    
                    startTime != null && endTime != null &&
                    currentTime.compareTo(startTime) >= 0 && currentTime.compareTo(endTime) <= 0
                }
                
                callback(hasActiveBookings)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error checking active bookings: ${e.message}")
                // Assume no active bookings in case of error
                callback(false)
            }
    }

    /**
     * Updates the spot availability in Firestore
     */
    private fun updateSpotAvailabilityInFirestore(spotId: String, isAvailable: Boolean, onComplete: () -> Unit) {
        db.collection("parking_spots")
            .whereEqualTo("name", spotId)
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    Log.e(TAG, "No parking spot found with ID: $spotId")
                    onComplete()
                    return@addOnSuccessListener
                }
                
                // Found the parking spot, update its availability
                val spotDoc = documents.documents[0]
                db.collection("parking_spots")
                    .document(spotDoc.id)
                    .update("is_available", isAvailable)
                    .addOnSuccessListener {
                        Log.d(TAG, "Updated parking spot availability: $isAvailable")
                        onComplete()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to update parking spot availability: ${e.message}")
                        // Still call onComplete even if we fail to update the spot
                        onComplete()
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error finding parking spot to update: ${e.message}")
                onComplete()
            }
    }

    private fun markSpotAsBooked(bookingId: String) {
        // First, update the parking spot's availability in Firestore
        updateSpotAvailabilityStatus(parkingSpot.name, false) {
            // Then proceed to receipt screen
            Toast.makeText(this, "Booking confirmed! Your spot is reserved.", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(this, ReceiptActivity::class.java).apply {
                    putExtra("bookingId", bookingId)
                }
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "Error navigating to receipt", e)
                Toast.makeText(this, "Payment successful but couldn't show receipt", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun checkRealTimeAvailability() {
        // Create timestamps for selected start and end times
        val startTimestamp = createTimestampFromSelection()
        val endTimestamp = createEndTimestampFromSelection()
        
        // Visual feedback while checking
        binding.availabilityInfo.text = "Checking availability..."
        binding.availabilityInfo.visibility = View.VISIBLE
        
        // Check if the spot is available for this time slot
        val isAvailable = parkingSpot.isAvailableForTimeSlot(startTimestamp, endTimestamp)
        
        if (!isAvailable) {
            // Spot is not available for the selected time slot
            binding.availabilityInfo.text = "⚠️ This spot is not available for your selected time.\n" +
                    "Please choose a different time or check available time slots."
            binding.availabilityInfo.setTextColor(getColor(R.color.warning))
            binding.availabilityInfo.visibility = View.VISIBLE
            
            // Suggest next available time
            val nextAvailable = parkingSpot.getNextAvailableTime(startTimestamp.toDate())
            if (nextAvailable != null) {
                val formattedTime = if (isToday(nextAvailable)) {
                    // Today - show as "Today at HH:mm"
                    "Today at " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextAvailable)
                } else if (isTomorrow(nextAvailable)) {
                    // Tomorrow - show as "Tomorrow at HH:mm"
                    "Tomorrow at " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextAvailable)
                } else {
                    // Some other day - show full date and time
                    SimpleDateFormat("EEE, MMM dd, HH:mm", Locale.getDefault()).format(nextAvailable)
                }
                
                binding.availabilityInfo.text = binding.availabilityInfo.text.toString() + 
                        "\n\nNext available: $formattedTime"
            }
        } else {
            // Spot is available for the selected time
            binding.availabilityInfo.text = "✅ This spot is available for your selected time!"
            binding.availabilityInfo.setTextColor(getColor(R.color.success))
            binding.availabilityInfo.visibility = View.VISIBLE
        }
    }
    
    private fun createTimestampFromSelection(): Timestamp {
        return Timestamp(Calendar.getInstance().apply {
            time = selectedDate.time
            set(Calendar.HOUR_OF_DAY, selectedTime.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, selectedTime.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
        }.time)
    }
    
    private fun createEndTimestampFromSelection(): Timestamp {
        return Timestamp(Calendar.getInstance().apply {
            time = selectedDate.time
            set(Calendar.HOUR_OF_DAY, selectedTime.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, selectedTime.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            add(Calendar.MINUTE, (duration * 60).toInt())
        }.time)
    }

    private fun checkPremiumSubscription() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            // User is not logged in
            Log.d(TAG, "Cannot check subscription - user not logged in")
            return
        }
        
        // Show loading indication
        binding.discountInfo.text = "Checking subscription status..."
        binding.discountInfo.visibility = View.VISIBLE
        
        Log.d(TAG, "Checking premium subscription for user ${currentUser.uid}")
        
        // Check Firestore for active subscriptions
        db.collection("subscriptions")
            .whereEqualTo("userId", currentUser.uid)
            .whereGreaterThan("endDate", Timestamp.now()) // Check if subscription is still valid
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->
                Log.d(TAG, "Subscription query returned ${documents.size()} documents")
                
                if (!documents.isEmpty) {
                    // User has an active subscription
                    val subscription = documents.documents[0]
                    val endDate = subscription.getTimestamp("endDate")
                    val endDateStr = if (endDate != null) {
                        java.text.SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(endDate.toDate())
                    } else {
                        "unknown"
                    }
                    
                    // Get discount percent from subscription
                    discountPercent = subscription.getLong("discountPercent")?.toInt() ?: 20
                    hasSubscription = true
                    
                    Log.d(TAG, "Found active subscription with discount: $discountPercent%, valid until: $endDateStr")
                    
                    // Calculate price with discount
                    calculateAmount()
                    
                    // Add upgrade subscription option
                    binding.btnSubscribeInfo.visibility = View.VISIBLE
                    binding.btnSubscribeInfo.text = "Premium Active"
                    binding.btnSubscribeInfo.setOnClickListener {
                        // Show subscription details
                        startActivity(Intent(this, SubscriptionActivity::class.java))
                    }
                } else {
                    // No active subscription
                    Log.d(TAG, "No active subscriptions found for this user")
                    hasSubscription = false
                    discountPercent = 0
                    
                    // Hide discount info
                    binding.discountInfo.visibility = View.GONE
                    
                    // Show option to upgrade
                    binding.btnSubscribeInfo.visibility = View.VISIBLE
                    binding.btnSubscribeInfo.text = "Save 20% with Premium"
                    binding.btnSubscribeInfo.setOnClickListener {
                        // Navigate to subscription page
                        startActivity(Intent(this, SubscriptionActivity::class.java))
                    }
                }
            }
            .addOnFailureListener { e ->
                // Error checking subscription
                Log.e(TAG, "Error checking subscription: ${e.message}")
                binding.discountInfo.visibility = View.GONE
                
                // Still show option to upgrade
                binding.btnSubscribeInfo.visibility = View.VISIBLE
                binding.btnSubscribeInfo.text = "Save 20% with Premium"
                binding.btnSubscribeInfo.setOnClickListener {
                    // Navigate to subscription page
                    startActivity(Intent(this, SubscriptionActivity::class.java))
                }
            }
    }
}