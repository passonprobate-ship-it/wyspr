package com.keystone.app.di

import com.keystone.app.transport.FgsForegroundClaim
import com.keystone.app.transport.FgsTransportLifecycle
import com.keystone.core.transport.ForegroundClaim
import com.keystone.core.transport.TransportLifecycle
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
