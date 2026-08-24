package com.alexdyakin.lexicon.data.di

import com.alexdyakin.lexicon.data.bridge.BridgeCredentials
import com.alexdyakin.lexicon.data.bridge.LexiconBridgeCredentials
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BridgeModule {
    /**
     * The connection depends on the interface rather than TokenStore + AuthApi so it
     * can be exercised on the JVM; EncryptedSharedPreferences cannot be built off-device.
     */
    @Binds
    @Singleton
    abstract fun bridgeCredentials(impl: LexiconBridgeCredentials): BridgeCredentials
}
