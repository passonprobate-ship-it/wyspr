package com.wyspr.app.di

import com.wyspr.app.transport.AndroidCoordinationNotifier
import com.wyspr.app.transport.AndroidMessagingNotifier
import com.wyspr.app.transport.AndroidPaymentNotifier
import com.wyspr.core.transport.CoordinationNotifier
import com.wyspr.core.transport.MessagingNotifier
import com.wyspr.core.transport.PaymentNotifier
import com.wyspr.core.transport.SyncTrigger
import com.wyspr.feature.messaging.sync.MessageSyncTrigger
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

    @Binds
    @Singleton
    abstract fun bindPaymentNotifier(impl: AndroidPaymentNotifier): PaymentNotifier

    @Binds
    @Singleton
    abstract fun bindCoordinationNotifier(impl: AndroidCoordinationNotifier): CoordinationNotifier

    @Binds
    @Singleton
    abstract fun bindSyncTrigger(impl: MessageSyncTrigger): SyncTrigger
}
