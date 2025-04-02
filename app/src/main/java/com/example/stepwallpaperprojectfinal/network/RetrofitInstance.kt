package com.example.stepwallpaperprojectfinal.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor // Optional logging
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitInstance {

    private const val BASE_URL = "https://api.unsplash.com/"

    // Optional: Create a logging interceptor for debugging
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY // Log request/response body
    }

    // Optional: Add the logger to an OkHttpClient
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor) // Add the interceptor here
        .build()

    // Lazy initialization of the Retrofit instance
    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient) // Use the custom client with logging (Optional)
            .addConverterFactory(GsonConverterFactory.create()) // Use Gson for JSON parsing
            .build()
    }

    // Lazy initialization of the API service interface implementation
    val api: UnsplashApiService by lazy {
        retrofit.create(UnsplashApiService::class.java)
    }
}