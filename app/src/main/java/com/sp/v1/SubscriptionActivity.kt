package com.sp.v1

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.razorpay.Checkout
import com.razorpay.PaymentResultListener
import org.json.JSONObject
import java.util.*
import kotlin.math.roundToInt

class SubscriptionActivity : AppCompatActivity(), PaymentResultListener {
    private lateinit var tvStatus: TextView
    private lateinit var tvBenefits: TextView
    private lateinit var btnSubscribe: Button
    private lateinit var btnBack: Button
    
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    
    private val SUBSCRIPTION_FEE = 300
    private val SUBSCRIPTION_DURATION_MONTHS = 3
    
    private var transactionId: String? = null
    private var currentSubscriptionEnd: Date? = null
    private var isSubscribed = false
    
    private val TAG = "SubscriptionActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)
        
        // Initialize Firebase
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
        
        // Initialize views
        tvStatus = findViewById(R.id.tvSubscriptionStatus)
        tvBenefits = findViewById(R.id.tvBenefits)
        btnSubscribe = findViewById(R.id.btnSubscribe)
        btnBack = findViewById(R.id.btnBack)
        
        // Set up benefits text
        tvBenefits.text = "Premium Subscription Benefits:\n" +
                "• 20% discount on all parking bookings\n" +
                "• Valid for ${SUBSCRIPTION_DURATION_MONTHS} months\n" +
                "• Priority customer support"
        
        // Set click listeners
        btnSubscribe.setOnClickListener {
            showSubscriptionConfirmation()
        }
        
        btnBack.setOnClickListener {
            finish()
        }
        
        // Check current subscription status
        checkSubscriptionStatus()
        
