package com.sting.openclaw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.sting.openclaw.data.local.PreferencesManager
import com.sting.openclaw.ui.navigation.AppNavigation
import com.sting.openclaw.ui.navigation.Screen
import com.sting.openclaw.ui.theme.OpenClawTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenClawAppContent()
        }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager
) : ViewModel() {
    val preferences = preferencesManager.preferences
}

@Composable
fun OpenClawAppContent(
    viewModel: MainViewModel = hiltViewModel()
) {
    val preferences by viewModel.preferences.collectAsState(initial = null)
    
    val isDarkTheme = preferences?.isDarkTheme == "true"
    val useDarkTheme = if (preferences?.isDarkTheme != null) isDarkTheme else isSystemInDarkTheme()
    
    // Determine start destination
    val startDestination = if (preferences?.currentGatewayId != null) {
        Screen.Chat.route
    } else {
        Screen.Setup.route
    }
    
    OpenClawTheme(darkTheme = useDarkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AppNavigation(startDestination = startDestination)
        }
    }
}
