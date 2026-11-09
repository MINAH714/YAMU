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
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 저장 창(SAF)에서 고른 문서(content:// 주소)에 쓰기 · 버리기 — 뷰모델이 Context를 들지 않게 */
interface CsvDocuments {
    /** 처음부터 다시 쓴다(원래 내용이 더 길어도 뒤가 남지 않게, 그렇게 열 수 없는 저장소면 그냥 쓰기로 — [writeTruncating]). 다 썼으면 true */
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
        val document = Uri.parse(uri)
        writeTruncating(bytes) { mode -> context.contentResolver.openOutputStream(document, mode) }
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

/**
 * "w"만 쓰면 원래 파일보다 짧게 쓸 때 뒤에 옛 내용이 남는 저장소 앱이 있어 "wt"(비우고 쓰기)로 먼저 연다.
 * "wt"를 받지 않는 저장소(일부 클라우드 제공자)도 있어 열지 못했을 때만 "w"로 한 번 더 연다.
 * 연 뒤에 쓰다 실패하면 다시 열지 않고 false — 지우기는 부르는 쪽이 [CsvDocuments.discardIfEmpty]로 크기 0인 파일만 한다
 */
internal fun writeTruncating(bytes: ByteArray, open: (mode: String) -> OutputStream?): Boolean {
    val out = runCatching { open("wt") }.getOrNull()
        ?: runCatching { open("w") }.getOrNull()
        ?: return false
    return runCatching { out.use { it.write(bytes) } }.isSuccess
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CsvDocumentsModule {
    @Binds
    abstract fun csvDocuments(impl: ContentResolverCsvDocuments): CsvDocuments
}