        // Preload Razorpay checkout
        Checkout.preload(applicationContext)
    }
    
    private fun checkSubscriptionStatus() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "Please login first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Show loading state
        tvStatus.text = "Checking subscription status..."
        btnSubscribe.isEnabled = false
        
        // Check if user has an active subscription
        db.collection("subscriptions")
            .whereEqualTo("userId", currentUser.uid)
            .whereGreaterThan("endDate", Timestamp.now())
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->
                if (!documents.isEmpty) {
                    // User has an active subscription
                    val subscription = documents.documents[0]
                    val endTimestamp = subscription.getTimestamp("endDate")
                    currentSubscriptionEnd = endTimestamp?.toDate()
                    
                    val endDateStr = java.text.SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                        .format(currentSubscriptionEnd ?: Date())
                    
                    tvStatus.text = "You have an active Premium subscription\nValid until: $endDateStr"
                    btnSubscribe.text = "Extend Subscription"
                    isSubscribed = true
                } else {
                    // No active subscription
                    tvStatus.text = "You don't have an active Premium subscription"
                    btnSubscribe.text = "Subscribe Now (₹$SUBSCRIPTION_FEE)"
                    isSubscribed = false
                }
                btnSubscribe.isEnabled = true
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error checking subscription status", e)
                tvStatus.text = "Error checking subscription status: ${e.message}"
                btnSubscribe.isEnabled = true
            }
    }
    
    private fun showSubscriptionConfirmation() {
        val message = if (isSubscribed) {
            "You already have an active subscription valid until " +
                    "${java.text.SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(currentSubscriptionEnd ?: Date())}. " +
                    "Extending will add $SUBSCRIPTION_DURATION_MONTHS more months from the current end date.\n\n" +
                    "Do you want to extend your Premium subscription for ₹$SUBSCRIPTION_FEE?"
        } else {
            "You are about to subscribe to our Premium plan for ₹$SUBSCRIPTION_FEE. " +
                    "This will give you a 20% discount on all parking bookings for $SUBSCRIPTION_DURATION_MONTHS months.\n\n" +
                    "Do you want to proceed?"
        }
        
        AlertDialog.Builder(this)
            .setTitle("Confirm Subscription")
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ ->
                createTransaction()
            }
            .setNegativeButton("No", null)
            .show()
    }
    
    private fun createTransaction() {
        val currentUser = auth.currentUser ?: return
        
        // Create a transaction record
        val transaction = hashMapOf(
            "userId" to currentUser.uid,
            "userEmail" to (currentUser.email ?: ""),
            "amount" to SUBSCRIPTION_FEE,
            "type" to "subscription",
            "status" to "pending",
            "createdAt" to Timestamp.now()
        )
        
        // Disable the button
        btnSubscribe.isEnabled = false
        btnSubscribe.text = "Processing..."
        
        // Add to Firestore
        db.collection("transactions")
            .add(transaction)
            .addOnSuccessListener { documentReference ->
                transactionId = documentReference.id
                // Start the payment process
                startPayment(SUBSCRIPTION_FEE.toDouble(), documentReference.id)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to create transaction", e)
                Toast.makeText(this, "Failed to create transaction: ${e.message}", Toast.LENGTH_SHORT).show()
                // Re-enable the button
                btnSubscribe.isEnabled = true
                btnSubscribe.text = if (isSubscribed) "Extend Subscription" else "Subscribe Now (₹$SUBSCRIPTION_FEE)"
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
            options.put("description", "Premium Subscription - ${SUBSCRIPTION_DURATION_MONTHS} months")
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
            btnSubscribe.isEnabled = true
            btnSubscribe.text = if (isSubscribed) "Extend Subscription" else "Subscribe Now (₹$SUBSCRIPTION_FEE)"
        }
    }
    
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
        btnSubscribe.isEnabled = true
        btnSubscribe.text = if (isSubscribed) "Extend Subscription" else "Subscribe Now (₹$SUBSCRIPTION_FEE)"
    }
    
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
    
    private fun addSubscriptionToDatabase() {
        val currentUser = auth.currentUser ?: return
        
        val startDate = Timestamp.now()
        val endDate: Timestamp
        
        // If extending an existing subscription, add months to the current end date
        if (isSubscribed && currentSubscriptionEnd != null) {
            val calendar = Calendar.getInstance()
            calendar.time = currentSubscriptionEnd!!
            calendar.add(Calendar.MONTH, SUBSCRIPTION_DURATION_MONTHS)
            endDate = Timestamp(calendar.time)
        } else {
            // New subscription, calculate from current date
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.MONTH, SUBSCRIPTION_DURATION_MONTHS)
            endDate = Timestamp(calendar.time)
        }
        
        // Create subscription record
        val subscriptionData = hashMapOf(
            "userId" to currentUser.uid,
            "userEmail" to (currentUser.email ?: ""),
            "startDate" to startDate,
            "endDate" to endDate,
            "transactionId" to transactionId,
            "discountPercent" to 20
        )
        
        // Show loading state
        btnSubscribe.isEnabled = false
        btnSubscribe.text = "Processing Subscription..."
        
        // First check if user already has a subscription document
        db.collection("subscriptions")
            .whereEqualTo("userId", currentUser.uid)
            .get()
            .addOnSuccessListener { documents ->
                if (!documents.isEmpty) {
                    // Update existing subscription
                    db.collection("subscriptions")
                        .document(documents.documents[0].id)
                        .update(
                            mapOf(
                                "endDate" to endDate,
                                "transactionId" to transactionId,
                                "updatedAt" to Timestamp.now()
                            )
                        )
                        .addOnSuccessListener {
                            subscriptionUpdatedSuccessfully(endDate.toDate())
                        }
                        .addOnFailureListener { e ->
                            subscriptionUpdateFailed(e)
                        }
                } else {
                    // Create new subscription
                    db.collection("subscriptions")
                        .add(subscriptionData)
                        .addOnSuccessListener {
                            subscriptionUpdatedSuccessfully(endDate.toDate())
                        }
                        .addOnFailureListener { e ->
                            subscriptionUpdateFailed(e)
                        }
                }
            }
            .addOnFailureListener { e ->
                subscriptionUpdateFailed(e)
            }
    }
    
    private fun subscriptionUpdatedSuccessfully(endDate: Date) {
        val endDateStr = java.text.SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(endDate)
        
        // Update UI
        tvStatus.text = "You have an active Premium subscription\nValid until: $endDateStr"
        btnSubscribe.text = "Extend Subscription"
        btnSubscribe.isEnabled = true
        isSubscribed = true
        currentSubscriptionEnd = endDate
        
        // Show success message
        Toast.makeText(
            this,
            "Premium subscription activated until $endDateStr!",
            Toast.LENGTH_LONG
        ).show()
    }
    
    private fun subscriptionUpdateFailed(e: Exception) {
        Log.e(TAG, "Error adding subscription", e)
        Toast.makeText(this, "Error updating subscription: ${e.message}", Toast.LENGTH_LONG).show()
        
        // Reset button state
        btnSubscribe.isEnabled = true
        btnSubscribe.text = if (isSubscribed) "Extend Subscription" else "Subscribe Now (₹$SUBSCRIPTION_FEE)"
        
        // Refresh subscription status
        checkSubscriptionStatus()
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
                    // Now add/update the subscription
                    addSubscriptionToDatabase()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update transaction status", e)
                    Toast.makeText(this, "Payment succeeded but failed to update transaction: ${e.message}", Toast.LENGTH_LONG).show()
                    // Still try to add the subscription
                    addSubscriptionToDatabase()
                }
        } ?: run {
            // No transaction ID, but payment succeeded
            Toast.makeText(this, "Payment successful! Activating subscription...", Toast.LENGTH_SHORT).show()
            addSubscriptionToDatabase()
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
                    btnSubscribe.isEnabled = true
                    btnSubscribe.text = if (isSubscribed) "Extend Subscription" else "Subscribe Now (₹$SUBSCRIPTION_FEE)"
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update transaction status", e)
                    Toast.makeText(this, "Payment failed and could not update transaction: ${e.message}", Toast.LENGTH_LONG).show()
                    
                    // Re-enable the button
                    btnSubscribe.isEnabled = true
                    btnSubscribe.text = if (isSubscribed) "Extend Subscription" else "Subscribe Now (₹$SUBSCRIPTION_FEE)"
                }
        } ?: run {
            // No transaction ID
            Toast.makeText(this, "Payment failed. Please try again.", Toast.LENGTH_LONG).show()
            
            // Re-enable the button
            btnSubscribe.isEnabled = true
            btnSubscribe.text = if (isSubscribed) "Extend Subscription" else "Subscribe Now (₹$SUBSCRIPTION_FEE)"
        }
    }
} 