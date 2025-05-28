package com.example.stepwallpaperprojectfinal.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord

class HealthConnectManager(private val context: Context) {

    val healthConnectClient: HealthConnectClient? by lazy {
            try {
                HealthConnectClient.getOrCreate(context.applicationContext)
            } catch (e: Exception) {
                // Can happen if HC is in a bad state or during Robolectric tests
                e.printStackTrace()
                null
            }
    }

    /**
     * Returns the SDK status.
     * SDK_AVAILABLE: Health Connect is available and ready.
     * SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED: HC needs to be installed or updated.
     * SDK_UNAVAILABLE: HC is not available on this device (e.g., old Android version without HC apk).
     */
    fun getSdkStatus(): Int {
        return HealthConnectClient.getSdkStatus(context)
    }

    /**
     * Creates an Intent to guide the user to install or update Health Connect.
     */
    fun getInstallHealthConnectIntent(): Intent {
        val uri = Uri.parse("market://details")
            .buildUpon()
            .appendQueryParameter("id", "com.google.android.apps.healthdata")
            .appendQueryParameter("url", "healthconnect://onboarding") // Deep link to HC onboarding
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    // Define the set of permissions your app needs
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class)
        // Add other permissions here if needed in the future
    )

    // Creates the contract for the permission launcher.
    // The ActivityResultLauncher using this will be in the Composable.
    fun requestPermissionsActivityContract(): ActivityResultContract<Set<String>, Set<String>> {
        return PermissionController.createRequestPermissionResultContract()
    }

    /**
     * Checks if all required Health Connect permissions have been granted.
     * This is a suspend function because it interacts with the HealthConnectClient.
     */
    suspend fun hasAllPermissions(): Boolean {
        return healthConnectClient?.permissionController?.getGrantedPermissions()?.containsAll(permissions) == true
    }
}