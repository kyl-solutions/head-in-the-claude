package com.kylsolutions.hitc

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ScreenshotHelper(
    private val activity: AppCompatActivity,
    private val onScreenshotCaptured: (File) -> Unit
) {

    private var imageUri: Uri? = null
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    fun initialize() {
        // Register camera launcher
        takePictureLauncher = activity.registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            if (success && imageUri != null) {
                handleCapturedImage(imageUri!!)
            }
        }

        // Register permission launcher
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                launchCamera()
            } else {
                showPermissionDeniedDialog()
            }
        }
    }

    fun captureScreenshot() {
        when {
            hasCameraPermission() -> launchCamera()
            else -> requestCameraPermission()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val photoFile = createImageFile()
        imageUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            photoFile
        )
        takePictureLauncher.launch(imageUri)
    }

    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val imageFileName = "HITC_$timestamp"
        val storageDir = activity.getExternalFilesDir(null)
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    private fun handleCapturedImage(uri: Uri) {
        try {
            val inputStream = activity.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            // Show preview dialog
            showImagePreview(bitmap, uri)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showImagePreview(bitmap: Bitmap, uri: Uri) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("Screenshot Captured")
        builder.setMessage("Send this screenshot to Claude?\n\n(Future: Will upload to conversation)")

        // TODO: Add ImageView to dialog to show preview
        // For now, just confirm

        builder.setPositiveButton("Send") { dialog, _ ->
            val file = File(uri.path ?: return@setPositiveButton)
            onScreenshotCaptured(file)
            dialog.dismiss()
        }

        builder.setNegativeButton("Retake") { dialog, _ ->
            dialog.dismiss()
            launchCamera()
        }

        builder.setNeutralButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        builder.show()
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Camera Permission Required")
            .setMessage("HIT-C needs camera access to capture screenshots.\n\nPlease enable it in Settings.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
