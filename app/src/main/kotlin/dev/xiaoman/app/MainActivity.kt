package dev.xiaoman.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.xiaoman.app.ui.XiaomanRoot
import dev.xiaoman.app.ui.XiaomanTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as XiaomanApp).graph.uiVisible.value = true
    }

    override fun onStop() {
        (application as XiaomanApp).graph.uiVisible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as XiaomanApp).graph
        setContent { XiaomanTheme { XiaomanRoot(graph) } }
    }
}
