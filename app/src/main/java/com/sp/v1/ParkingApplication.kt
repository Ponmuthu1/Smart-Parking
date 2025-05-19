package com.sp.v1

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.razorpay.Checkout
import com.sp.v1.utils.SecurityProviderUtils
import java.util.Date

class ParkingApplication : Application() {
    
    companion object {
        private const val TAG = "ParkingApplication"
        
        // Make this accessible throughout the app to check if Google Play Services are available
        var googlePlayServicesAvailable = false
            // Allow setting this from other classes
            
        // Track when the last availability check was performed
        private var lastAvailabilityCheck = 0L
        
        // Minimum interval between checks (5 minutes)
        private const val CHECK_INTERVAL = 5 * 60 * 1000
    }
    
    private lateinit var db: FirebaseFirestore
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Firestore
        db = FirebaseFirestore.getInstance()
        
        // First check if Google Play Services are available
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
        googlePlayServicesAvailable = (resultCode == com.google.android.gms.common.ConnectionResult.SUCCESS)
        
        if (!googlePlayServicesAvailable) {
            Log.w(TAG, "Google Play Services not available (status code: $resultCode)")
        }
        
        // Initialize Razorpay with proper settings
        try {
            Checkout.preload(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Razorpay", e)
        }
        
        // Update Android security provider to protect against SSL exploits
        try {
            val securityProviderUpdated = SecurityProviderUtils.updateAndroidSecurityProvider(this)
            if (!securityProviderUpdated) {
                Log.w(TAG, "Security provider update failed - app will use default security settings")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating security provider", e)
            // Continue with app initialization even if security provider update fails
            // as we've implemented graceful degradation elsewhere
        }
        
        // Check for completed bookings and update spot availability
        checkAndUpdateSpotAvailability()
    }
    
    /**
     * Helper method to check if a feature requiring Google Play Services can be used
     * and display appropriate message to the user if not
     */
    fun checkGooglePlayServicesWithMessage(context: Context): Boolean {
        if (!googlePlayServicesAvailable) {
            Toast.makeText(
                context,
                "This feature requires Google Play Services. Some functionality may be limited.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        return true
    }
    
    /**
     * Checks for completed bookings and updates spot availability
     * This is called when the app starts and can be called from activities
     */
    fun checkAndUpdateSpotAvailability() {
        val currentTime = System.currentTimeMillis()
        
        // Only check if it's been a while since the last check
        if (currentTime - lastAvailabilityCheck < CHECK_INTERVAL) {
            Log.d(TAG, "Skipping availability check, last check was ${(currentTime - lastAvailabilityCheck) / 1000} seconds ago")
            return
        }
        
        Log.d(TAG, "Performing parking spot availability check")
        lastAvailabilityCheck = currentTime
        
        // Use the BookingUtils class to update booking and spot status
        com.sp.v1.utils.BookingUtils.updateBookingAndSpotStatus()
    }
} 