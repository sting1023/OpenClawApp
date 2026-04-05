package com.sting.openclaw.ui.screens.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sting.openclaw.data.gateway.ConnectionState
import com.sting.openclaw.data.gateway.GatewayClient
import com.sting.openclaw.data.local.GatewayConfig
import com.sting.openclaw.data.local.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val gatewayClient: GatewayClient
) : ViewModel() {
    
    val connectionState = gatewayClient.connectionState
    
    val savedGateways = preferencesManager.preferences
        .map { it.gateways }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    
    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()
    
    private val _port = MutableStateFlow("15789")
    val port: StateFlow<String> = _port.asStateFlow()
    
    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()
    
    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    fun updateUrl(value: String) { _url.value = value }
    fun updatePort(value: String) { _port.value = value }
    fun updateToken(value: String) { _token.value = value }
    fun updateName(value: String) { _name.value = value }
    
    fun connect() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            val fullUrl = "${_url.value}:${_port.value}"
            
            val result = gatewayClient.connect(fullUrl, _token.value)
            
            if (result.isSuccess) {
                _isConnected.value = true
                
                // Save gateway config
                val gateway = GatewayConfig(
                    id = UUID.randomUUID().toString(),
                    name = _name.value.ifEmpty { "OpenClaw" },
                    url = _url.value,
                    port = _port.value.toIntOrNull() ?: 15789,
                    token = _token.value
                )
                preferencesManager.saveGateway(gateway)
                preferencesManager.setCurrentGateway(gateway.id)
            } else {
                _error.value = result.exceptionOrNull()?.message ?: "Connection failed"
            }
            
            _isLoading.value = false
        }
    }
    
    fun loadGateway(gateway: GatewayConfig) {
        _name.value = gateway.name
        _url.value = gateway.url
        _port.value = gateway.port.toString()
        _token.value = gateway.token
    }
    
    fun deleteGateway(gatewayId: String) {
        viewModelScope.launch {
            preferencesManager.deleteGateway(gatewayId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: SetupViewModel = hiltViewModel(),
    onConnected: () -> Unit
) {
    val url by viewModel.url.collectAsState()
    val port by viewModel.port.collectAsState()
    val token by viewModel.token.collectAsState()
    val name by viewModel.name.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val savedGateways by viewModel.savedGateways.collectAsState()
    
    LaunchedEffect(isConnected) {
        if (isConnected) {
            onConnected()
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Cloud,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "OpenClaw",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        
        Text(
            text = "Connect to your Gateway",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (savedGateways.isNotEmpty()) {
            Text(
                text = "Saved Gateways",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth()
            )
            
            savedGateways.forEach { gateway ->
                Card(
                    onClick = { viewModel.loadGateway(gateway) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = gateway.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${gateway.url}:${gateway.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewModel.deleteGateway(gateway.id) }) {
                            Icon(Icons.Default.Lock, contentDescription = "Delete")
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        OutlinedTextField(
            value = name,
            onValueChange = { viewModel.updateName(it) },
            label = { Text("Name (optional)") },
            placeholder = { Text("My OpenClaw") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedTextField(
            value = url,
            onValueChange = { viewModel.updateUrl(it) },
            label = { Text("Gateway IP / Host") },
            placeholder = { Text("192.168.1.100") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedTextField(
            value = port,
            onValueChange = { viewModel.updatePort(it) },
            label = { Text("Port") },
            placeholder = { Text("15789") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedTextField(
            value = token,
            onValueChange = { viewModel.updateToken(it) },
            label = { Text("Token") },
            placeholder = { Text("Your gateway token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = { viewModel.connect() },
            modifier = Modifier.fillMaxWidth(),
            enabled = url.isNotBlank() && token.isNotBlank() && !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connect")
            }
        }
    }
}
