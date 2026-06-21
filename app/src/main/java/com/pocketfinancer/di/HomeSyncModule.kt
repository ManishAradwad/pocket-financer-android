package com.pocketfinancer.di

import com.pocketfinancer.pipeline.HomeSyncDelegate
import com.pocketfinancer.ui.home.HomeSyncDelegateImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HomeSyncModule {

    @Binds
    @Singleton
    abstract fun bindHomeSyncDelegate(impl: HomeSyncDelegateImpl): HomeSyncDelegate
}
