package com.sp.v1.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.google.android.gms.maps.model.LatLng
import com.sp.v1.MapsActivity

/**
 * Utility class for handling navigation to parking spots
 */
object NavigationUtils {
    /**
     * Navigate to a parking spot using Google Maps
     * @param context The context to start the navigation from
     * @param latitude The latitude of the destination
     * @param longitude The longitude of the destination
     * @param spotName The name of the parking spot (optional)
     */
    fun navigateToSpot(context: Context, latitude: Double, longitude: Double, spotName: String? = null) {
        val uri = Uri.parse("google.navigation:q=$latitude,$longitude")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }

        if (isGoogleMapsInstalled(context)) {
            context.startActivity(mapIntent)
        } else {
            Toast.makeText(context, "Google Maps is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Open Google Maps with the parking spot location
     * @param context The context to start the activity from
     * @param latitude The latitude of the spot
     * @param longitude The longitude of the spot
     * @param spotName The name of the parking spot
     */
    fun openInMaps(context: Context, latitude: Double, longitude: Double, spotName: String) {
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude($spotName)")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        context.startActivity(intent)
    }

    /**
     * Open the MapsActivity with navigation to a specific spot
     * @param context The context to start the activity from
     * @param latitude The latitude of the spot
     * @param longitude The longitude of the spot
     * @param spotName The name of the parking spot
     */
    fun openMapsActivity(context: Context, latitude: Double, longitude: Double, spotName: String) {
        val intent = Intent(context, MapsActivity::class.java).apply {
            putExtra("latitude", latitude)
            putExtra("longitude", longitude)
            putExtra("spotName", spotName)
            putExtra("navigate", true)
        }
        context.startActivity(intent)
    }

    /**
     * Check if Google Maps is installed
     */
    private fun isGoogleMapsInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.google.android.apps.maps", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
} 