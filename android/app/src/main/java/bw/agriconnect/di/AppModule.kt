package bw.agriconnect.di

import android.content.Context
import bw.agriconnect.core.util.AndroidConnectivityObserver
import bw.agriconnect.core.util.ConnectivityObserver
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.repository.DefaultAuthRepository
import bw.agriconnect.data.repository.DefaultFarmPlotRepository
import bw.agriconnect.data.repository.FarmPlotRepository
import bw.agriconnect.data.session.EncryptedTokenStore
import bw.agriconnect.data.session.SessionManager
import bw.agriconnect.data.session.TokenStore
import bw.agriconnect.data.session.createSessionManager
import bw.agriconnect.data.sync.RoomSyncQueue
import bw.agriconnect.data.sync.SyncQueue
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * Application-scoped singletons that are not a plain `@Inject` constructor graph:
 * the encrypted token container, the DataStore-backed session, the connectivity
 * observer and the error mapper all need a `Context` or another binding.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The bearer-token container. A singleton so the in-memory access-token cache
     * shared with [bw.agriconnect.data.remote.AuthInterceptor] is one instance.
     */
    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore =
        EncryptedTokenStore(context)

    /**
     * The observable session. Reuses the process-wide `preferencesDataStore`
     * delegate via [createSessionManager] so there is exactly one DataStore for
     * the session file (a second instance would throw at runtime).
     */
    @Provides
    @Singleton
    fun provideSessionManager(
        @ApplicationContext context: Context,
        tokenStore: TokenStore,
    ): SessionManager = createSessionManager(context, tokenStore)

    /**
     * Real network availability (validated capability), used by the offline banner
     * and by [bw.agriconnect.data.sync.SyncScheduler].
     */
    @Provides
    @Singleton
    fun provideConnectivityObserver(@ApplicationContext context: Context): ConnectivityObserver =
        AndroidConnectivityObserver(context)

    /** Map transport/OAuth failures to a presentation-safe [bw.agriconnect.core.result.AppError]. */
    @Provides
    @Singleton
    fun provideNetworkErrorParser(json: Json): NetworkErrorParser = NetworkErrorParser(json)
}

/**
 * Interface-to-implementation bindings. Kept in a separate `abstract` module from
 * [AppModule] because Kotlin cannot mix `@Binds` (abstract) and `@Provides` (body)
 * in one class.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BindingsModule {

    @Binds
    @Singleton
    internal abstract fun bindSyncQueue(impl: RoomSyncQueue): SyncQueue

    @Binds
    @Singleton
    internal abstract fun bindAuthRepository(impl: DefaultAuthRepository): AuthRepository

    @Binds
    @Singleton
    internal abstract fun bindFarmPlotRepository(impl: DefaultFarmPlotRepository): FarmPlotRepository
}
