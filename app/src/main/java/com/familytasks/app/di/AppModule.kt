package com.familytasks.app.di

import com.familytasks.app.data.repository.DeviceRepositoryImpl
import com.familytasks.app.data.repository.TaskRepositoryImpl
import com.familytasks.app.data.repository.TaskSyncStateRepositoryImpl
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.domain.repository.TaskSyncStateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    @Singleton
    abstract fun bindDeviceRepository(impl: DeviceRepositoryImpl): DeviceRepository

    @Binds
    @Singleton
    abstract fun bindTaskSyncStateRepository(impl: TaskSyncStateRepositoryImpl): TaskSyncStateRepository
}
