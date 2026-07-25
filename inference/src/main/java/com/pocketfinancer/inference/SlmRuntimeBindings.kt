package com.pocketfinancer.inference

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SlmRuntimeBindings {
    @Binds
    @Singleton
    abstract fun bindSlmRuntime(implementation: SlmRuntimeCoordinator): SlmRuntime

    @Binds
    @Singleton
    abstract fun bindSlmModelStorage(
        implementation: DefaultSlmModelStorage
    ): SlmModelStorage
}
