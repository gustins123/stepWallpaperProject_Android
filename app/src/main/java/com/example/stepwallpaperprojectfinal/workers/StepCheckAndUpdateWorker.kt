package com.example.stepwallpaperprojectfinal.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stepwallpaperprojectfinal.data.UserPreferencesRepository // Need preferences
import com.example.stepwallpaperprojectfinal.image.ImageProcessor // Need processor
import kotlinx.coroutines.delay // For simulating work
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import java.util.Calendar // For isSameCalendarDay helper
import androidx.work.OneTimeWorkRequestBuilder // For triggering Daily worker
import androidx.work.ExistingWorkPolicy // For unique one-time work
import androidx.work.WorkManager


class StepCheckAndUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TARGET_STEPS = 8000f // Daily step goal as float for progress calculation
        const val SIGNIFICANT_STEP_DIFF = 50 // Update wallpaper only if steps change by this much
        const val PROGRESS_CHANGE_THRESHOLD = 0.005 // Or update if progress % changes by 0.5%
        const val KEY_FORCE_UPDATE = "force_update" // Key for input data
    }

    // Helper function for calendar day check (can be moved to a utility object)
    private fun isSameCalendarDay(millis1: Long, millis2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = millis1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = millis2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    // Helper for calculating daily steps
    private fun calculateDailySteps(latestRawSteps: Float?, storedBaseline: Float?): Long {
        if (latestRawSteps == null || storedBaseline == null) return 0L
        return max(0f, latestRawSteps - storedBaseline).toLong()
    }

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        val prefsRepository = UserPreferencesRepository(appContext)
        println("StepCheckAndUpdateWorker: Starting work...")

        // --- 1. NEW: Check for New Calendar Day ---
        val lastFetchTimestamp = prefsRepository.lastFetchTimestampFlow.firstOrNull()
        val now = System.currentTimeMillis()

        if (lastFetchTimestamp != null && !isSameCalendarDay(lastFetchTimestamp, now)) {
            println("StepCheckAndUpdateWorker: New calendar day detected (current: $now, last fetch: $lastFetchTimestamp).")
            println("StepCheckAndUpdateWorker: Triggering DailyImageFetchWorker for new day setup.")

            val dailyFetchRequest = OneTimeWorkRequestBuilder<DailyImageFetchWorker>()
                // No specific input data needed here for DailyImageFetchWorker, it re-evaluates date itself
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                "newDayTriggeredDailyFetch", // Unique name for this specific one-time trigger
                ExistingWorkPolicy.REPLACE,  // If one is already pending from this trigger, replace it
                dailyFetchRequest
            )
            // This worker instance has done its job by delegating new day tasks.
            return Result.success()
        }
        // --- END NEW ---

        // --- Read Input Data ---
        val forceUpdate = inputData.getBoolean(KEY_FORCE_UPDATE, false) // Default to false
        if (forceUpdate) {
            println("StepCheckAndUpdateWorker: Force update requested via input data.")
        }

        try {
            // --- 1. Read necessary data from DataStore ---
            // Use firstOrNull to get the current value non-blockingly within the coroutine
            val storedImageUrl = prefsRepository.imageUrlFlow.firstOrNull()
            val storedTimestamp = prefsRepository.lastFetchTimestampFlow.firstOrNull() ?: 0L // Seed for processor
            val currentImageTimestamp = prefsRepository.lastFetchTimestampFlow.firstOrNull() ?: now  // Get the timestamp for the *current* image for the ImageProcessor seed
            val storedDailySteps = prefsRepository.dailyStepsFlow.firstOrNull() ?: 0L // Last calculated daily steps
            val storedBaseline = prefsRepository.stepBaselineFlow.firstOrNull() // Raw value when day started
            val latestRawSteps = prefsRepository.latestRawStepsFlow.firstOrNull() // Most recent raw value


            if (storedImageUrl == null) {
                println("StepCheckAndUpdateWorker: No image URL stored. Skipping wallpaper update.")
                // If no image, no point calculating steps for reveal? Maybe still save calculated steps?
                // Let's skip entirely for now. Daily worker should provide URL.
                return Result.success()
            }

            if (latestRawSteps == null || storedBaseline == null) {
                println("StepCheckAndUpdateWorker: Missing step data (baseline or latest raw). Cannot calculate progress accurately.")
                // Should we still try to save 0 daily steps? Or wait for baseline?
                // Let's save 0 daily steps if baseline isn't set, indicating day just started maybe.
                if (storedBaseline == null && storedDailySteps != 0L) {
                    prefsRepository.saveDailySteps(0L) // Reset if baseline is missing
                }
                return Result.success() // Or retry if we expect data soon? Success is safer.
            }


            // --- 2. Calculate Current Daily Steps & Progress ---
            // Simple approach: steps = max(0, current_raw - baseline_at_start)
            val calculatedDailySteps = calculateDailySteps(latestRawSteps, storedBaseline)
            val currentProgress = (calculatedDailySteps / TARGET_STEPS).coerceIn(0.0f, 1.0f)
            val previousProgress = (storedDailySteps / TARGET_STEPS).coerceIn(0.0f, 1.0f)

            println("StepCheckAndUpdateWorker: RawSteps=$latestRawSteps, Baseline=$storedBaseline, CalcDaily=$calculatedDailySteps (PrevSaved=$storedDailySteps), Progress=$currentProgress")


            // --- 3. Check if Update is Needed ---
            val stepDifference = kotlin.math.abs(calculatedDailySteps - storedDailySteps)
            val progressDifference = kotlin.math.abs(currentProgress - previousProgress)
            // Check if calculated steps are now effectively zero AND different from before this run started
            val justResetToZero = calculatedDailySteps < SIGNIFICANT_STEP_DIFF && calculatedDailySteps != storedDailySteps

            // Update if: force flag is set OR step/progress change is significant OR steps just reset to zero/low
            val shouldUpdate = forceUpdate ||
                    stepDifference >= SIGNIFICANT_STEP_DIFF ||
                    progressDifference >= PROGRESS_CHANGE_THRESHOLD ||
                    justResetToZero

            // Only update if steps changed significantly OR progress crossed a threshold
            if (shouldUpdate) {
                // Add forceUpdate status to the log for clarity
                println("StepCheckAndUpdateWorker: Update condition met (force=$forceUpdate, stepDiff=$stepDifference, progDiff=$progressDifference, justReset=$justResetToZero). Updating wallpaper.")

                // --- 4. Load Original Bitmap ---
                val originalBitmap = loadBitmapFromUrl(appContext, storedImageUrl)
                if (originalBitmap == null) {
                    println("StepCheckAndUpdateWorker: Failed to load original bitmap from URL. Retrying.")
                    return Result.retry()
                }

                // --- 5. Generate Revealed Bitmap ---
                val revealedBitmap = ImageProcessor.generateRevealedBitmap(
                    originalBitmap,
                    currentProgress,
                    currentImageTimestamp // Use the timestamp of the current image as seed
                )
                originalBitmap.recycle() // Recycle after processor is done with it

                // Recycle original bitmap after use if possible (check ImageProcessor doesn't hold reference)
                // originalBitmap.recycle() // Be cautious with recycling if bitmap is cached by Coil/etc.

                if (revealedBitmap == null) {
                    println("StepCheckAndUpdateWorker: Failed to generate revealed bitmap. Retrying?")
                    // Maybe fail permanently if processor fails consistently?
                    return Result.failure() // Or retry? Failure might be better here.
                }

                // --- 6. Set Wallpaper ---
                val success = setWallpaper(appContext, revealedBitmap)

                // Recycle revealed bitmap after setting it
                revealedBitmap.recycle()

                if (!success) {
                    println("StepCheckAndUpdateWorker: Failed to set wallpaper. Retrying?")
                    // Might be permission issue (though declared) or system issue.
                    return Result.failure() // Failure might be appropriate.
                }
                println("StepCheckAndUpdateWorker: Wallpaper updated successfully.")

            } else {
                println("StepCheckAndUpdateWorker: No significant change / force update needed. Skipping wallpaper update.")
            }

            // --- 7. Save Updated Calculated Daily Steps ---
            // Always save the latest calculated value, even if wallpaper wasn't updated
            if (calculatedDailySteps != storedDailySteps) {
                prefsRepository.saveDailySteps(calculatedDailySteps)
                println("StepCheckAndUpdateWorker: Saved updated daily steps: $calculatedDailySteps")
            }

            return Result.success()

        } catch (e: Exception) {
            println("StepCheckAndUpdateWorker: Error during execution - ${e.message}")
            e.printStackTrace()
            return Result.retry() // Retry on unexpected errors
        }

        println("StepCheckAndUpdateWorker: Work finished.")
        // For now, always return success. Add error handling later.
        return Result.success()
    }

    // --- Helper Functions ---

    private suspend fun loadBitmapFromUrl(context: Context, imageUrl: String): Bitmap? {
        return withContext(Dispatchers.IO) { // Ensure network/disk IO is off main thread
            val imageLoader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false) // Need software bitmap for processor/wallpaper
                .build()
            try {
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    // Need to convert drawable to bitmap. Ensure it's mutable if needed? Processor creates new one anyway.
                    result.drawable.toBitmap() // Use extension function
                } else {
                    println("Coil load failed: ${(result as? coil.request.ErrorResult)?.throwable?.message}")
                    null
                }
            } catch (e: IOException) {
                println("Coil load IO Exception: ${e.message}")
                null
            }
            catch (e: Exception) {
                println("Coil load General Exception: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun setWallpaper(context: Context, bitmap: Bitmap): Boolean {
        return withContext(Dispatchers.IO) { // Wallpaper setting can block
            try {
                val wallpaperManager = WallpaperManager.getInstance(context)
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                true
            } catch (e: SecurityException) {
                println("SetWallpaper Error: Permission denied? ${e.message}")
                e.printStackTrace()
                false
            } catch (e: Exception) {
                println("SetWallpaper Error: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }
}