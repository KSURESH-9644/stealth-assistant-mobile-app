package com.stealth.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.stealth.assistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var resumeTextContent: String = ""

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleSelectedFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkBasicPermissions()

        // 1. Resume ఫైల్ పికర్
        binding.btnPickResume.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        // 2. ఫ్లోటింగ్ ఓవర్‌లే లాంచ్
        binding.btnLaunchOverlay.setOnClickListener {
            checkOverlayAndStartService()
        }

        // 3. GitHub Releases ద్వారా యాప్ అప్‌డేట్
        binding.btnUpdateApp.setOnClickListener {
            AppUpdater.checkForUpdate(this) { statusMessage ->
                Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleSelectedFile(uri: Uri) {
        try {
            var fileName = "Selected Document"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            binding.tvResumeStatus.text = fileName

            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                // PDF లేదా డాక్యుమెంట్ ఫైల్స్‌ను కరప్ట్ అవ్వకుండా Base64 రూపంలో సర్వర్‌కు పంపుతాం
                resumeTextContent = if (fileName.endsWith(".pdf", ignoreCase = true)) {
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                } else {
                    String(bytes, Charsets.UTF_8)
                }
            }
            Toast.makeText(this, "Resume context loaded!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            binding.tvResumeStatus.text = "Error reading file content"
            e.printStackTrace()
        }
    }

    private fun checkBasicPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    private fun checkOverlayAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable 'Display over other apps' permission", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val customContext = binding.etCustomContext.text.toString().trim()

        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("EXTRA_RESUME_TEXT", resumeTextContent)
            putExtra("EXTRA_CUSTOM_CONTEXT", customContext)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        moveTaskToBack(true)
    }
}