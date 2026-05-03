package com.xreal360.viewer.media

import android.net.Uri

data class MediaItem(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean
)
