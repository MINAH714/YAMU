package com.yamu.app.feature.report.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 저장 창(SAF)에서 고른 문서(content:// 주소)에 쓰기 · 버리기 — 뷰모델이 Context를 들지 않게 */
interface CsvDocuments {
    /** 다 썼으면 true */
    suspend fun write(uri: String, bytes: ByteArray): Boolean

    /** 저장 창이 미리 만든 빈 파일을 지운다(받기 실패 · 쓰기 실패). 못 지워도 그냥 둔다 */
    suspend fun discard(uri: String)
}

@Singleton
class ContentResolverCsvDocuments @Inject constructor(@ApplicationContext private val context: Context) : CsvDocuments {
    override suspend fun write(uri: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openOutputStream(Uri.parse(uri), "w")?.use { it.write(bytes) } != null }.getOrDefault(false)
    }

    override suspend fun discard(uri: String) {
        withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri)) } }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CsvDocumentsModule {
    @Binds
    abstract fun csvDocuments(impl: ContentResolverCsvDocuments): CsvDocuments
}
