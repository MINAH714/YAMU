package com.yamu.app.pos

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 모의 POS release: 진짜 저장소만(가짜는 src/posDebug에만) */
@Module
@InstallIn(SingletonComponent::class)
abstract class PosDataModule {
    @Binds
    abstract fun posRepository(impl: PosRepositoryImpl): PosRepository
}
