package com.example.stepwallpaperprojectfinal.network

import com.example.stepwallpaperprojectfinal.BuildConfig // Import generated BuildConfig
import com.example.stepwallpaperprojectfinal.model.UnsplashPhoto
import retrofit2.Response // Use Response for more control over the result
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

interface UnsplashApiService {

    // Define the Authorization header using the API key from BuildConfig
    // Note: Using "Client-ID" is standard for Unsplash API public access
    @Headers("Authorization: Client-ID ${BuildConfig.UNSPLASH_ACCESS_KEY}")
    @GET("photos/random") // Endpoint path
    suspend fun getRandomPhoto(
        // Optional query parameters (can add orientation, query term, etc.)
        //@Query("topics") topics: String = "wallpapers", // Example: fetch landscape photos
        @Query("query") searchTerm: String? = "wallpaper" // Example: search for specific themes
    ): Response<UnsplashPhoto> // Return Response wrapper around your data model
}