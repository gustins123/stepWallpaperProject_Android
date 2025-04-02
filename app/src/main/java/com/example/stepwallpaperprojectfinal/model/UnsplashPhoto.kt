package com.example.stepwallpaperprojectfinal.model

import com.google.gson.annotations.SerializedName

// Represents the main photo object (can add more fields like description, user, etc. later if needed)
data class UnsplashPhoto(
    @SerializedName("id") // Example: if you needed the ID
    val id: String?,
    @SerializedName("urls")
    val urls: UnsplashUrls? // The nested object containing different image URLs
)

// Represents the 'urls' object within the photo data
data class UnsplashUrls(
    @SerializedName("raw")
    val raw: String?,
    @SerializedName("full")
    val full: String?,
    @SerializedName("regular")
    val regular: String?, // Often a good size for general use
    @SerializedName("small")
    val small: String?,
    @SerializedName("thumb")
    val thumb: String?
)