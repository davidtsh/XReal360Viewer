package com.xreal360.viewer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.xreal360.viewer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Custom contract to support initial URI
    private class OpenDocumentWithInitialUri : ActivityResultContract<Pair<Array<String>, Uri?>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<Array<String>, Uri?>): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, input.first)
                input.second?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
        }
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == RESULT_OK) intent?.data else null
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) openGallery()
        else Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show()
    }

    // Gallery picker with initial URI support
    private val galleryLauncher = registerForActivityResult(OpenDocumentWithInitialUri()) { uri: Uri? ->
        uri?.let {
            saveLastUri(it)
            openViewer(it)
        }
    }

    companion object {
        const val EXTRA_AUTO_OPEN = "auto_open"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersion.text = "v${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvVersion.text = "v1.0"
        }

        binding.btnPickImage.setOnClickListener { checkPermissionsAndOpen("image/*") }

        if (intent.getBooleanExtra(EXTRA_AUTO_OPEN, false)) {
            checkPermissionsAndOpen("image/*")
        }
    }

    private fun checkPermissionsAndOpen(mimeType: String) {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (mimeType.startsWith("video")) arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val notGranted = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            pendingMime = mimeType
            openGallery()
        } else {
            pendingMime = mimeType
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private var pendingMime = "image/*"

    private fun openGallery() {
        val lastUri = getLastUri()
        galleryLauncher.launch(arrayOf(pendingMime) to lastUri)
    }

    private fun saveLastUri(uri: Uri) {
        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putString("last_uri", uri.toString())
            .apply()
    }

    private fun getLastUri(): Uri? {
        val uriString = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString("last_uri", null) ?: return null
        return Uri.parse(uriString)
    }

    private fun openViewer(uri: Uri) {
        val isVideo = contentResolver.getType(uri)?.startsWith("video") == true
        val intent = Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.EXTRA_URI, uri.toString())
            putExtra(ViewerActivity.EXTRA_IS_VIDEO, isVideo)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }
}
