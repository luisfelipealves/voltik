package com.voltik.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VoltikStartScreen() }
    }
}

@Composable
fun VoltikStartScreen() {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFC4F36A))) {
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF101115)).padding(24.dp)) {
            Text("VOLTIK", color = Color(0xFFC4F36A), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(28.dp))
            Text("Biblioteca", color = Color.White, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(20.dp))
            Text("Sua biblioteca estará aqui.", color = Color.LightGray)
        }
    }
}
