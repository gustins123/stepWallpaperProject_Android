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
import com.example.stepwallpaperprojectfinal.health.HealthConnectManager


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

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        val prefsRepository = UserPreferencesRepository(appContext)
        val healthConnectManager = HealthConnectManager(appContext) // Instantiate HC Manager

        println("StepCheckAndUpdateWorker: Starting work (with Health Connect)...")

        // --- 1. Check for New Calendar Day ---
        val lastFetchTimestamp = prefsRepository.lastFetchTimestampFlow.firstOrNull()
        val now = System.currentTimeMillis()

        if (lastFetchTimestamp != null && !isSameCalendarDay(lastFetchTimestamp, now)) {
            println("StepCheckAndUpdateWorker: New calendar day detected. Triggering DailyImageFetchWorker.")
            // ... (logic to enqueue DailyImageFetchWorker - KEEP THIS) ...
            val dailyFetchRequest = OneTimeWorkRequestBuilder<DailyImageFetchWorker>().build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                "newDayTriggeredDailyFetch",
                ExistingWorkPolicy.REPLACE,
                dailyFetchRequest
            )
            return Result.success()
        }
        // --- END NEW DAY CHECK ---

        // --- Read Input Data ---
        val forceUpdate = inputData.getBoolean(KEY_FORCE_UPDATE, false) // Default to false
        if (forceUpdate) {
            println("StepCheckAndUpdateWorker: Force update requested via input data.")
        }

        try {
            // --- 2. Read essential data from DataStore & Health Connect ---
            val storedImageUrl = prefsRepository.imageUrlFlow.firstOrNull()
            // This timestamp is for the ImageProcessor seed, representing when the current image was fetched
            val currentImageTimestamp = prefsRepository.lastFetchTimestampFlow.firstOrNull() ?: now

            // Read the step count this worker saved last time (for comparison)
            val previouslySavedDailySteps = prefsRepository.dailyStepsFlow.firstOrNull() ?: 0L

            if (storedImageUrl == null) {
                println("StepCheckAndUpdateWorker: No image URL stored. Skipping.")
                return Result.success()
            }

            // --- Get current steps for today from Health Connect ---
            val stepsFromHealthConnect = healthConnectManager.getStepsToday()

            if (stepsFromHealthConnect == null) {
                // This could be due to permissions revoked, HC error, or HC not installed/available.
                // The getStepsToday() function logs details.
                println("StepCheckAndUpdateWorker: Failed to get steps from Health Connect. Retrying later.")
                return Result.retry() // Retry as HC might become available or permissions granted
            }

            val calculatedDailySteps = stepsFromHealthConnect // This is our new source of truth for current daily steps
            val currentProgress = (calculatedDailySteps / TARGET_STEPS).coerceIn(0.0f, 1.0f)
            // For progress difference, compare against previously saved HC steps
            val previousProgress = (previouslySavedDailySteps / TARGET_STEPS).coerceIn(0.0f, 1.0f)

            println("StepCheckAndUpdateWorker: Steps from HC=$calculatedDailySteps (Previously saved in DataStore=$previouslySavedDailySteps), Progress=$currentProgress")

            // --- 3. Check if Update is Needed ---
            val stepDifference = kotlin.math.abs(calculatedDailySteps - previouslySavedDailySteps)
            val progressDifference = kotlin.math.abs(currentProgress - previousProgress)
            // If steps are very low, and different from what we last saved, it's likely a reset or start of day
            val justResetToLow = calculatedDailySteps < SIGNIFICANT_STEP_DIFF && calculatedDailySteps != previouslySavedDailySteps

            val shouldUpdate = forceUpdate ||
                    stepDifference >= SIGNIFICANT_STEP_DIFF ||
                    progressDifference >= PROGRESS_CHANGE_THRESHOLD ||
                    justResetToLow

            if (shouldUpdate) {
                println("StepCheckAndUpdateWorker: Update condition met (force=$forceUpdate, stepDiff=$stepDifference, progDiff=$progressDifference, justResetToLow=$justResetToLow). Updating wallpaper.")

                val originalBitmap = loadBitmapFromUrl(appContext, storedImageUrl)
                if (originalBitmap == null) {
                    println("StepCheckAndUpdateWorker: Failed to load original bitmap. Retrying.")
                    return Result.retry()
                }

                val revealedBitmap = ImageProcessor.generateRevealedBitmap(
                    originalBitmap,
                    currentProgress,
                    currentImageTimestamp // Seed with the current image's fetch time
                )
                originalBitmap.recycle()

                if (revealedBitmap == null) {
                    println("StepCheckAndUpdateWorker: Failed to generate revealed bitmap. Failing.")
                    return Result.failure()
                }

                val success = setWallpaper(appContext, revealedBitmap)
                revealedBitmap.recycle()

                if (!success) {
                    println("StepCheckAndUpdateWorker: Failed to set wallpaper. Failing.")
                    return Result.failure()
                }
                println("StepCheckAndUpdateWorker: Wallpaper updated successfully.")

            } else {
                println("StepCheckAndUpdateWorker: No significant change or force update. Skipping wallpaper update.")
            }

            // --- 4. Save Updated Daily Steps (from Health Connect) to DataStore ---
            // This is important so the *next* run of this worker can compare for "significant change"
            if (calculatedDailySteps != previouslySavedDailySteps) {
                prefsRepository.saveDailySteps(calculatedDailySteps)
                println("StepCheckAndUpdateWorker: Saved daily steps from HC to DataStore: $calculatedDailySteps")
            }

            return Result.success()

        } catch (e: Exception) {
            println("StepCheckAndUpdateWorker: Error during execution - ${e.message}")
            e.printStackTrace()
            return Result.retry()
        }
    }

    // --- Helper Functions ---

    // --- Helper to check calendar day (could be moved to a common utility) ---
    private fun isSameCalendarDay(millis1: Long, millis2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = millis1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = millis2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

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