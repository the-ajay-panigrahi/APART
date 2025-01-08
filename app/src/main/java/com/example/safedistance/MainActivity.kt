package com.example.safedistance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.safedistance.ui.theme.SafeDistanceTheme
import com.google.mlkit.vision.camera.CameraSourceConfig
import com.google.mlkit.vision.camera.CameraXSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    companion object {
        const val IMAGE_WIDTH = 1024
        const val IMAGE_HEIGHT = 1024
        const val AVERAGE_EYE_DISTANCE = 63.0f // in mm
        const val SAFE_DISTANCE_THRESHOLD = 500.0f // 50cm in mm
        private const val TAG = "MainActivity"
    }

    private var focalLength: Float = 0f
    private var sensorX: Float = 0f
    private var sensorY: Float = 0f

    private var outputMessage: MutableState<String?> = mutableStateOf("")
    private var isTooClose: MutableState<Boolean> = mutableStateOf(false)
    private var showError: MutableState<Boolean> = mutableStateOf(false)
    private var errorTitle: MutableState<String> = mutableStateOf("")
    private var errorMessage: MutableState<String> = mutableStateOf("")

    private val cameraPermissionRequestLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                initializeParams()
                createCameraSource()
            } else {
                Toast.makeText(
                    this,
                    "Go to settings and enable camera permission to use this app",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SafeDistanceTheme {
                MainScreen()
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionRequestLauncher.launch(Manifest.permission.CAMERA)
        } else {
            initializeParams()
            createCameraSource()
        }
    }

    @Composable
    private fun MainScreen() {
        if (showError.value) {
            ErrorDialog(
                title = errorTitle.value,
                message = errorMessage.value,
                onDismiss = { 
                    showError.value = false
                    finish()
                }
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ScreenDistanceView(outputMessage, isTooClose)
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        errorTitle.value = title
        errorMessage.value = message
        showError.value = true
    }

    @Composable
    private fun ErrorDialog(
        title: String,
        message: String,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = title) },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        )
    }

    @Composable
    private fun ScreenDistanceView(message: MutableState<String?>, isTooClose: MutableState<Boolean>) {
        val backgroundColor = MaterialTheme.colorScheme.background
        val boxColor = if (isTooClose.value) {
            Color(0xFFFF6B6B)
        } else {
            MaterialTheme.colorScheme.surface
        }
        val textColor = MaterialTheme.colorScheme.onSurface

        Column(
            modifier = Modifier
                .background(backgroundColor)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "APART",
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(25.dp))
                    .background(boxColor, shape = RoundedCornerShape(25.dp))
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = message.value ?: "Please look at the front camera",
                        color = textColor,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center
                    )
                    
                    if (isTooClose.value) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "⚠️ Too Close! Please move back (>50cm)",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    private fun createCameraSource() {
        val detector = FaceDetection.getClient(faceDetectorOptions())
        try {
            val config = CameraSourceConfig.Builder(this, detector) { result ->
                result.addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        outputMessage.value = "No face detected"
                        return@addOnSuccessListener
                    }
                    
                    for (face in faces) {
                        val leftEyePos = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                        val rightEyePos = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

                        if (leftEyePos != null && rightEyePos != null) {
                            val deltaX = abs(leftEyePos.x - rightEyePos.x)
                            val deltaY = abs(leftEyePos.y - rightEyePos.y)

                            val distanceInMm = if (deltaX >= deltaY) {
                                focalLength * (AVERAGE_EYE_DISTANCE / sensorX) * (IMAGE_WIDTH / deltaX)
                            } else {
                                focalLength * (AVERAGE_EYE_DISTANCE / sensorY) * (IMAGE_HEIGHT / deltaY)
                            }

                            // Convert mm to cm for display
                            val distanceInCm = distanceInMm / 10.0f

                            isTooClose.value = distanceInMm < SAFE_DISTANCE_THRESHOLD
                            outputMessage.value = "Distance: %.1f cm".format(distanceInCm)
                        }
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed: ${e.message}")
                }
            }
            .setFacing(CameraSourceConfig.CAMERA_FACING_FRONT)
            .build()

            val cameraSource = CameraXSource(config)
            cameraSource.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera source: ${e.message}")
            showErrorDialog("Error", "Could not start camera source")
        }
    }

    private fun faceDetectorOptions(): FaceDetectorOptions {
        return FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    }

    private fun initializeParams() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = getFrontCameraId(cameraManager)

        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

            if (focalLengths != null && focalLengths.isNotEmpty()) {
                focalLength = focalLengths[0]
            }

            if (sensorSize != null) {
                sensorX = sensorSize.width
                sensorY = sensorSize.height
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing camera parameters: ${e.message}")
            showErrorDialog("Error", "Could not initialize camera parameters")
        }
    }

    private fun getFrontCameraId(cameraManager: CameraManager): String {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return cameraId
            }
        }
        throw NoFrontCameraException()
    }
}

class NoFrontCameraException : Exception("No front camera available on this device")
