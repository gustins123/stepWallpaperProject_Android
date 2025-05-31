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
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant // For current time
import java.time.ZoneId // For device's timezone
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit // For truncating to start of day

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

    /**
     * Reads the total steps taken today from Health Connect.
     * Returns the total step count, or null if an error occurs or permissions are missing.
     */
    suspend fun getStepsToday(): Long? {
        if (healthConnectClient == null) {
            println("HealthConnectManager: Client is null, cannot read steps.")
            return null
        }
        if (!hasAllPermissions()) {
            println("HealthConnectManager: Read Steps permission not granted, cannot read steps.")
            return null
        }

        try {
            // Define the time range: from the start of today until now
            val zoneId = ZoneId.systemDefault() // Use the device's current timezone
            val startOfToday = ZonedDateTime.now(zoneId).truncatedTo(ChronoUnit.DAYS).toInstant()
            val now = Instant.now()

            // Ensure 'now' is after 'startOfToday' to prevent issues if clocks are weird or testing around midnight
            if (now.isBefore(startOfToday)) {
                println("HealthConnectManager: Current time is before start of today, returning 0 steps.")
                return 0L // Or handle as an error/edge case
            }

            val timeRangeFilter = TimeRangeFilter.between(startOfToday, now)

            val request = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = timeRangeFilter
                // You can add dataOriginFilter here if you only want steps from specific apps,
                // but usually, you want all aggregated steps.
            )

            val response = healthConnectClient!!.readRecords(request) // Client checked for null above
            println("HealthConnectManager: ---- Raw Step Records Received (${response.records.size} records) ----")
            var rawTotalStepsSum = 0L
            for (record in response.records) {
                println("  Raw Record: count=${record.count}, start=${record.startTime}, end=${record.endTime}, sourceApp='${record.metadata.dataOrigin.packageName}', device='${record.metadata.device?.manufacturer} ${record.metadata.device?.model}'")
                rawTotalStepsSum += record.count
            }
            println("HealthConnectManager: Sum of all raw record counts: $rawTotalStepsSum")
            println("HealthConnectManager: ---- End of Raw Records ----")

            // --- De-duplication for records with exact same startTime and endTime ---
            // We'll store the maximum step count found for each unique time slot.
            val stepsByUniqueTimeSlot = mutableMapOf<Pair<Instant, Instant>, Long>()

            for (record in response.records) {
                val timeSlotKey = Pair(record.startTime, record.endTime)
                val currentMaxStepsForSlot = stepsByUniqueTimeSlot[timeSlotKey] ?: 0L // If slot not seen, current max is 0

                // If this record has more steps for the same exact time slot, update it.
                if (record.count > currentMaxStepsForSlot) {
                    stepsByUniqueTimeSlot[timeSlotKey] = record.count
                }
            }

            // Now, sum the steps from our de-duplicated map
            var deDuplicatedTotalSteps = 0L
            println("HealthConnectManager: ---- De-duplicated Step Contributions by Time Slot ----")
            for ((timeSlot, steps) in stepsByUniqueTimeSlot) {
                println("  Slot [${timeSlot.first} to ${timeSlot.second}]: $steps steps")
                deDuplicatedTotalSteps += steps
            }
            println("HealthConnectManager: ---- End of De-duplicated Contributions ----")

            println("HealthConnectManager: De-duplicated total steps today: $deDuplicatedTotalSteps (from $startOfToday to $now)")
            return deDuplicatedTotalSteps
        } catch (e: SecurityException) {
            // This can happen if permissions were revoked after checking but before reading.
            println("HealthConnectManager: SecurityException reading steps - Likely permissions revoked: ${e.message}")
            e.printStackTrace()
        } catch (e: Exception) {
            // Other errors like API issues, dead client, etc.
            println("HealthConnectManager: Error reading steps: ${e.message}")
            e.printStackTrace()
        }
        return null // Return null on any error
    }
}