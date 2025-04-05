package com.example.stepwallpaperprojectfinal.image

import android.graphics.*
import androidx.core.graphics.applyCanvas
import kotlin.math.roundToInt
import java.util.Collections // For shuffling
import java.util.Random // For seeding random

object ImageProcessor {

    // --- Configuration ---
    private const val MASK_WIDTH = 144 // Low resolution width for the mask
    private const val MASK_HEIGHT = 256// Low resolution height for the mask
    private const val MASK_PIXEL_COUNT = MASK_WIDTH * MASK_HEIGHT

    // --- Cache for shuffled coordinates ---
    // Simple cache to avoid recalculating shuffled list for the same seed repeatedly
    private var cachedSeed: Long? = null
    private var cachedShuffledCoordinates: List<Pair<Int, Int>>? = null

    /**
     * Generates a list of coordinates (x, y) for the mask, shuffled deterministically based on a seed.
     */
    @Synchronized // Ensure thread safety if accessed from multiple threads (e.g., workers)
    private fun getShuffledMaskCoordinates(seed: Long): List<Pair<Int, Int>> {
        // Return cached list if seed matches
        if (seed == cachedSeed && cachedShuffledCoordinates != null) {
            return cachedShuffledCoordinates!!
        }

        // Generate coordinates if not cached or seed changed
        val coordinates = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until MASK_HEIGHT) {
            for (x in 0 until MASK_WIDTH) {
                coordinates.add(Pair(x, y))
            }
        }

        // Shuffle deterministically using the seed
        Collections.shuffle(coordinates, Random(seed))

        // Update cache
        cachedSeed = seed
        cachedShuffledCoordinates = coordinates
        return coordinates
    }

    /**
     * Generates the revealed bitmap based on progress using a low-resolution mask.
     *
     * @param sourceBitmap The original high-resolution image.
     * @param progress Progress value between 0.0f and 1.0f.
     * @param seed A seed for deterministic random shuffling of reveal pixels.
     * @return A new Bitmap representing the revealed state, or null on error.
     */
    fun generateRevealedBitmap(sourceBitmap: Bitmap?, progress: Float, seed: Long): Bitmap? {
        if (sourceBitmap == null || sourceBitmap.isRecycled) {
            println("Error: Source bitmap is null or recycled.")
            return null
        }

        // Clamp progress
        val clampedProgress = progress.coerceIn(0.0f, 1.0f)

        // --- 1. Create/Update the Low-Resolution Mask ---
        val shuffledCoords = getShuffledMaskCoordinates(seed)
        val pixelsToRevealOnMask = (MASK_PIXEL_COUNT * clampedProgress).roundToInt()

        // Create the mask bitmap (ARGB_8888 allows easy drawing with colors)
        // Initialize with TRANSPARENT (0x00000000)
        val maskBitmap = Bitmap.createBitmap(MASK_WIDTH, MASK_HEIGHT, Bitmap.Config.ARGB_8888)
        maskBitmap.eraseColor(Color.BLACK) // Ensure it starts transparent

        // Apply revealed pixels (draw opaque white onto the transparent mask)
        maskBitmap.applyCanvas {
            val paint = Paint().apply { color = Color.TRANSPARENT } // Opaque color to reveal
            for (i in 0 until pixelsToRevealOnMask) {
                if (i < shuffledCoords.size) { // Bounds check
                    val (x, y) = shuffledCoords[i]
                    // Draw a single point - might be slow, drawing rects could be faster
                    //drawPoint(x.toFloat(), y.toFloat(), paint)
                    // Alternative:
                    maskBitmap.setPixel(x, y, Color.TRANSPARENT) //outside applyCanvas
                }
            }
        }


        // --- 2. Compose Final Image ---
        // Create the output bitmap, matching source dimensions, initially black
        val finalBitmap = Bitmap.createBitmap(sourceBitmap.width, sourceBitmap.height, sourceBitmap.config ?: Bitmap.Config.ARGB_8888)
        finalBitmap.eraseColor(Color.BLACK) // Start with black background

        val canvas = Canvas(finalBitmap)

        // Paint for drawing the mask scaled up *without* filtering (blocky)
        val maskPaint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false // Nearest-neighbor scaling
        }

        // Paint for drawing the source image, using the mask via PorterDuff SRC_IN
        // SRC_IN: Keeps source pixels only where destination (mask) pixels are opaque
        val imagePaint = Paint().apply {
            isAntiAlias = false // Keep consistent with mask paint
            isFilterBitmap = false // Keep consistent with mask paint
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }

        // Define rectangles for scaling the mask
        val maskSrcRect = Rect(0, 0, MASK_WIDTH, MASK_HEIGHT) // Source is the whole mask
        val finalDestRect = Rect(0, 0, sourceBitmap.width, sourceBitmap.height) // Destination is the whole final bitmap


        canvas.drawBitmap(sourceBitmap, 0f, 0f, imagePaint)
        // Composition Steps (SRC_IN):
        // 1. Draw the scaled mask onto the (initially black) final bitmap
        canvas.drawBitmap(maskBitmap, maskSrcRect, finalDestRect, maskPaint)
        // 2. Draw the source image onto the same canvas. Because of SRC_IN mode,
        //    it will only appear where the scaled mask was drawn (opaque parts).
        //canvas.drawBitmap(sourceBitmap, 0f, 0f, imagePaint)


        // --- 3. Cleanup ---
        // Recycle the intermediate mask bitmap as it's no longer needed
        maskBitmap.recycle()

        return finalBitmap
    }
}