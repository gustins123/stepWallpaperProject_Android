package com.example.stepwallpaperprojectfinal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.* // Use Material 3 components
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.stepwallpaperprojectfinal.ui.theme.StepWallpaperProjectFinalTheme
import androidx.compose.material3.CircularProgressIndicator // For loading state
import androidx.compose.runtime.saveable.rememberSaveable // To save state across config changes
import androidx.compose.ui.text.style.TextOverflow
import com.example.stepwallpaperprojectfinal.network.RetrofitInstance // Import Retrofit instance
import kotlinx.coroutines.launch // Import coroutine launch builder
import java.io.IOException // For exception handling
import android.app.WallpaperManager
import android.graphics.drawable.BitmapDrawable
import android.widget.Toast // Simple feedback for now, Snackbar is better
import androidx.compose.material3.Divider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect // For showing Toast/Snackbar from coroutine
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.ErrorResult
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers // Import Dispatchers
import kotlinx.coroutines.withContext // Import withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StepWallpaperProjectFinalTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    mainScreen()
                }
            }
        }
    }
}

// Helper to check initial permission status
private fun checkAllPermissions(context: Context, permissions: Array<String>): Boolean {
    if (permissions.isEmpty()) return true // No runtime permissions needed
    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

// Helper extension function to get activity from context (useful for shouldShowRequestPermissionRationale)
fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class) // Needed for Scaffold/SnackbarHost
@Composable
fun mainScreen() { // Renaming to MainScreen or creating a new one might be better later
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope() // Scope for launching coroutines
    val lifecycleOwner = LocalLifecycleOwner.current // For observing lifecycle state

    // --- Permissions Handling ---
    // Define permissions needed at runtime based on Android version
    val runtimePermissions = remember {
        mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            // Add POST_NOTIFICATIONS here if you plan to use them on API 33+
            // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            //     add(Manifest.permission.POST_NOTIFICATIONS)
            // }
        }.toTypedArray() // Convert to Array for the launcher
    }

    // State to track if runtime permissions are granted
    var permissionsGrantedState by remember {
        mutableStateOf(checkAllPermissions(context, runtimePermissions))
    }
    // State to track if user has denied permanently (used to guide to settings)
    var showGoToSettingsDialog by remember { mutableStateOf(false) }

    // Prepare the permission request launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissionsResultMap ->
            permissionsGrantedState = permissionsResultMap.values.all { it }
            if (!permissionsGrantedState) {
                // Check if any permission was permanently denied
                val permanentlyDenied = permissionsResultMap.any { (permission, granted) ->
                    !granted && !context.findActivity()?.shouldShowRequestPermissionRationale(permission)!!
                }
                if (permanentlyDenied && runtimePermissions.isNotEmpty()) { // Check if not empty before showing dialog
                    showGoToSettingsDialog = true
                }
            }
        }
    )

    // --- API Fetching State ---
    var imageUrl by rememberSaveable { mutableStateOf<String?>(null) } // Holds the fetched URL
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // --- Wallpaper Setting State ---
    var isSettingWallpaper by remember { mutableStateOf(false) }
    // Use Snackbar for better feedback than Toast
    val snackbarHostState = remember { SnackbarHostState() }
    var wallpaperResultMessage by remember { mutableStateOf<String?>(null) }

    // --- Effect to show Snackbar when message changes ---
    LaunchedEffect(wallpaperResultMessage) {
        wallpaperResultMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            wallpaperResultMessage = null // Reset message after showing
        }
    }

    // --- API Fetch Function ---
    fun fetchRandomImage() {
        isLoading = true
        errorMessage = null
        imageUrl = null // Clear previous results

        coroutineScope.launch {
            try {
                val response = RetrofitInstance.api.getRandomPhoto() // Call the API
                if (response.isSuccessful && response.body() != null) {
                    // Use 'regular' URL, fallback to 'full' or 'small' if needed
                    val fetchedUrl = response.body()?.urls?.full
                        ?: response.body()?.urls?.regular
                        ?: response.body()?.urls?.small
                    imageUrl = fetchedUrl // Update state on success
                    if (fetchedUrl == null) {
                        errorMessage = "No suitable image URL found in response."
                    }
                } else {
                    // Handle API errors (e.g., rate limit, invalid key)
                    errorMessage = "API Error: ${response.code()} - ${response.message()}"
                    println("API Error Body: ${response.errorBody()?.string()}") // Log error body
                }
            } catch (e: IOException) {
                // Handle network errors (no connection)
                errorMessage = "Network Error: ${e.message}"
            } catch (e: Exception) {
                // Handle other potential errors (JSON parsing, etc.)
                errorMessage = "Error: ${e.message}"
            } finally {
                isLoading = false // Ensure loading stops
            }
        }
    }

    // --- Wallpaper Setting Function ---
    fun setWallpaperFromUrl(url: String) {
        isSettingWallpaper = true
        coroutineScope.launch {
            val imageLoader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false) // Crucial for WallpaperManager compatibility
                .build()

            try {
                // Execute the request and suspend until it's done
                val result = imageLoader.execute(request)

                if (result is SuccessResult) {
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        try {
                            // Perform wallpaper setting on a background thread
                            withContext(Dispatchers.IO) {
                                val wallpaperManager = WallpaperManager.getInstance(context)
                                // You could also set FLAG_LOCK or both:
                                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                            }
                            // Update state back on the main thread
                            withContext(Dispatchers.Main) { wallpaperResultMessage = "Wallpaper set successfully!" }

                        } catch (e: SecurityException) {
                            // Often due to manifest permission missing, though unlikely if declared
                            e.printStackTrace()
                            withContext(Dispatchers.Main) { wallpaperResultMessage = "Permission Error setting wallpaper." }
                        } catch (e: Exception) {
                            // Other errors during setting wallpaper
                            e.printStackTrace()
                            withContext(Dispatchers.Main) { wallpaperResultMessage = "Failed to set wallpaper: ${e.message}" }
                        }
                    } else {
                        withContext(Dispatchers.Main) { wallpaperResultMessage = "Failed to decode image." }
                    }
                } else if (result is ErrorResult) {
                    // Handle Coil loading errors
                    result.throwable.printStackTrace()
                    withContext(Dispatchers.Main) { wallpaperResultMessage = "Failed to load image: ${result.throwable.message}" }
                }
            } catch (e: Exception) {
                // Catch exceptions during the Coil execute() call itself
                e.printStackTrace()
                withContext(Dispatchers.Main) { wallpaperResultMessage = "Image loading failed: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isSettingWallpaper = false }
            }
        }
    }

    // --- UI using Scaffold for Snackbar ---
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues -> // Content padding provided by Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // Apply padding from Scaffold
                .padding(16.dp), // Add your own padding
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text("StepReveal Wallpaper Setup", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            // --- Permission Section ---
            Text("Permissions Status:", style = MaterialTheme.typography.titleMedium)
            if (permissionsGrantedState || runtimePermissions.isEmpty()) {
                Text("Runtime Permissions Granted!", color = Color(0xFF4CAF50))
            } else {
                Text("Runtime Permissions Needed.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { permissionsLauncher.launch(runtimePermissions) }) {
                    Text("Grant Runtime Permissions")
                }
            }
            // ...(Permission explanations)...

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            // --- Image Fetch Section ---
            Text("1. Fetch Image:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = ::fetchRandomImage, enabled = !isLoading) {
                Text("Fetch Random Image")
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (isLoading) {
                CircularProgressIndicator()
            } else if (errorMessage != null) {
                Text("Fetch Error: $errorMessage", color = MaterialTheme.colorScheme.error)
            } else if (imageUrl != null) {
                Text("Fetched URL:", style = MaterialTheme.typography.bodySmall)
                Text(
                    imageUrl!!,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text("Click button to fetch image.")
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            // --- Wallpaper Setting Section ---
            Text("2. Set Wallpaper:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { imageUrl?.let { setWallpaperFromUrl(it) } },
                // Enable only if URL exists AND not currently fetching OR setting
                enabled = imageUrl != null && !isLoading && !isSettingWallpaper
            ) {
                Text("Set Fetched Image as Wallpaper")
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (isSettingWallpaper) {
                CircularProgressIndicator()
            }

            // Removed direct message display here as Snackbar handles it
            // else if (wallpaperSetMessage != null) {
            //    Text(wallpaperSetMessage!!, color = if(wallpaperSetMessage!!.startsWith("Failed")) MaterialTheme.colorScheme.error else Color(0xFF4CAF50) )
            //}

            // ...(Rest of the Column)...
        }
    } // End Scaffold


    // --- Dialog for Permanently Denied Permissions (from Chapter 1) ---
    if (showGoToSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showGoToSettingsDialog = false },
            title = { Text("Permission Required") },
            text = { Text("Activity Recognition permission is required for this app to count steps. Please grant it in app settings.") },
            confirmButton = {
                Button(onClick = {
                    showGoToSettingsDialog = false
                    // Open app settings
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", context.packageName, null)
                    intent.data = uri
                    context.startActivity(intent)
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                Button(onClick = { showGoToSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}