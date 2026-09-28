package feather.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import feather.link.FeatherViewModel
import feather.link.FeatherViewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModel: FeatherViewModel by viewModels { FeatherViewModelFactory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // the board can use the whole screen; FeatherScreen pads for bars and camera cut-outs
        setContent {
            FeatherTheme {
                FeatherScreen(viewModel)
            }
        }
    }
}
