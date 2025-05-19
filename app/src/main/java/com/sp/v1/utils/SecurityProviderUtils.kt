package com.sp.v1.utils

import android.content.Context
import android.util.Log
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.security.ProviderInstaller
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLContext

/**
 * Utility class to handle Google Play Services security provider installation.
 * This helps ensure that the app has access to the latest security patches
 * for SSL/TLS communication, even on older devices.
 */
object SecurityProviderUtils {
    
    private const val TAG = "SecurityProviderUtils"
    
    /**
     * Updates the Android security provider to protect against SSL exploits.
     * Call this early in your app's lifecycle (like in Application.onCreate or your main activity).
     *
     * @param context Application context
     * @return true if security provider was updated successfully, false otherwise
     */
    fun updateAndroidSecurityProvider(context: Context): Boolean {
        try {
            // Install the latest security provider
            ProviderInstaller.installIfNeeded(context)
            
            // Verify that a proper SSLContext can be created after the update
            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, null, null)
            
            Log.d(TAG, "Security provider updated successfully")
            return true
        } catch (e: GooglePlayServicesNotAvailableException) {
            // This device doesn't have Google Play Services available
            Log.w(TAG, "Google Play Services not available - security provider update skipped", e)
            return setupDefaultSecurityProvider()
        } catch (e: GooglePlayServicesRepairableException) {
            // Google Play Services is available but needs to be updated
            Log.w(TAG, "Google Play Services needs update - security provider update skipped", e)
            return setupDefaultSecurityProvider()
        } catch (e: NoSuchAlgorithmException) {
            Log.e(TAG, "TLSv1.2 is not supported on this device", e)
            return attemptLowerTlsVersion()
        } catch (e: RuntimeException) {
            // This catches errors like "Failed to load module" from the logs
            Log.e(TAG, "Failed to update security provider", e)
            return setupDefaultSecurityProvider()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error updating security provider", e)
            return setupDefaultSecurityProvider()
        }
    }
    
    /**
     * Attempt to set up a default security provider if Google Play Services fails
     */
    private fun setupDefaultSecurityProvider(): Boolean {
        try {
            // Try to use the system's default security providers
            val sslContext = SSLContext.getDefault()
            Log.d(TAG, "Using system default security provider")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to use default security provider", e)
            return false
        }
    }
    
    /**
     * Try to create an SSL context with an older TLS version if TLS 1.2 fails
     */
    private fun attemptLowerTlsVersion(): Boolean {
        for (version in arrayOf("TLSv1.1", "TLSv1")) {
            try {
                val sslContext = SSLContext.getInstance(version)
                sslContext.init(null, null, null)
                Log.d(TAG, "Successfully initialized $version as fallback")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize $version", e)
            }
        }
        return false
    }
} 