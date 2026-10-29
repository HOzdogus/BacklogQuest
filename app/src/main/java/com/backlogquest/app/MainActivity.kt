package com.backlogquest.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.backlogquest.app.data.BacklogViewModel
import com.backlogquest.app.data.Shortcuts
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
        if (savedInstanceState == null) handlePlay(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePlay(intent)
    }

    override fun onResume() {
        super.onResume()
        // Back from a game started here: work out how long you played.
        vm.checkSession()
    }

    /** A home-screen game icon was tapped: start that game straight away. */
    private fun handlePlay(intent: Intent?) {
        val id = intent?.getStringExtra(Shortcuts.EXTRA_GAME) ?: return
        intent.removeExtra(Shortcuts.EXTRA_GAME)
        vm.play(id)
    }
}
