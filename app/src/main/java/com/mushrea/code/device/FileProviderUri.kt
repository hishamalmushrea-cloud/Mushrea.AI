package com.mushrea.code.device

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Centralizes FileProvider URI creation for opening and sharing device files with external apps
 * (file agent, prompt section 28). The provider is declared in the manifest with
 * [R.xml.device_file_paths].
 */
object FileProviderUri {
    fun forFile(
        context: Context,
        file: File,
    ): Uri = FileProvider.getUriForFile(context, "${context.packageName}.device.files", file)
}
