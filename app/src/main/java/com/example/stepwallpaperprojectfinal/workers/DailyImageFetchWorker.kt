package com.example.stepwallpaperprojectfinal.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.stepwallpaperprojectfinal.data.UserPreferencesRepository // Need access to repository
import com.example.stepwallpaperprojectfinal.network.RetrofitInstance
import kotlinx.coroutines.delay // For simulating work
import kotlinx.coroutines.flow.firstOrNull
import java.io.IOException
import java.util.Calendar

class DailyImageFetchWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val appContext = applicationContext // Get context for repository
        val prefsRepository = UserPreferencesRepository(appContext)
        val unsplashApiService = RetrofitInstance.api // Get API service
        println("DailyImageFetchWorker: Starting work...")

        // --- 1. Check if a new day has started ---
        val lastFetchTimestamp = prefsRepository.lastFetchTimestampFlow.firstOrNull()
        val now = System.currentTimeMillis()

        if (lastFetchTimestamp == null || !isSameCalendarDay(lastFetchTimestamp, now)) {
            println("DailyImageFetchWorker: New day detected ($now) or first run. Proceeding...")

            // --- 2. Fetch New Image ---
            val fetchedImageUrl: String? = try {
                val response = unsplashApiService.getRandomPhoto(
                    orientation = "portrait",
                    query = null// Pass the potentially null query
                ) // Call the API
                if (response.isSuccessful && response.body() != null) {
                    // Use 'regular' URL, fallback if needed
                    response.body()?.urls?.full
                        ?: response.body()?.urls?.regular
                        ?: response.body()?.urls?.small
                } else {
                    println("DailyImageFetchWorker: API Error ${response.code()} - ${response.message()}")
                    null // Indicate fetch failure
                }
            } catch (e: IOException) {
                println("DailyImageFetchWorker: Network Error - ${e.message}")
                null // Indicate fetch failure
            } catch (e: Exception) {
                println("DailyImageFetchWorker: General Error - ${e.message}")
                e.printStackTrace()
                null // Indicate fetch failure
            }

            // --- 3. Handle Fetch Result ---
            if (fetchedImageUrl != null) {
                println("DailyImageFetchWorker: Successfully fetched new image URL. - $fetchedImageUrl")

                // --- 4. Get Step Baseline ---
                // Read the *last known raw value* saved by the periodic step checker
                val baselineSteps = prefsRepository.latestRawStepsFlow.firstOrNull()
                println("DailyImageFetchWorker: Using step baseline: $baselineSteps")

                // --- 5. Save New Day State ---
                try {
                    prefsRepository.startNewDay(fetchedImageUrl, now, baselineSteps)
                    println("DailyImageFetchWorker: Successfully saved state for the new day.")

                    // --- 6. (Optional) Trigger immediate wallpaper update check ---
                    triggerImmediateStepCheck(appContext)

                    return Result.success()
                } catch (e: Exception) {
                    println("DailyImageFetchWorker: Failed to save new day state - ${e.message}")
                    e.printStackTrace()
                    // Decide if this is retryable or a failure
                    return Result.retry() // Or Result.failure()
                }

            } else {
                // Fetch failed
                println("DailyImageFetchWorker: Image fetch failed. Retrying...")
                return Result.retry() // Ask WorkManager to retry later
            }

        } else {
            // --- Not a new day ---
            println("DailyImageFetchWorker: Work already done for today (Last fetch: $lastFetchTimestamp). Skipping.")
            return Result.success() // Task is done for today.
        }
    }
    // --- Helper Functions ---
    private fun isSameCalendarDay(millis1: Long, millis2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = millis1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = millis2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun triggerImmediateStepCheck(context: Context) {
        val workManager = WorkManager.getInstance(context)
        // Create input data map: Set our key to true
        val inputData = workDataOf(StepCheckAndUpdateWorker.KEY_FORCE_UPDATE to true)
        val updateRequest = OneTimeWorkRequestBuilder<StepCheckAndUpdateWorker>()
            .setInputData(inputData)
            // Add constraints if needed (e.g., battery not low) matching the periodic one?
            .build()
        workManager.enqueueUniqueWork(
            "immediateStepCheck", // Unique name for one-time requests like this
            ExistingWorkPolicy.REPLACE, // Replace any pending immediate check
            updateRequest
        )
        println("DailyImageFetchWorker: Enqueued immediate StepCheckAndUpdateWorker.")
    }
}