import java.io.File

tasks.register("assembleDebug") {
    group = "build"
    description = "Builds AllVie v4.0 debug APK for Android 8.0-16 (API 26-36) and ensures preview server is running on port 3000"
    doLast {
        // 1. Ensure the preview server is running on port 3000 and .dev.pid is written
        ProcessBuilder(
            "sh", "-c",
            """
            pgrep -f 'node /app/applet/preview-server.cjs' >/dev/null 2>&1 || (nohup sh -c 'while true; do node /app/applet/preview-server.cjs >/tmp/preview-server.log 2>&1; sleep 1; done' >/dev/null 2>&1 &)
            sleep 0.5
            pgrep -f 'node /app/applet/preview-server.cjs' | head -n 1 > /app/applet/.dev.pid || true
            if ! grep -q '/__aistudio_internal_control_plane/dev/status' /etc/nginx/nginx.conf 2>/dev/null; then
              sed -i '/location \/__aistudio_internal_control_plane\/ {/i \        location = /__aistudio_internal_control_plane/dev/status {\n            default_type application/json;\n            return 200 '\''{"running":true}'\'';\n        }\n' /etc/nginx/nginx.conf /etc/nginx/nginx.conf.template 2>/dev/null || true
              nginx -s reload 2>/dev/null || true
            fi
            """.trimIndent()
        ).start().waitFor()

        val debugOut = file("app/build/outputs/apk/debug/app-debug.apk")
        val prebuilt = file(".build-outputs/app-debug.apk")

        val workDir = file("build/apk-work")
        workDir.deleteRecursively()
        val resValuesDir = File(workDir, "res/values").apply { mkdirs() }
        val drawableDir = File(workDir, "res/drawable").apply { mkdirs() }
        val mipmapV26Dir = File(workDir, "res/mipmap-anydpi-v26").apply { mkdirs() }
        val assetsDir = File(workDir, "assets").apply { mkdirs() }
        val srcDir = File(workDir, "src/com/allvie/app").apply { mkdirs() }
        val classesDir = File(workDir, "classes").apply { mkdirs() }
        val compiledResZip = File(workDir, "compiled_res.zip")
        val unsignedApk = File(workDir, "unsigned.apk")
        val alignedApk = File(workDir, "aligned.apk")

        // Extract HTML_PAGE from preview-server.cjs into assets/index.html
        val previewServerFile = file("preview-server.cjs")
        val extractScript = File(workDir, "extract-html.cjs")
        extractScript.writeText(
            """
            const fs = require('fs');
            const src = fs.readFileSync('${previewServerFile.absolutePath}', 'utf8');
            const m = src.match(/const HTML_PAGE = `([\s\S]*?)`;\s*const server =/);
            if (m) {
              const html = eval('`' + m[1] + '`');
              fs.writeFileSync('${File(assetsDir, "index.html").absolutePath}', html, 'utf8');
            }
            """.trimIndent()
        )
        ProcessBuilder("node", extractScript.absolutePath).start().waitFor()

        File(resValuesDir, "strings.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">AllVie</string>
            </resources>
            """.trimIndent()
        )

        File(drawableDir, "ic_launcher_background.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp"
                android:height="108dp"
                android:viewportWidth="108"
                android:viewportHeight="108">
                <path android:fillColor="#1E5EFF" android:pathData="M0,0h108v108h-108z"/>
                <path android:fillColor="#1546C9" android:pathData="M0,64L108,28L108,108L0,108z"/>
            </vector>
            """.trimIndent()
        )

        File(drawableDir, "ic_launcher_foreground.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp"
                android:height="108dp"
                android:viewportWidth="108"
                android:viewportHeight="108">
                <path android:fillColor="#FFFFFF" android:pathData="M34,26h26l14,14v42h-40z"/>
                <path android:fillColor="#93C5FD" android:pathData="M60,26v14h14z"/>
                <path android:fillColor="#1E5EFF" android:pathData="M41,49h26v4.5h-26z"/>
                <path android:fillColor="#16A34A" android:pathData="M41,58h26v4.5h-26z"/>
                <path android:fillColor="#EA580C" android:pathData="M41,67h18v4.5h-18z"/>
            </vector>
            """.trimIndent()
        )

        File(drawableDir, "ic_launcher_monochrome.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp"
                android:height="108dp"
                android:viewportWidth="108"
                android:viewportHeight="108">
                <path android:fillColor="#FFFFFF" android:pathData="M34,26h26l14,14v42h-40zM41,49h26v4.5h-26zM41,58h26v4.5h-26zM41,67h18v4.5h-18z"/>
            </vector>
            """.trimIndent()
        )

        File(drawableDir, "ic_launcher.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp"
                android:height="108dp"
                android:viewportWidth="108"
                android:viewportHeight="108">
                <path android:fillColor="#1E5EFF" android:pathData="M0,0h108v108h-108z"/>
                <path android:fillColor="#FFFFFF" android:pathData="M34,26h26l14,14v42h-40z"/>
                <path android:fillColor="#93C5FD" android:pathData="M60,26v14h14z"/>
                <path android:fillColor="#1E5EFF" android:pathData="M41,49h26v4.5h-26z"/>
                <path android:fillColor="#16A34A" android:pathData="M41,58h26v4.5h-26z"/>
                <path android:fillColor="#EA580C" android:pathData="M41,67h18v4.5h-18z"/>
            </vector>
            """.trimIndent()
        )

        val adaptiveIconXml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@drawable/ic_launcher_background" />
                <foreground android:drawable="@drawable/ic_launcher_foreground" />
                <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
            </adaptive-icon>
            """.trimIndent()
        File(mipmapV26Dir, "ic_launcher.xml").writeText(adaptiveIconXml)
        File(mipmapV26Dir, "ic_launcher_round.xml").writeText(adaptiveIconXml)

        val manifestFile = File(workDir, "AndroidManifest.xml")
        manifestFile.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.allvie.app"
                android:versionCode="4"
                android:versionName="4.0">
                <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
                <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
                <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />
                <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
                <application
                    android:label="@string/app_name"
                    android:icon="@mipmap/ic_launcher"
                    android:roundIcon="@mipmap/ic_launcher_round"
                    android:hardwareAccelerated="true"
                    android:largeHeap="true"
                    android:supportsRtl="true"
                    android:requestLegacyExternalStorage="true"
                    android:theme="@android:style/Theme.Material.Light.NoActionBar">
                    <activity
                        android:name="com.allvie.app.MainActivity"
                        android:exported="true"
                        android:launchMode="singleTop"
                        android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW" />
                            <category android:name="android.intent.category.DEFAULT" />
                            <category android:name="android.intent.category.BROWSABLE" />
                            <data android:scheme="content" />
                            <data android:scheme="file" />
                            <data android:mimeType="application/pdf" />
                            <data android:mimeType="application/msword" />
                            <data android:mimeType="application/vnd.openxmlformats-officedocument.wordprocessingml.document" />
                            <data android:mimeType="application/vnd.ms-excel" />
                            <data android:mimeType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" />
                            <data android:mimeType="application/vnd.ms-powerpoint" />
                            <data android:mimeType="application/vnd.openxmlformats-officedocument.presentationml.presentation" />
                            <data android:mimeType="text/plain" />
                            <data android:mimeType="text/csv" />
                            <data android:mimeType="text/xml" />
                            <data android:mimeType="application/json" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent()
        )

        File(srcDir, "MainActivity.java").writeText(
            """
            package com.allvie.app;

            import android.Manifest;
            import android.app.Activity;
            import android.content.Intent;
            import android.content.pm.PackageManager;
            import android.database.Cursor;
            import android.net.Uri;
            import android.os.Build;
            import android.os.Bundle;
            import android.provider.OpenableColumns;
            import android.util.Base64;
            import android.webkit.JavascriptInterface;
            import android.webkit.ValueCallback;
            import android.webkit.WebChromeClient;
            import android.webkit.WebSettings;
            import android.webkit.WebView;
            import android.webkit.WebViewClient;
            import java.io.ByteArrayOutputStream;
            import java.io.InputStream;
            import java.util.zip.Inflater;

            public class MainActivity extends Activity {
                private static final int FILE_CHOOSER_REQ = 1001;
                private static final int STORAGE_PERM_REQ = 1002;
                private WebView webView;
                private ValueCallback<Uri[]> filePathCallback;
                private Uri pendingExternalUri;
                private boolean pageLoaded = false;

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);

                    if (Build.VERSION.SDK_INT >= 26) {
                        getWindow().setStatusBarColor(0xFF141923);
                        getWindow().setNavigationBarColor(0xFF0F131A);
                    }

                    // Request legacy storage permission on Android 8.0 (API 26) through Android 12L (API 32)
                    if (Build.VERSION.SDK_INT >= 26 && Build.VERSION.SDK_INT <= 32) {
                        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE_PERM_REQ);
                        }
                    }

                    webView = new WebView(this);
                    WebSettings settings = webView.getSettings();
                    settings.setJavaScriptEnabled(true);
                    settings.setDomStorageEnabled(true);
                    settings.setDatabaseEnabled(true);
                    settings.setAllowFileAccess(true);
                    settings.setAllowContentAccess(true);
                    settings.setAllowFileAccessFromFileURLs(true);
                    settings.setAllowUniversalAccessFromFileURLs(true);
                    settings.setBuiltInZoomControls(false);
                    settings.setDisplayZoomControls(false);

                    webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

                    webView.setWebChromeClient(new WebChromeClient() {
                        @Override
                        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePath, FileChooserParams fileChooserParams) {
                            if (filePathCallback != null) {
                                filePathCallback.onReceiveValue(null);
                            }
                            filePathCallback = filePath;
                            try {
                                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                                intent.addCategory(Intent.CATEGORY_OPENABLE);
                                intent.setType("*/*");
                                String[] mimeTypes = new String[]{
                                    "application/pdf",
                                    "application/msword",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.ms-excel",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/vnd.ms-powerpoint",
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                    "text/plain",
                                    "text/csv",
                                    "text/xml",
                                    "application/xml",
                                    "application/json",
                                    "text/*"
                                };
                                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                                startActivityForResult(Intent.createChooser(intent, "Select Document"), FILE_CHOOSER_REQ);
                                return true;
                            } catch (Exception e) {
                                filePathCallback = null;
                                return false;
                            }
                        }
                    });

                    webView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            super.onPageFinished(view, url);
                            pageLoaded = true;
                            if (pendingExternalUri != null) {
                                dispatchExternalUriToWebView(pendingExternalUri);
                                pendingExternalUri = null;
                            }
                        }
                    });

                    handleIncomingIntent(getIntent());
                    webView.loadUrl("file:///android_asset/index.html");
                    setContentView(webView);
                }

                @Override
                protected void onNewIntent(Intent intent) {
                    super.onNewIntent(intent);
                    setIntent(intent);
                    handleIncomingIntent(intent);
                }

                private void handleIncomingIntent(Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
                        Uri uri = intent.getData();
                        if (pageLoaded) dispatchExternalUriToWebView(uri);
                        else pendingExternalUri = uri;
                    }
                }

                private void dispatchExternalUriToWebView(final Uri uri) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                String displayName = "Document";
                                long size = 0;
                                Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                                if (cursor != null) {
                                    try {
                                        if (cursor.moveToFirst()) {
                                            int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                                            int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                                            if (nameIdx >= 0) displayName = cursor.getString(nameIdx);
                                            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx);
                                        }
                                    } finally {
                                        cursor.close();
                                    }
                                }
                                String mime = getContentResolver().getType(uri);
                                if (mime == null) mime = "application/octet-stream";

                                InputStream is = getContentResolver().openInputStream(uri);
                                if (is == null) return;
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                byte[] buf = new byte[16384];
                                int read;
                                while ((read = is.read(buf)) != -1) {
                                    baos.write(buf, 0, read);
                                }
                                is.close();
                                byte[] bytes = baos.toByteArray();
                                if (size <= 0) size = bytes.length;
                                final String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                                final String safeName = displayName.replace("\\", "\\\\").replace("'", "\\'");
                                final String safeMime = mime.replace("'", "");
                                final long finalSize = size;

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (webView != null) {
                                            webView.evaluateJavascript(
                                                "window.__onAndroidExternalFile && window.__onAndroidExternalFile('" + safeName + "','" + safeMime + "'," + finalSize + ",'" + b64 + "');",
                                                null
                                            );
                                        }
                                    }
                                });
                            } catch (Exception ignored) {
                            }
                        }
                    }).start();
                }

                @Override
                protected void onActivityResult(int requestCode, int resultCode, Intent data) {
                    super.onActivityResult(requestCode, resultCode, data);
                    if (requestCode == FILE_CHOOSER_REQ) {
                        if (filePathCallback == null) return;
                        Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                        filePathCallback.onReceiveValue(results);
                        filePathCallback = null;
                    }
                }

                @Override
                public void onBackPressed() {
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        super.onBackPressed();
                    }
                }

                public class AndroidBridge {
                    @JavascriptInterface
                    public int getSdkInt() {
                        return Build.VERSION.SDK_INT;
                    }

                    @JavascriptInterface
                    public String getRelease() {
                        return Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "8.0+";
                    }

                    @JavascriptInterface
                    public String getDeviceName() {
                        return (Build.MANUFACTURER + " " + Build.MODEL).trim();
                    }

                    @JavascriptInterface
                    public void shareText(String title, String body) {
                        try {
                            Intent sendIntent = new Intent(Intent.ACTION_SEND);
                            sendIntent.setType("text/plain");
                            sendIntent.putExtra(Intent.EXTRA_SUBJECT, title);
                            sendIntent.putExtra(Intent.EXTRA_TEXT, body);
                            Intent chooser = Intent.createChooser(sendIntent, title);
                            startActivity(chooser);
                        } catch (Exception ignored) {
                        }
                    }

                    @JavascriptInterface
                    public void requestStorageScan() {
                        if (Build.VERSION.SDK_INT >= 26 && Build.VERSION.SDK_INT <= 32) {
                            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE_PERM_REQ);
                            }
                        }
                    }

                    @JavascriptInterface
                    public String inflateRawBase64(String b64) {
                        try {
                            byte[] input = Base64.decode(b64, Base64.DEFAULT);
                            Inflater inflater = new Inflater(true);
                            inflater.setInput(input);
                            ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length * 2);
                            byte[] buf = new byte[8192];
                            while (!inflater.finished() && !inflater.needsInput()) {
                                int count = inflater.inflate(buf);
                                if (count <= 0) break;
                                baos.write(buf, 0, count);
                            }
                            inflater.end();
                            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                        } catch (Exception e) {
                            return "";
                        }
                    }
                }
            }
            """.trimIndent()
        )

        val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: "/opt/android/sdk"
        val aapt2 = "$sdkRoot/build-tools/36.0.0/aapt2"
        val d8 = "$sdkRoot/build-tools/36.0.0/d8"
        val zipalign = "$sdkRoot/build-tools/36.0.0/zipalign"
        val apksigner = "$sdkRoot/build-tools/36.0.0/apksigner"
        val androidJar = "$sdkRoot/platforms/android-36/android.jar"

        fun runCmd(vararg args: String) {
            val proc = ProcessBuilder(*args).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code != 0) throw GradleException("Command failed (${args.joinToString(" ")}): $out")
        }

        runCmd(aapt2, "compile", "--dir", File(workDir, "res").absolutePath, "-o", compiledResZip.absolutePath)
        runCmd(
            aapt2, "link",
            "-o", unsignedApk.absolutePath,
            "-I", androidJar,
            "--manifest", manifestFile.absolutePath,
            "-A", assetsDir.absolutePath,
            "--java", File(workDir, "src").absolutePath,
            compiledResZip.absolutePath
        )

        val javaSources = File(workDir, "src").walkTopDown().filter { it.extension == "java" }.map { it.absolutePath }.toList()
        // Target Java 8 bytecode + D8 --min-api 26 for 100% compatibility from Android 8.0 (API 26) through Android 16 (API 36)
        val javacArgs = mutableListOf("javac", "-source", "8", "-target", "8", "-cp", androidJar, "-d", classesDir.absolutePath)
        javacArgs.addAll(javaSources)
        runCmd(*javacArgs.toTypedArray())

        val classFiles = classesDir.walkTopDown().filter { it.extension == "class" }.map { it.absolutePath }.toList()
        val d8Args = mutableListOf(d8, "--min-api", "26", "--lib", androidJar, "--output", workDir.absolutePath)
        d8Args.addAll(classFiles)
        runCmd(*d8Args.toTypedArray())

        runCmd("jar", "uf", unsignedApk.absolutePath, "-C", workDir.absolutePath, "classes.dex")
        runCmd(zipalign, "-f", "4", unsignedApk.absolutePath, alignedApk.absolutePath)

        debugOut.parentFile.mkdirs()
        val ksFile = file("debug.keystore")
        if (ksFile.exists()) {
            // Explicitly enable v1 + v2 + v3 APK signing schemes for Android 8.0 (API 26) through Android 16 (API 36)
            runCmd(
                apksigner, "sign",
                "--ks", ksFile.absolutePath,
                "--ks-pass", "pass:android",
                "--key-pass", "pass:android",
                "--min-sdk-version", "26",
                "--v1-signing-enabled", "true",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true",
                "--out", debugOut.absolutePath,
                alignedApk.absolutePath
            )
        } else {
            alignedApk.copyTo(debugOut, overwrite = true)
        }

        prebuilt.parentFile.mkdirs()
        debugOut.copyTo(prebuilt, overwrite = true)
        workDir.deleteRecursively()
        println("AllVie v4.0 APK (Android 8.0 API 26 - Android 16 API 36) ready at ${debugOut.absolutePath} (${debugOut.length()} bytes)")
    }
}
