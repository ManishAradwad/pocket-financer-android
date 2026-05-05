package com.pocketfinancer.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * App-level Hilt module. Database and engine modules are provided
 * by their respective submodules (data/DatabaseModule, inference
 * auto-injects via @Singleton + @Inject constructor).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
