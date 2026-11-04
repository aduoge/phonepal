package dev.phonepal.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.phonepal.app.ui.PhonePalRoot
import dev.phonepal.app.ui.PhonePalTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as PhonePalApp).graph.uiVisible.value = true
    }

    override fun onStop() {
        (application as PhonePalApp).graph.uiVisible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as PhonePalApp).graph
        setContent { PhonePalTheme { PhonePalRoot(graph) } }
    }
}
