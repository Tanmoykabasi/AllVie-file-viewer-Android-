package com.allvie.app

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesRepository: UserPreferencesRepository

    private var externalOpenFile by mutableStateOf<FileItem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        removeSystemBarScrims()
        externalOpenFile = intent.toExternalFileItem(this)

        setContent {
            val preferences by preferencesRepository.preferencesFlow.collectAsStateWithLifecycle(
                initialValue = UserPreferences()
            )
            var hasStorageAccess by remember { mutableStateOf(applicationContext.hasStorageAccess()) }

            val readPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                hasStorageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    applicationContext.hasStorageAccess()
                } else {
                    val readGranted = grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true
                    val writeGranted = grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
                    readGranted || writeGranted
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
                    val permissions = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    } else {
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    readPermissionLauncher.launch(permissions)
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
        externalOpenFile = intent.toExternalFileItem(this)
    }

    private fun removeSystemBarScrims() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }
}
private fun Context.hasStorageAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        val readGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        readGranted || writeGranted
    }
}

private fun Intent.toExternalFileItem(context: Context): FileItem? {
    if (action != Intent.ACTION_VIEW) return null

    val uri = data ?: return null
    val contentResolver = context.contentResolver
    val mimeType = type ?: contentResolver.getType(uri).orEmpty()
    val displayName = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Document"

    val category = FileCategory.from(mimeType, displayName) ?: return null
    val readableUri = makeExternalViewUriReadable(context, uri, displayName)
    return FileItem(
        uriString = readableUri.toString(),
        displayName = displayName,
        mimeType = mimeType,
        category = category,
        size = 0L,
        lastModified = 0L,
        pathLabel = "External file"
    )
}

private fun Intent.makeExternalViewUriReadable(context: Context, uri: Uri, displayName: String): Uri {
    if (uri.scheme?.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) != true) {
        return uri
    }

    if ((flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 &&
        (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
    ) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val copiedUri = runCatching {
        val cacheDir = File(context.cacheDir, "external_open").apply { mkdirs() }
        val cacheFile = File(cacheDir, "${System.currentTimeMillis()}_${displayName.safeCacheName()}")
        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        input.use {
            FileOutputStream(cacheFile).use { output ->
                it.copyTo(output)
            }
        }
        Uri.fromFile(cacheFile)
    }.getOrNull()

    return copiedUri ?: uri.directReadableFileUri() ?: uri
}

private fun Uri.directReadableFileUri(): Uri? {
    val directPath = path.orEmpty()
    if (!directPath.startsWith("/storage/", ignoreCase = true)) return null
    val file = File(directPath)
    return if (file.isFile && file.canRead()) Uri.fromFile(file) else null
}

private fun String.safeCacheName(): String {
    val safe = replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
    return safe.ifBlank { "external_file" }
}
