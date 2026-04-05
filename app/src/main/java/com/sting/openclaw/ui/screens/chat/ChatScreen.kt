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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.sting.openclaw.data.gateway.Attachment
import com.sting.openclaw.data.gateway.ConnectionState
import com.sting.openclaw.data.gateway.GatewayClient
import com.sting.openclaw.data.local.PreferencesManager
import com.sting.openclaw.data.repository.ChatRepository
import com.sting.openclaw.data.repository.MessageUiModel
import com.sting.openclaw.data.repository.ModelRepository
import com.sting.openclaw.ui.screens.models.ModelPickerSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val modelRepository: ModelRepository,
    private val preferencesManager: PreferencesManager,
    private val gatewayClient: GatewayClient
) : ViewModel() {
    
    val connectionState = gatewayClient.connectionState
    val messages = chatRepository.chatMessages
    val isLoading = chatRepository.isLoading
    val isGenerating = chatRepository.isGenerating
    val models = modelRepository.models
    val selectedModel = modelRepository.selectedModel
    
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()
    
    private val _showModelPicker = MutableStateFlow(false)
    val showModelPicker: StateFlow<Boolean> = _showModelPicker.asStateFlow()
    
    init {
        // Load history on start
        viewModelScope.launch {
            chatRepository.loadHistory()
        }
    }
    
    fun updateInput(text: String) { _inputText.value = text }
    
    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return
        
        _inputText.value = ""
        viewModelScope.launch {
            chatRepository.sendMessage(text)
        }
    }
    
    fun abort() {
        viewModelScope.launch {
            chatRepository.abort()
        }
    }
    
    fun showModelPicker() { _showModelPicker.value = true }
    fun hideModelPicker() { _showModelPicker.value = false }
    
    fun selectModel(model: String) {
        viewModelScope.launch {
            modelRepository.setSelectedModel(model)
            preferencesManager.setSelectedModel(model)
            _showModelPicker.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val showModelPicker by viewModel.showModelPicker.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    
    val listState = rememberLazyListState()
    
    // Auto-scroll when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenClaw", style = MaterialTheme.typography.titleMedium)
                        if (connectionState is ConnectionState.Connected) {
                            Text(
                                text = selectedModel ?: "Connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showModelPicker() }) {
                        Icon(Icons.Default.Dashboard, contentDescription = "Switch Model")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection status banner
            if (connectionState is ConnectionState.Connecting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (connectionState is ConnectionState.Error) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Connection error: ${(connectionState as ConnectionState.Error).message}",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }
            
            // Input area
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { viewModel.updateInput(it) },
                        placeholder = { Text("Type a message...") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { viewModel.sendMessage() })
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    if (isGenerating) {
                        FilledIconButton(
                            onClick = { viewModel.abort() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    } else {
                        FilledIconButton(
                            onClick = { viewModel.sendMessage() },
                            enabled = inputText.isNotBlank()
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
    
    // Model picker sheet
    if (showModelPicker) {
        ModelPickerSheet(
            models = emptyList(),
            selectedModel = selectedModel,
            onSelect = { viewModel.selectModel(it) },
            onDismiss = { viewModel.hideModelPicker() }
        )
    }
}

@Composable
fun MessageBubble(message: MessageUiModel) {
    val isUser = message.role == "user"
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) 
                MaterialTheme.colorScheme.primary 
            else 
                MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.content,
                    color = if (isUser) 
                        MaterialTheme.colorScheme.onPrimary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Render attachments (images)
                message.attachments.forEach { attachment ->
                    when {
                        attachment.url != null -> {
                            AsyncImage(
                                model = attachment.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                        attachment.content != null -> {
                            // Base64 image
                            val mimeType = attachment.mimeType ?: "image/png"
                            val base64Data = attachment.content
                            val imageData = "data:$mimeType;base64,$base64Data"
                            AsyncImage(
                                model = imageData,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
                
                if (message.isStreaming) {
                    Text(
                        text = "...",
                        color = if (isUser) 
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
