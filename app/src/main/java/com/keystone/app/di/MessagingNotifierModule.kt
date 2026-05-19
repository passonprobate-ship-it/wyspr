package com.keystone.app.di

import com.keystone.app.transport.AndroidMessagingNotifier
import com.keystone.core.transport.MessagingNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MessagingNotifierModule {
    @Binds
    @Singleton
    abstract fun bindMessagingNotifier(impl: AndroidMessagingNotifier): MessagingNotifier
}
