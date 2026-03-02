package com.kylsolutions.kylidescope

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

/**
 * Attachment data returned from the picker.
 * Carries the temp file and its detected MIME type.
 */
data class AttachmentResult(
    val file: File,
    val mediaType: String
)

class ScreenshotHelper(
    private val activity: AppCompatActivity,
    private val onScreenshotCaptured: (File) -> Unit,
    private val onFileAttached: ((AttachmentResult) -> Unit)? = null
) {

    companion object {
        /** MIME types the Claude API actually accepts for vision/documents */
        val SUPPORTED_MEDIA_TYPES = setOf(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "application/pdf"
        )

        private const val MAX_FILE_SIZE = 5 * 1024 * 1024 // 5 MB API limit
    }

    private var imageUri: Uri? = null
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private lateinit var pickFileLauncher: ActivityResultLauncher<Array<String>>
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

        // Register file picker (supports multiple MIME types)
        pickFileLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                handlePickedFile(uri)
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

    fun pickFile() {
        // Allow images + PDFs
        pickFileLauncher.launch(arrayOf(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "application/pdf"
        ))
    }

    fun showAttachOptions() {
        val options = arrayOf("Camera", "Gallery / Files")
        AlertDialog.Builder(activity)
            .setTitle("Attach File")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> captureScreenshot()
                    1 -> pickFile()
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
        val photoFile = createTempFile(".jpg")
        imageUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            photoFile
        )
        takePictureLauncher.launch(imageUri)
    }

    private fun createTempFile(extension: String): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "KYLID_$timestamp"
        val storageDir = activity.getExternalFilesDir(null)
        return File.createTempFile(fileName, extension, storageDir)
    }

    private fun handleCapturedImage(uri: Uri) {
        try {
            val inputStream = activity.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap != null) {
                val file = File(uri.path ?: return)
                showImagePreview(bitmap, file, "image/jpeg")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Handle a file picked from the document picker.
     * Preserves original format — no forced JPEG conversion.
     */
    private fun handlePickedFile(uri: Uri) {
        try {
            // Detect MIME type from ContentResolver (most reliable)
            val mimeType = activity.contentResolver.getType(uri) ?: run {
                // Fallback: detect from file extension
                val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }

            // Validate supported type
            if (mimeType !in SUPPORTED_MEDIA_TYPES) {
                showUnsupportedFormatDialog(mimeType)
                return
            }

            // Read file bytes and check size
            val inputStream = activity.contentResolver.openInputStream(uri) ?: return
            val bytes = inputStream.readBytes()
            inputStream.close()

            if (bytes.size > MAX_FILE_SIZE) {
                showFileTooLargeDialog(bytes.size)
                return
            }

            // Determine file extension from MIME type
            val extension = when (mimeType) {
                "image/jpeg" -> ".jpg"
                "image/png" -> ".png"
                "image/gif" -> ".gif"
                "image/webp" -> ".webp"
                "application/pdf" -> ".pdf"
                else -> ".bin"
            }

            // Save to temp file preserving original format
            val tempFile = createTempFile(extension)
            FileOutputStream(tempFile).use { out -> out.write(bytes) }

            // Show appropriate preview
            if (mimeType.startsWith("image/")) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    showImagePreview(bitmap, tempFile, mimeType)
                }
            } else {
                // PDF or other document — show file info preview
                showDocumentPreview(tempFile, mimeType, bytes.size)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showImagePreview(bitmap: Bitmap, file: File, mediaType: String) {
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
                deliverAttachment(file, mediaType)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Preview dialog for non-image files (PDF, etc.).
     */
    private fun showDocumentPreview(file: File, mediaType: String, sizeBytes: Int) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val sizeKb = sizeBytes / 1024

        val label = TextView(activity).apply {
            text = "\uD83D\uDCC4  ${file.name}\n${formatMediaType(mediaType)}  •  ${sizeKb} KB"
            textSize = 16f
            setPadding(pad, pad, pad, pad)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
        }

        AlertDialog.Builder(activity)
            .setTitle("Send Document")
            .setView(container)
            .setPositiveButton("Send") { dialog, _ ->
                deliverAttachment(file, mediaType)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Deliver attachment through the new callback if available, otherwise fallback.
     */
    private fun deliverAttachment(file: File, mediaType: String) {
        val callback = onFileAttached
        if (callback != null) {
            callback(AttachmentResult(file, mediaType))
        } else {
            // Fallback to legacy image-only callback
            onScreenshotCaptured(file)
        }
    }

    private fun showUnsupportedFormatDialog(mimeType: String) {
        AlertDialog.Builder(activity)
            .setTitle("Unsupported Format")
            .setMessage("\"$mimeType\" is not supported.\n\nSupported: JPEG, PNG, GIF, WebP, PDF")
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun showFileTooLargeDialog(sizeBytes: Int) {
        val sizeMb = String.format("%.1f", sizeBytes / (1024.0 * 1024.0))
        AlertDialog.Builder(activity)
            .setTitle("File Too Large")
            .setMessage("${sizeMb} MB exceeds the 5 MB limit.\n\nPlease choose a smaller file.")
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun formatMediaType(mediaType: String): String = when (mediaType) {
        "application/pdf" -> "PDF"
        "image/jpeg" -> "JPEG"
        "image/png" -> "PNG"
        "image/gif" -> "GIF"
        "image/webp" -> "WebP"
        else -> mediaType
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Camera Permission Required")
            .setMessage("KYLIDESCOPE needs camera access to capture images.\n\nPlease enable it in Settings.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
