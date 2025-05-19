package com.sp.v1

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sp.v1.utils.RatingUtils

/**
 * A utility activity for initializing and fixing ratings in the database
 */
class RatingInitActivity : AppCompatActivity() {
    
    private lateinit var initButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private val TAG = "RatingInitActivity"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rating_init)
        
        // Initialize views
        initButton = findViewById(R.id.btnInitRatings)
        statusText = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        
        // Setup back button in toolbar
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Rating Initialization"
        
        // Setup click listener
        initButton.setOnClickListener {
            initializeRatings()
        }
    }
    
    private fun initializeRatings() {
        // Show progress
        progressBar.visibility = View.VISIBLE
        statusText.text = "Initializing rating fields..."
        initButton.isEnabled = false
        
        // Call the utility function
        RatingUtils.initializeAllSpotRatings(
            context = this,
            onComplete = { count ->
                // Update UI
                progressBar.visibility = View.GONE
                statusText.text = "Initialization complete: $count spots processed"
                initButton.isEnabled = true
                
                // Log result
                Log.d(TAG, "Rating initialization completed for $count spots")
                Toast.makeText(this, "Ratings initialized for $count spots", Toast.LENGTH_SHORT).show()
            }
        )
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
} 