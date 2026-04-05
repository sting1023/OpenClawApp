package com.sting.openclaw.ui.screens.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    
    // Expose connection state directly from gatewayClient
    val connectionState: StateFlow<ConnectionState> = gatewayClient.connectionState
    
    val savedGateways: StateFlow<List<GatewayConfig>> = preferencesManager.preferences
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
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _navigateToChat = MutableStateFlow(false)
    val navigateToChat: StateFlow<Boolean> = _navigateToChat.asStateFlow()
    
    // Load saved gateway on startup
    init {
        viewModelScope.launch {
            try {
                val prefs = preferencesManager.preferences.first()
                val current = prefs.gateways.find { it.id == prefs.currentGatewayId }
                if (current != null) {
                    _name.value = current.name
                    _url.value = current.url
                    _port.value = current.port.toString()
                    _token.value = current.token
                }
            } catch (e: Exception) {
                // Ignore, use defaults
            }
        }
    }
    
    fun updateUrl(value: String) { _url.value = value }
    fun updatePort(value: String) { _port.value = value }
    fun updateToken(value: String) { _token.value = value }
    fun updateName(value: String) { _name.value = value }
    
    fun saveCurrentGateway(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val gateway = GatewayConfig(
                    id = UUID.randomUUID().toString(),
                    name = _name.value.ifEmpty { "OpenClaw" },
                    url = _url.value,
                    port = _port.value.toIntOrNull() ?: 15789,
                    token = _token.value
                )
                preferencesManager.saveGateway(gateway)
                preferencesManager.setCurrentGateway(gateway.id)
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Save failed: ${e.message}"
            }
        }
    }
    
    fun connect() {
        if (_url.value.isBlank() || _token.value.isBlank()) {
            _errorMessage.value = "Please fill in URL and Token"
            return
        }
        
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            try {
                val fullUrl = "${_url.value}:${_port.value}"
                gatewayClient.updateConfig(fullUrl, _token.value)
                val result = gatewayClient.connect(fullUrl, _token.value)
                
                if (result.isSuccess) {
                    // Auto-save on successful connect
                    saveCurrentGateway {}
                    _navigateToChat.value = true
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Connection failed"
                    _errorMessage.value = err
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Connection failed"
            } finally {
                _isLoading.value = false
            }
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
    
    fun clearError() { _errorMessage.value = null }
    fun clearNavigation() { _navigateToChat.value = false }
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
    val errorMessage by viewModel.errorMessage.collectAsState()
    val savedGateways by viewModel.savedGateways.collectAsState()
    val navigateToChat by viewModel.navigateToChat.collectAsState()
    
    var passwordVisible by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Handle navigation
    LaunchedEffect(navigateToChat) {
        if (navigateToChat) {
            try {
                onConnected()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Navigation error: ${e.message}")
            } finally {
                viewModel.clearNavigation()
            }
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Saved gateways
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
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            OutlinedTextField(
                value = name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("Name (optional)") },
                placeholder = { Text("My OpenClaw") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedTextField(
                value = url,
                onValueChange = { viewModel.updateUrl(it) },
                label = { Text("Gateway IP / Host") },
                placeholder = { Text("192.168.5.4") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) }
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedTextField(
                value = port,
                onValueChange = { viewModel.updatePort(it) },
                label = { Text("Port") },
                placeholder = { Text("15789 or 690") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                leadingIcon = { Icon(Icons.Default.Router, contentDescription = null) }
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedTextField(
                value = token,
                onValueChange = { viewModel.updateToken(it) },
                label = { Text("Token") },
                placeholder = { Text("Your gateway token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide token" else "Show token"
                        )
                    }
                }
            )
            
            // Error message
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Save button
            OutlinedButton(
                onClick = {
                    viewModel.saveCurrentGateway {
                        kotlinx.coroutines.MainScope().launch {
                            snackbarHostState.showSnackbar("Configuration saved!")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank() && !isLoading
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save")
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Connect button
            Button(
                onClick = { viewModel.connect() },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank() && token.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting...")
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect")
                }
            }
        }
    }
}
