package com.yamu.app.feature.report.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
    /** 처음부터 다시 쓴다(원래 내용이 더 길어도 뒤가 남지 않게). 다 썼으면 true */
    suspend fun write(uri: String, bytes: ByteArray): Boolean

    /**
     * 받기 · 쓰기에 실패했을 때 저장 창이 새로 만든 빈 파일만 지운다. 저장 창에서 이미 있는 파일을 골라 덮어쓰기를 눌렀으면
     * 그 파일의 주소가 오므로, 크기가 0이 아니거나 크기를 모르면 지우지 않는다(사용자의 원래 파일). 못 지워도 그냥 둔다
     */
    suspend fun discardIfEmpty(uri: String)
}

@Singleton
class ContentResolverCsvDocuments @Inject constructor(@ApplicationContext private val context: Context) : CsvDocuments {
    override suspend fun write(uri: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        // "w"만 쓰면 원래 파일보다 짧게 쓸 때 뒤에 옛 내용이 남는 저장소 앱이 있다 — "wt"로 비우고 쓴다
        runCatching { context.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
    }

    override suspend fun discardIfEmpty(uri: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val document = Uri.parse(uri)
                val size = context.contentResolver.query(document, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
                if (size == 0L) DocumentsContract.deleteDocument(context.contentResolver, document)
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CsvDocumentsModule {
    @Binds
    abstract fun csvDocuments(impl: ContentResolverCsvDocuments): CsvDocuments
}
