package app.hack.eightballpool

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_OVERLAY = 101
        private const val REQ_CAPTURE = 102
    }

    private lateinit var startAutoBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Landscape lock ensures VirtualDisplay never needs recreating (1x permission only!)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1120"))
            setPadding(48, 24, 48, 24)
        }

        val title = TextView(this).apply {
            text = "8-Ball Pool Fast-Tap Bounce Overlay"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "• 100% Free Game Touch (table kabhi block nahi hoga)\n" +
                    "• ⚡TAP dabao → White Ball khud edge-to-edge detect hogi\n" +
                    "• Wall ya table par kahin bhi tap & slide karo → Instant 1B–4B bounce\n" +
                    "• Ungli uthate hi game touch free! ◄ ► se 0.25° fine adjust karo"
            textSize = 12.5f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 24)
        }

        startAutoBtn = Button(this).apply {
            text = if (OverlayService.isRunning) "OVERLAY ACTIVE (OPEN GAME)" else "START (AUTO WHITE BALL + FAST TAP)"
            setBackgroundColor(Color.parseColor("#16A34A"))
            setTextColor(Color.WHITE)
            setPadding(40, 22, 40, 22)
            setOnClickListener {
                if (OverlayService.isRunning) {
                    moveTaskToBack(true)
                } else {
                    startWithAutoWhiteBall()
                }
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 16
            layoutParams = lp
        }

        val manualOnlyBtn = Button(this).apply {
            text = "MANUAL ONLY (NO SCREEN SHARE)"
            setBackgroundColor(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            setPadding(28, 18, 28, 18)
            setOnClickListener {
                startManualOnly()
            }
        }

        val stopBtn = Button(this).apply {
            text = "STOP OVERLAY"
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            setPadding(28, 18, 28, 18)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = 16
            layoutParams = lp
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                startAutoBtn.text = "START (AUTO WHITE BALL + FAST TAP)"
                Toast.makeText(this@MainActivity, "Overlay Stopped", Toast.LENGTH_SHORT).show()
            }
        }

        btnRow.addView(manualOnlyBtn)
        btnRow.addView(stopBtn)

        root.addView(title)
        root.addView(subtitle)
        root.addView(startAutoBtn)
        root.addView(btnRow)
        setContentView(root)
    }

    private fun startWithAutoWhiteBall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQ_OVERLAY)
            return
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun startManualOnly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQ_OVERLAY)
            return
        }
        startService(Intent(this, OverlayService::class.java))
        startAutoBtn.text = "OVERLAY ACTIVE (OPEN GAME)"
        moveTaskToBack(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                startWithAutoWhiteBall()
            } else {
                Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val svcIntent = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    startService(svcIntent)
                }
                startAutoBtn.text = "OVERLAY ACTIVE (OPEN GAME)"
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Screen share cancelled — use MANUAL ONLY", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
