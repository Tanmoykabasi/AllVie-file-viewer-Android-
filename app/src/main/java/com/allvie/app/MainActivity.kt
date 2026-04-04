package com.allvie.app

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allvie.app.data.preferences.UserPreferencesRepository
import com.allvie.app.domain.model.FileCategory
import com.allvie.app.domain.model.FileItem
import com.allvie.app.domain.model.UserPreferences
import com.allvie.app.ui.AllVieApp
import com.allvie.app.ui.theme.AllVieTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesRepository: UserPreferencesRepository

    private var externalOpenFile by mutableStateOf<FileItem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalOpenFile = intent.toExternalFileItem(contentResolver)

        setContent {
            val preferences by preferencesRepository.preferencesFlow.collectAsStateWithLifecycle(
                initialValue = UserPreferences()
            )
            var hasStorageAccess by remember { mutableStateOf(applicationContext.hasStorageAccess()) }

            val readPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                hasStorageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    applicationContext.hasStorageAccess()
                } else {
                    granted
                }
            }

            val allFilesAccessLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                hasStorageAccess = applicationContext.hasStorageAccess()
            }

            val requestStorageAccess: () -> Unit = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        hasStorageAccess = true
                    } else {
                        val packageUri = Uri.parse("package:${applicationContext.packageName}")
                        val appSettingsIntent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            packageUri
                        )
                        val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        runCatching { allFilesAccessLauncher.launch(appSettingsIntent) }
                            .onFailure { allFilesAccessLauncher.launch(fallbackIntent) }
                    }
                } else {
                    readPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }

            LaunchedEffect(Unit) {
                if (!hasStorageAccess) {
                    requestStorageAccess()
                }
            }

            AllVieTheme(themeMode = preferences.themeMode) {
                AllVieApp(
                    hasStorageAccess = hasStorageAccess,
                    onRequestStorageAccess = requestStorageAccess,
                    externalOpenFile = externalOpenFile,
                    onExternalOpenFileConsumed = { externalOpenFile = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalOpenFile = intent.toExternalFileItem(contentResolver)
    }
}

private fun Context.hasStorageAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

private fun Intent.toExternalFileItem(contentResolver: ContentResolver): FileItem? {
    if (action != Intent.ACTION_VIEW) return null

    val uri = data ?: return null
    val mimeType = type ?: contentResolver.getType(uri).orEmpty()
    val displayName = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: uri.lastPathSegment ?: "Document"

    val category = FileCategory.from(mimeType, displayName) ?: return null
    return FileItem(
        uriString = uri.toString(),
        displayName = displayName,
        mimeType = mimeType,
        category = category,
        size = 0L,
        lastModified = 0L,
        pathLabel = "External file"
    )
}
