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
                    imageScreen()
                }
            }
        }
    }
}

@Composable
fun PermissionScreen() {
    val context = LocalContext.current
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

    // --- UI ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Welcome to Step Wallpaper!", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text("This app needs the following permissions to function:")
        Spacer(modifier = Modifier.height(8.dp))
        Text("- Internet (To download images - Granted at install)")
        Text("- Set Wallpaper (To update wallpaper - Declared)")
        Text("- Receive Boot Completed (To work reliably after reboot - Granted at install)")

        // Conditionally explain runtime permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Text("- Activity Recognition (To count steps - Requires Runtime Grant)")
        }
        // Add explanation for POST_NOTIFICATIONS if needed

        Spacer(modifier = Modifier.height(32.dp))

        if (permissionsGrantedState || runtimePermissions.isEmpty()) { // Granted or no runtime perms needed
            Text(
                "All necessary runtime permissions granted!",
                color = Color(0xFF4CAF50) // Green color
            )
            Spacer(modifier = Modifier.height(16.dp))
            // TODO: Add a button here later to navigate to the main app screen/logic
            // Button(onClick = { /* Navigate or enable main features */ }) {
            //     Text("Continue")
            // }
        } else {
            Text(
                "Please grant the required runtime permissions.",
                color = MaterialTheme.colorScheme.error // Use theme's error color
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                // Launch the permission request
                permissionsLauncher.launch(runtimePermissions)
            }) {
                Text("Grant Runtime Permissions")
            }
        }
    }

    // --- Dialog for Permanently Denied Permissions ---
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

@Composable
fun imageScreen() { // Renaming to MainScreen or creating a new one might be better later
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope() // Scope for launching coroutines

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
                    val fetchedUrl = response.body()?.urls?.regular
                        ?: response.body()?.urls?.full
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

    // --- UI ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top // Change arrangement to see content better
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
        // ...(Permission explanations from Chapter 1)...

        Divider(modifier = Modifier.padding(vertical = 16.dp)) // Separator

        // --- Image Fetch Section ---
        Text("Image Fetch Test:", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = ::fetchRandomImage, enabled = !isLoading) {
            Text("Fetch Random Image")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else if (errorMessage != null) {
            Text("Error: $errorMessage", color = MaterialTheme.colorScheme.error)
        } else if (imageUrl != null) {
            Text("Fetched URL:", style = MaterialTheme.typography.bodySmall)
            Text(
                imageUrl!!,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3, // Prevent super long URLs taking too much space
                overflow = TextOverflow.Ellipsis
            )
            // TODO: In Chapter 3, we'll load this URL into an ImageView/Coil
        } else {
            Text("Click the button to fetch an image.")
        }

        // ...(Spacer to push content up if needed)...
    }

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