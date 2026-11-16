package com.yamu.app.pos

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 모의 POS Debug도 실제 서버 API만 사용한다. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PosDataModule {
    @Binds
    abstract fun posRepository(impl: PosRepositoryImpl): PosRepository
}
