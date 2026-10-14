package com.fabapps.gomonitor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Tab(val label: String, val icon: TabIcon) {
    DASHBOARD("Dashboard", TabIcon.DASHBOARD),
    HISTORY("Historial", TabIcon.HISTORY),
    PROCESSES("Procesos", TabIcon.PROCESSES),
    CONNECTIONS("Conexiones", TabIcon.CONNECTIONS),
}

@Composable
fun GoMonitorApp(viewModel: MainViewModel = viewModel()) {
    GoMonitorTheme {
        var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
        Scaffold(
            containerColor = Palette.Background,
            bottomBar = {
                NavigationBar(containerColor = Palette.Surface) {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { TabGlyph(item.icon, tint = if (tab == item) Palette.Cpu else Palette.Muted) },
                            label = { Text(item.label, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = Palette.Cpu,
                                unselectedTextColor = Palette.Muted,
                                indicatorColor = Palette.SurfaceHigh,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val openConnections = { tab = Tab.CONNECTIONS }
                when (tab) {
                    Tab.DASHBOARD -> DashboardScreen(viewModel, openConnections)
                    Tab.HISTORY -> HistoryScreen(viewModel, openConnections)
                    Tab.PROCESSES -> ProcessesScreen(viewModel, openConnections)
                    Tab.CONNECTIONS -> ConnectionsScreen(viewModel, onConnected = { tab = Tab.DASHBOARD })
                }
            }
        }
    }
}
