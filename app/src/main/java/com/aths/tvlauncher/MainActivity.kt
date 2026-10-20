package com.aths.tvlauncher

import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvWifiStatus: TextView
    private lateinit var tvAppCountBadge: TextView
    private lateinit var tvFocusedAppName: TextView
    private lateinit var appsScrollView: HorizontalScrollView
    private lateinit var appsRow: LinearLayout
    private lateinit var btnHeaderSettings: LinearLayout

    private data class TvAppItem(
        val label: String,
        val packageName: String,
        val activityName: String,
        val icon: Drawable?
    )

    private val installedApps = mutableListOf<TvAppItem>()

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClockAndDate()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideSystemUI()

        tvClock = findViewById(R.id.tvClock)
        tvDate = findViewById(R.id.tvDate)
        tvWifiStatus = findViewById(R.id.tvWifiStatus)
        tvAppCountBadge = findViewById(R.id.tvAppCountBadge)
        tvFocusedAppName = findViewById(R.id.tvFocusedAppName)
        appsScrollView = findViewById(R.id.appsScrollView)
        appsRow = findViewById(R.id.appsRow)
        btnHeaderSettings = findViewById(R.id.btnHeaderSettings)

        btnHeaderSettings.setOnClickListener {
            showSettingsDialog()
        }

        btnHeaderSettings.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                tvFocusedAppName.text = "Settings"
            }
        }

        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, pkgFilter)

        updateClockAndDate()
        updateNetworkStatus()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        handler.removeCallbacks(clockRunnable)
        handler.post(clockRunnable)
        updateNetworkStatus()
    }

    override fun onPause() {
        handler.removeCallbacks(clockRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (_: Exception) {}
        handler.removeCallbacks(clockRunnable)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun updateClockAndDate() {
        val now = Date()
        tvClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        tvDate.text = SimpleDateFormat("EEEE, dd MMMM", Locale.getDefault()).format(now)
    }

    private fun updateNetworkStatus() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                tvWifiStatus.text = "Offline"
                return
            }
            val activeNetwork = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNetwork)
            val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            if (isConnected) {
                when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                        tvWifiStatus.text = "Wi-Fi"
                    }
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> {
                        tvWifiStatus.text = "Ethernet"
                    }
                    else -> {
                        tvWifiStatus.text = "Connected"
                    }
                }
            } else {
                tvWifiStatus.text = "Offline"
            }
        } catch (_: Exception) {
            tvWifiStatus.text = "Connected"
        }
    }

    private fun loadApps() {
        appsRow.removeAllViews()
        installedApps.clear()

        val pm = packageManager
        val tvIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }

        val resolvedTvApps: List<ResolveInfo> = pm.queryIntentActivities(tvIntent, 0)
        val seenPackages = mutableSetOf<String>()

        for (info in resolvedTvApps) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) continue // Skip ourselves
            if (seenPackages.add(pkg)) {
                val label = info.loadLabel(pm).toString()
                val icon = info.loadIcon(pm)
                installedApps.add(
                    TvAppItem(
                        label = label,
                        packageName = pkg,
                        activityName = info.activityInfo.name,
                        icon = icon
                    )
                )
            }
        }

        // If no leanback apps found (rare), query standard launcher apps as fallback
        if (installedApps.isEmpty()) {
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolvedLauncherApps = pm.queryIntentActivities(launcherIntent, 0)
            for (info in resolvedLauncherApps) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName) continue
                if (seenPackages.add(pkg)) {
                    val label = info.loadLabel(pm).toString()
                    val icon = info.loadIcon(pm)
                    installedApps.add(
                        TvAppItem(
                            label = label,
                            packageName = pkg,
                            activityName = info.activityInfo.name,
                            icon = icon
                        )
                    )
                }
            }
        }

        // Sort alphabetically by app name
        installedApps.sortBy { it.label.lowercase(Locale.getDefault()) }

        tvAppCountBadge.text = "${installedApps.size} APPS"

        val inflater = LayoutInflater.from(this)
        var firstCard: View? = null

        for ((index, app) in installedApps.withIndex()) {
            val cardView = inflater.inflate(R.layout.item_app_card, appsRow, false)
            val iconView = cardView.findViewById<ImageView>(R.id.cardIcon)
            val labelView = cardView.findViewById<TextView>(R.id.cardLabel)
            val subtitleView = cardView.findViewById<TextView>(R.id.cardSubtitle)

            labelView.text = app.label
            if (app.icon != null) {
                iconView.setImageDrawable(app.icon)
            } else {
                iconView.setImageResource(R.drawable.ic_apps)
            }

            // Keyboard/D-pad navigation link: Top moves to header settings
            cardView.nextFocusUpId = R.id.btnHeaderSettings
            btnHeaderSettings.nextFocusDownId = cardView.id

            cardView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate()
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .translationZ(12f)
                        .setDuration(150)
                        .start()
                    tvFocusedAppName.text = app.label
                    subtitleView.setTextColor(getColor(R.color.accent_cyan))
                    ensureCardVisible(cardView)
                } else {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationZ(0f)
                        .setDuration(150)
                        .start()
                    subtitleView.setTextColor(getColor(R.color.accent_violet))
                }
            }

            cardView.setOnClickListener {
                launchApp(app)
            }

            appsRow.addView(cardView)
            if (index == 0) {
                firstCard = cardView
            }
        }

        // Add an inline TV Settings card at the end of the list for quick access
        val settingsCard = inflater.inflate(R.layout.item_app_card, appsRow, false)
        val settingsIcon = settingsCard.findViewById<ImageView>(R.id.cardIcon)
        val settingsLabel = settingsCard.findViewById<TextView>(R.id.cardLabel)
        val settingsSub = settingsCard.findViewById<TextView>(R.id.cardSubtitle)

        settingsIcon.setImageResource(R.drawable.ic_settings)
        settingsLabel.text = "Settings"
        settingsSub.text = "SYSTEM"

        settingsCard.nextFocusUpId = R.id.btnHeaderSettings
        settingsCard.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(12f).setDuration(150).start()
                tvFocusedAppName.text = "Android TV Settings"
                settingsSub.setTextColor(getColor(R.color.accent_cyan))
                ensureCardVisible(settingsCard)
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
                settingsSub.setTextColor(getColor(R.color.accent_violet))
            }
        }
        settingsCard.setOnClickListener {
            showSettingsDialog()
        }
        appsRow.addView(settingsCard)

        // Focus the first app on boot
        firstCard?.post {
            firstCard.requestFocus()
        }
    }

    private fun ensureCardVisible(view: View) {
        val scrollBounds = android.graphics.Rect()
        appsScrollView.getDrawingRect(scrollBounds)

        val cardLeft = view.left
        val cardRight = view.right

        if (cardLeft < scrollBounds.left) {
            appsScrollView.smoothScrollTo(cardLeft - 40, 0)
        } else if (cardRight > scrollBounds.right) {
            appsScrollView.smoothScrollTo(cardRight - scrollBounds.width() + 40, 0)
        }
    }

    private fun launchApp(app: TvAppItem) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            setClassName(app.packageName, app.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = packageManager.getLaunchIntentForPackage(app.packageName)
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(fallback)
                } else {
                    Toast.makeText(this, "Cannot launch ${app.label}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Launch error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(this, R.style.DialogTheme)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        dialog.setContentView(dialogView)

        val btnOpenAndroidSettings = dialogView.findViewById<View>(R.id.btnOpenAndroidSettings)
        val btnRefreshApps = dialogView.findViewById<View>(R.id.btnRefreshApps)
        val btnCloseSettings = dialogView.findViewById<View>(R.id.btnCloseSettings)
        val dialogDeviceInfo = dialogView.findViewById<TextView>(R.id.dialogDeviceInfo)

        val modelInfo = "${Build.MANUFACTURER.uppercase(Locale.getDefault())} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        dialogDeviceInfo.text = "Albedo personal launcher\n$modelInfo"

        btnOpenAndroidSettings.setOnClickListener {
            dialog.dismiss()
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open Settings", Toast.LENGTH_SHORT).show()
            }
        }

        btnRefreshApps.setOnClickListener {
            dialog.dismiss()
            loadApps()
            Toast.makeText(this, "App list refreshed", Toast.LENGTH_SHORT).show()
        }

        btnCloseSettings.setOnClickListener {
            dialog.dismiss()
        }

        btnOpenAndroidSettings.requestFocus()
        dialog.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Ignore back key on launcher home to prevent exiting to an empty screen
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
