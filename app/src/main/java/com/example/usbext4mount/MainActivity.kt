package com.example.usbext4mount

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {

    private val viewModel: MountViewModel by viewModels()

    /** Keeps the drive list current while the app is open, even in the background. */
    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> viewModel.onUsbAttached()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> viewModel.onUsbDetached()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme { MainScreen(viewModel) }
        }
        if (savedInstanceState == null && isUsbAttach(intent)) viewModel.onUsbAttached()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbEvents, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbEvents, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(usbEvents)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isUsbAttach(intent)) viewModel.onUsbAttached()
    }

    private fun isUsbAttach(intent: Intent?) =
        intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
}
