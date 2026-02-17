package com.kylsolutions.hitc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>
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

        // Register image picker launcher (gallery/files)
        pickImageLauncher = activity.registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                handlePickedImage(uri)
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

    fun pickImage() {
        pickImageLauncher.launch("image/*")
    }

    fun showAttachOptions() {
        val options = arrayOf("Camera", "Gallery / Files")
        AlertDialog.Builder(activity)
            .setTitle("Attach Image")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> captureScreenshot()
                    1 -> pickImage()
                }
            }
            .show()
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
            if (bitmap != null) {
                val file = File(uri.path ?: return)
                showImagePreview(bitmap, file)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handlePickedImage(uri: Uri) {
        try {
            val inputStream = activity.contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap != null) {
                // Save picked image to temp file for the send pipeline
                val tempFile = createImageFile()
                FileOutputStream(tempFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                showImagePreview(bitmap, tempFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showImagePreview(bitmap: Bitmap, file: File) {
        val imageView = ImageView(activity).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(imageView)
        }

        AlertDialog.Builder(activity)
            .setTitle("Send Image")
            .setView(container)
            .setPositiveButton("Send") { dialog, _ ->
                onScreenshotCaptured(file)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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
