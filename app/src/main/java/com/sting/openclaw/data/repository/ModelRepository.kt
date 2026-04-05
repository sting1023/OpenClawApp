package com.sting.openclaw.data.repository

import com.sting.openclaw.data.gateway.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    private val json: Json
) {
    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()
    
    private val _selectedModel = MutableStateFlow<String?>(null)
    val selectedModel: StateFlow<String?> = _selectedModel.asStateFlow()
    
    var webSocket: com.squareup.okhttp3.WebSocket? = null
    private lateinit var gatewayClient: GatewayClient
    fun setGatewayClient(client: GatewayClient) { gatewayClient = client }
    
    suspend fun listModels(): Result<List<ModelInfo>> {
        val request = GatewayRequest(
            id = UUID.randomUUID().toString(),
            method = "models.list",
            params = null
        )
        
        return try {
            webSocket?.send(json.encodeToString(GatewayRequest.serializer(), request))
            Result.success(emptyList()) // Response handled via events
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun setModels(modelList: List<ModelInfo>) {
        _models.value = modelList
    }
    
    fun setSelectedModel(modelName: String?) {
        _selectedModel.value = modelName
    }
}
