package com.xreal360.viewer.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.xreal360.viewer.databinding.ActivityMainBinding
import java.util.Locale

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
        private val SUPPORTED_MIME_TYPES = arrayOf("image/*", "video/*")
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
            binding.tvVersion.text = "v1.1.0"
        }

        binding.btnPickImage.setOnClickListener { checkPermissionsAndOpen() }

        if (intent.getBooleanExtra(EXTRA_AUTO_OPEN, false)) {
            checkPermissionsAndOpen()
        }
    }

    private fun checkPermissionsAndOpen() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val notGranted = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            openGallery()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun openGallery() {
        val lastUri = getLastUri()
        galleryLauncher.launch(SUPPORTED_MIME_TYPES to lastUri)
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
        val playlist = buildFolderPlaylist(uri)
        val isVideo = contentResolver.getType(playlist.uris[playlist.index])?.startsWith("video") == true
        val intent = Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.EXTRA_URI, playlist.uris[playlist.index].toString())
            putStringArrayListExtra(ViewerActivity.EXTRA_URIS, ArrayList(playlist.uris.map { it.toString() }))
            putExtra(ViewerActivity.EXTRA_INDEX, playlist.index)
            putExtra(ViewerActivity.EXTRA_IS_VIDEO, isVideo)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private data class MediaPlaylist(
        val uris: List<Uri>,
        val index: Int
    )

    private fun buildFolderPlaylist(selectedUri: Uri): MediaPlaylist {
        val selectedMedia = resolveMediaStoreItem(selectedUri) ?: return MediaPlaylist(listOf(selectedUri), 0)
        val bucketId = queryBucketId(selectedMedia.uri, selectedMedia.isVideo)
            ?: return MediaPlaylist(listOf(selectedMedia.uri), 0)

        val entries = (
            queryBucketMedia(
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                bucketColumn = MediaStore.Images.Media.BUCKET_ID,
                bucketId = bucketId,
                isVideo = false
            ) + queryBucketMedia(
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                bucketColumn = MediaStore.Video.Media.BUCKET_ID,
                bucketId = bucketId,
                isVideo = true
            )
        ).sortedWith(
            compareBy<MediaPlaylistEntry> { it.displayName.lowercase(Locale.getDefault()) }
                .thenBy { it.uri.toString() }
        )

        return if (entries.isEmpty()) {
            MediaPlaylist(listOf(selectedMedia.uri), 0)
        } else {
            val uris = entries.map { it.uri }
            val selectedIndex = entries.indexOfFirst {
                it.id == selectedMedia.id && it.isVideo == selectedMedia.isVideo
            }.let { if (it >= 0) it else 0 }
            MediaPlaylist(uris, selectedIndex)
        }
    }

    private data class MediaPlaylistEntry(
        val uri: Uri,
        val id: Long,
        val isVideo: Boolean,
        val displayName: String
    )

    private fun queryBucketMedia(
        collection: Uri,
        bucketColumn: String,
        bucketId: String,
        isVideo: Boolean
    ): List<MediaPlaylistEntry> {
        val idColumn = MediaStore.MediaColumns._ID
        val displayNameColumn = MediaStore.MediaColumns.DISPLAY_NAME
        val projection = arrayOf(idColumn, displayNameColumn)
        val selection = "$bucketColumn = ?"
        val entries = mutableListOf<MediaPlaylistEntry>()

        contentResolver.query(collection, projection, selection, arrayOf(bucketId), null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(idColumn)
            val displayNameIndex = cursor.getColumnIndexOrThrow(displayNameColumn)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                entries += MediaPlaylistEntry(
                    uri = ContentUris.withAppendedId(collection, id),
                    id = id,
                    isVideo = isVideo,
                    displayName = cursor.getString(displayNameIndex).orEmpty()
                )
            }
        }

        return entries
    }

    private data class MediaStoreItem(
        val uri: Uri,
        val id: Long,
        val isVideo: Boolean
    )

    private fun resolveMediaStoreItem(uri: Uri): MediaStoreItem? {
        val mimeType = contentResolver.getType(uri).orEmpty()
        val mimeLooksVideo = mimeType.startsWith("video")

        if (DocumentsContract.isDocumentUri(this, uri) && uri.authority == "com.android.providers.media.documents") {
            val parts = DocumentsContract.getDocumentId(uri).split(":")
            if (parts.size == 2) {
                val type = parts[0]
                val id = parts[1].toLongOrNull() ?: return null
                val isVideo = type == "video"
                val collection = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                return MediaStoreItem(ContentUris.withAppendedId(collection, id), id, isVideo)
            }
        }

        if (uri.authority == "media") {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return null
            return MediaStoreItem(uri, id, mimeLooksVideo)
        }

        return null
    }

    private fun queryBucketId(uri: Uri, isVideo: Boolean): String? {
        val bucketColumn = if (isVideo) {
            MediaStore.Video.Media.BUCKET_ID
        } else {
            MediaStore.Images.Media.BUCKET_ID
        }
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        val selection = "${MediaStore.MediaColumns._ID} = ?"

        contentResolver.query(collection, arrayOf(bucketColumn), selection, arrayOf(id.toString()), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(bucketColumn))
            }
        }
        return null
    }
}
