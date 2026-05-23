package com.wyspr.app.di

import com.wyspr.app.transport.FgsForegroundClaim
import com.wyspr.app.transport.FgsTransportLifecycle
import com.wyspr.core.transport.ForegroundClaim
import com.wyspr.core.transport.TransportLifecycle
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TransportLifecycleModule {
    @Binds
    @Singleton
    abstract fun bindTransportLifecycle(impl: FgsTransportLifecycle): TransportLifecycle

    @Binds
    @Singleton
    abstract fun bindForegroundClaim(impl: FgsForegroundClaim): ForegroundClaim
}
