package com.sting.openclaw.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sting.openclaw.data.gateway.*
import com.sting.openclaw.data.local.PreferencesManager
import com.sting.openclaw.data.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val gatewayClient: GatewayClient,
    private val chatRepository: ChatRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {
    
    val messages by chatRepository.chatMessages.collectAsState()
    val isLoading by chatRepository.isLoading.collectAsState()
    val isGenerating by chatRepository.isGenerating.collectAsState()
    val connectionState by gatewayClient.connectionState.collectAsState()
    
    private val _inputText = kotlinx.coroutines.flow.MutableStateFlow("")
    val inputText = _inputText.asStateFlow()
    
    fun updateInput(text: String) { _inputText.value = text }
    
    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty() || connectionState !is ConnectionState.Connected) return
        _inputText.value = ""
        viewModelScope.launch {
            chatRepository.sendMessage(text)
        }
    }
    
    fun abort() {
        viewModelScope.launch { chatRepository.abort() }
    }
    
    fun clearMessages() = chatRepository.clearMessages()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onDisconnect: () -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    
    val listState = rememberLazyListState()
    
    // Auto-scroll when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    // Connection status
    val statusColor = when (connectionState) {
        is ConnectionState.Connected -> Color(0xFF4CAF50)
        is ConnectionState.Connecting -> Color(0xFFFF9800)
        is ConnectionState.Error -> Color(0xFFF44336)
        else -> Color(0xFF9E9E9E)
    }
    val statusText = when (connectionState) {
        is ConnectionState.Connected -> "Connected"
        is ConnectionState.Connecting -> "Connecting..."
        is ConnectionState.Error -> "Error: ${(connectionState as ConnectionState.Error).message}"
        else -> "Disconnected"
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(statusColor, RoundedCornerShape(5.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("OpenClaw", style = MaterialTheme.typography.titleMedium)
                    }
                },
                actions = {
                    // Connection status text
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    // Abort button
                    if (isGenerating) {
                        IconButton(onClick = { viewModel.abort() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    }
                    // Disconnect
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Default.Close, contentDescription = "Disconnect")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    val isUser = msg.role == "user"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            modifier = Modifier.widthIn(max = 280.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isUser)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(
                                topStart = 16.dp, topEnd = 16.dp,
                                bottomStart = if (isUser) 16.dp else 4.dp,
                                bottomEnd = if (isUser) 4.dp else 16.dp
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (msg.content.isNotEmpty()) {
                                    Text(
                                        text = msg.content,
                                        color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (msg.isStreaming) {
                                    Text(
                                        text = "typing...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isUser) Color.White.copy(alpha=0.7f)
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // Error banner when disconnected
            if (connectionState !is ConnectionState.Connected && connectionState !is ConnectionState.Connecting) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Not connected. ${if (connectionState is ConnectionState.Error) (connectionState as ConnectionState.Error).message else ""}",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            // Input
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { viewModel.updateInput(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message...") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { viewModel.sendMessage() }),
                        singleLine = true,
                        enabled = connectionState is ConnectionState.Connected && !isGenerating
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { viewModel.sendMessage() },
                        enabled = inputText.isNotBlank() && connectionState is ConnectionState.Connected && !isGenerating
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
