package org.daslearning.opensms.next.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.Binds
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.daslearning.opensms.next.data.AppDatabase
import org.daslearning.opensms.next.domain.Clock
import org.daslearning.opensms.next.domain.ClientAuthenticator
import org.daslearning.opensms.next.domain.FieldCipher
import org.daslearning.opensms.next.domain.GatewayRuntimeState
import org.daslearning.opensms.next.domain.GatewayStatusSource
import org.daslearning.opensms.next.domain.GatewayServer
import org.daslearning.opensms.next.domain.TrustedNetworkPolicy
import org.daslearning.opensms.next.domain.PersistentRateLimiter
import org.daslearning.opensms.next.domain.SmsSegmenter
import org.daslearning.opensms.next.domain.SmsSender
import org.daslearning.opensms.next.domain.SmsPartEncoder
import org.daslearning.opensms.next.domain.SubscriptionProvider
import org.daslearning.opensms.next.domain.SystemClock
import org.daslearning.opensms.next.domain.TokenVerifier
import org.daslearning.opensms.next.domain.QueueCoordinator
import org.daslearning.opensms.next.data.ClientRepository
import org.daslearning.opensms.next.data.MessageRepository
import org.daslearning.opensms.next.data.RoomRateLimiter
import org.daslearning.opensms.next.data.RoomQueueCoordinator
import org.daslearning.opensms.next.platform.AndroidFieldCipher
import org.daslearning.opensms.next.platform.AndroidSmsAdapters
import org.daslearning.opensms.next.platform.AndroidTokenVerifier
import org.daslearning.opensms.next.platform.AndroidTrustedNetworkPolicy
import org.daslearning.opensms.next.api.OpenSmsApiServer

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "opensms.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()

    @Provides
    @Singleton
    fun provideFieldCipher(): FieldCipher = AndroidFieldCipher()

    @Provides
    fun provideClock(): Clock = SystemClock

    @Provides
    @Singleton
    fun provideTokenVerifier(): TokenVerifier = AndroidTokenVerifier()

    @Provides
    @Singleton
    fun provideGatewayRuntimeState(): GatewayRuntimeState = GatewayRuntimeState()

    @Provides
    fun provideGatewayStatusSource(state: GatewayRuntimeState): GatewayStatusSource = state

    @Provides
    @Singleton
    fun provideTrustedNetworkPolicy(policy: AndroidTrustedNetworkPolicy): TrustedNetworkPolicy = policy

    @Provides
    @Singleton
    fun provideGatewayServer(server: OpenSmsApiServer): GatewayServer = server
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformBindings {
    @Binds
    abstract fun bindSmsSender(adapters: AndroidSmsAdapters): SmsSender

    @Binds
    abstract fun bindSubscriptionProvider(adapters: AndroidSmsAdapters): SubscriptionProvider

    @Binds
    abstract fun bindSmsSegmenter(adapters: AndroidSmsAdapters): SmsSegmenter

    @Binds
    abstract fun bindSmsPartEncoder(adapters: AndroidSmsAdapters): SmsPartEncoder

    @Binds
    abstract fun bindClientAuthenticator(repository: ClientRepository): ClientAuthenticator

    @Binds
    abstract fun bindRateLimiter(limiter: RoomRateLimiter): PersistentRateLimiter

    @Binds
    abstract fun bindQueueCoordinator(coordinator: RoomQueueCoordinator): QueueCoordinator
}
