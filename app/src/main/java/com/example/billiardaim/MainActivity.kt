package com.example.billiardaim

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Khởi chạy giao diện ngắm bi-a OverlayView
        val overlayView = OverlayView(this)
        setContentView(overlayView)
    }
}
