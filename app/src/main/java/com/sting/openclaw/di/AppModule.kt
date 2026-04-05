package com.sting.openclaw.di

import android.content.Context
import com.sting.openclaw.data.gateway.GatewayClient
import com.sting.openclaw.data.local.PreferencesManager
import com.sting.openclaw.data.repository.ChatRepository
import com.sting.openclaw.data.repository.ModelRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    
    @Provides
    @Singleton
    fun providePreferencesManager(
        @ApplicationContext context: Context
    ): PreferencesManager = PreferencesManager(context)
    
    @Provides
    @Singleton
    fun provideGatewayClient(json: Json): GatewayClient = GatewayClient(json)
    
    @Provides
    @Singleton
    fun provideChatRepository(json: Json): ChatRepository = ChatRepository(json)
    
    @Provides
    @Singleton
    fun provideModelRepository(json: Json): ModelRepository = ModelRepository(json)
}
