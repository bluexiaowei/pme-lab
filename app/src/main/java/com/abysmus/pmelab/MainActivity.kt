package com.abysmus.pmelab

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.abysmus.pmelab.ui.LabScreen

class MainActivity : ComponentActivity() {

    private val vm: LabViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        vm.onPermissionResult(granted)
        if (granted) ensureBluetoothOn()
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* user may enable BT; scan button will retry */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF2DD4BF),
                    secondary = Color(0xFF5EEAD4),
                    background = Color(0xFF0B1220),
                    surface = Color(0xFF111827),
                    onPrimary = Color(0xFF042F2E),
                    onBackground = Color(0xFFE5E7EB),
                    onSurface = Color(0xFFE5E7EB)
                )
            ) {
                LaunchedEffect(Unit) {
                    requestBlePermissionsIfNeeded()
                }
                LabScreen(
                    vm = vm,
                    onRequestPermissions = { requestBlePermissionsIfNeeded() },
                    onEnsureBluetooth = { ensureBluetoothOn() }
                )
            }
        }
    }

    private fun requestBlePermissionsIfNeeded() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            vm.onPermissionResult(true)
            ensureBluetoothOn()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private fun ensureBluetoothOn() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION")
            enableBtLauncher.launch(android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
}
