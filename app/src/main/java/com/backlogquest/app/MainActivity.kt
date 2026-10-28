package com.backlogquest.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.backlogquest.app.data.BacklogViewModel
import com.backlogquest.app.ui.BacklogApp
import com.backlogquest.app.ui.BacklogTheme

class MainActivity : ComponentActivity() {
    private val vm: BacklogViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so keep the status bar icons light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            BacklogTheme {
                BacklogApp(vm)
            }
        }
    }
}
