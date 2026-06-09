package com.victorypoint.zldreventreporter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.victorypoint.zldreventreporter.ui.navigation.AppNavGraph
import com.victorypoint.zldreventreporter.ui.theme.ZldrReporterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ZldrReporterApplication
        setContent {
            ZldrReporterTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavGraph(app = app)
                }
            }
        }
    }
}
