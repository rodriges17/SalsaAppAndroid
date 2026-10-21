package com.stepbystep.salsa

import android.media.MediaMetadataRetriever

/** release() is available on every supported Android version, including API 26. */
internal inline fun <T> MediaMetadataRetriever.releasing(block: (MediaMetadataRetriever) -> T): T {
    try {
        return block(this)
    } finally {
        release()
    }
}
