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
import android.hardware.Sensor // Import Sensor
import android.hardware.SensorEvent // Import SensorEvent
import android.hardware.SensorEventListener // Import SensorEventListener
import android.hardware.SensorManager // Import SensorManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect // Import DisposableEffect
import androidx.compose.material3.OutlinedTextField // Import TextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController // To hide keyboard

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
    val keyboardController = LocalSoftwareKeyboardController.current // Get keyboard controller

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
    var isFetchingImage by remember { mutableStateOf(false) }
    var fetchErrorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

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
        keyboardController?.hide() // Hide keyboard when fetch starts
        isFetchingImage = true
        fetchErrorMessage = null
        imageUrl = null // Clear previous results

        coroutineScope.launch {
            try {
                // Use null if the search query is blank after trimming
                val queryToSend = searchQuery.trim().ifBlank { null }

                val response = RetrofitInstance.api.getRandomPhoto(
                    orientation = "portrait",
                    query = queryToSend        // Pass the potentially null query
                ) // Call the API
                if (response.isSuccessful && response.body() != null) {
                    // Use 'regular' URL, fallback to 'full' or 'small' if needed
                    val fetchedUrl = response.body()?.urls?.full
                        ?: response.body()?.urls?.regular
                        ?: response.body()?.urls?.small
                    imageUrl = fetchedUrl // Update state on success
                    if (fetchedUrl == null) {
                        fetchErrorMessage = "No suitable image URL found in response."
                    }
                } else {
                    // Handle API errors (e.g., rate limit, invalid key)
                    fetchErrorMessage = "API Error: ${response.code()} - ${response.message()}"
                    println("API Error Body: ${response.errorBody()?.string()}") // Log error body
                }
            } catch (e: IOException) {
                // Handle network errors (no connection)
                fetchErrorMessage = "Network Error: ${e.message}"
            } catch (e: Exception) {
                // Handle other potential errors (JSON parsing, etc.)
                fetchErrorMessage = "Error: ${e.message}"
            } finally {
                isFetchingImage = false // Ensure loading stops
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
    // --- Step Counter State & Setup ---
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    val stepCounterSensor: Sensor? = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }
    val sensorAvailable = stepCounterSensor != null

    // State to hold the raw steps read from the sensor (steps since last reboot)
    var rawStepsSinceReboot by remember { mutableStateOf<Long?>(null) }

    // --- Sensor Event Listener ---
    val sensorEventListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
                    val steps = event.values[0].toLong()
                    rawStepsSinceReboot = steps
                    // Log.d("StepCounter", "Raw steps since reboot: $steps")
                    // LATER: Here we'll calculate daily steps based on a stored baseline
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // You might log accuracy changes if needed, but often not critical for step counter
                println("Step counter accuracy changed: $accuracy")
            }
        }
    }

    // --- Register/Unregister Listener ---
    DisposableEffect(sensorManager, stepCounterSensor, sensorAvailable) {
        if (sensorManager == null || stepCounterSensor == null || !sensorAvailable) {
            // No sensor manager or sensor available, do nothing
            println("StepCounter: Sensor Manager or Step Counter Sensor not available for registration.")
        } else {
            // Register the listener
            val registered = sensorManager.registerListener(
                sensorEventListener,
                stepCounterSensor,
                SensorManager.SENSOR_DELAY_NORMAL // Or adjust sampling rate if needed
            )
            if (registered) {
                println("StepCounter: Listener registered successfully.")
            } else {
                println("StepCounter: ERROR - Failed to register listener.")
            }
        }

        // Cleanup function: Will be called when the Composable leaves the composition
        onDispose {
            if (sensorManager != null && stepCounterSensor != null) {
                sensorManager.unregisterListener(sensorEventListener)
                println("StepCounter: Listener unregistered.")
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
                .padding(16.dp) // Add your own padding
                .verticalScroll(rememberScrollState()), // Make column scrollable
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

            // Add the TextField for the query
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Image Query (Optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search // Show search icon on keyboard
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        fetchRandomImage() // Trigger fetch on keyboard search action
                    }
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = ::fetchRandomImage, enabled = !isFetchingImage) {
                Text("Fetch Image")
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (isFetchingImage) {
                CircularProgressIndicator()
            } else if (fetchErrorMessage != null) {
                Text("Fetch Error: $fetchErrorMessage", color = MaterialTheme.colorScheme.error)
            } else if (imageUrl != null) {
                Text("Fetched URL:", style = MaterialTheme.typography.bodySmall)
                Text(
                    imageUrl!!,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                // Show only if not loading and no error
                if(!isFetchingImage && fetchErrorMessage == null) {
                    Text("Enter a query (optional) and click fetch.")
                }
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            // --- Wallpaper Setting Section ---
            Text("2. Set Wallpaper:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { imageUrl?.let { setWallpaperFromUrl(it) } },
                // Enable only if URL exists AND not currently fetching OR setting
                enabled = imageUrl != null && !isFetchingImage && !isSettingWallpaper
            ) {
                Text("Set Fetched Image as Wallpaper")
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (isSettingWallpaper) {
                CircularProgressIndicator()
            }

            // --- Step Counter Section ---
            Text("3. Step Counter:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (sensorAvailable) {
                Text(
                    "Sensor Status: Available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4CAF50) // Green
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Raw Steps (since last reboot): ${rawStepsSinceReboot ?: "Waiting..."}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "(Note: This value resets on device reboot. Daily calculation comes later.)",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Sensor Status: Step Counter Sensor NOT available on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(16.dp)) // Add space at the bottom
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