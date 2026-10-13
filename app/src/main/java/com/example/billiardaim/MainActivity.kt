package com.example.billiardaim

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val btnPermission = Button(this).apply {
            text = "1. Cấp quyền hiển thị đè màn hình"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }

        val btnInfo = Button(this).apply {
            text = "2. Ứng dụng đã sẵn sàng"
            isEnabled = false
        }

        layout.addView(btnPermission)
        layout.addView(btnInfo)
        setContentView(layout)
    }
}
