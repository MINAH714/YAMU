package com.yamu.app.pos

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.yamu.app.core.designsystem.component.GreyChip
import com.yamu.app.core.designsystem.theme.YamuTheme

/**
 * 고객 결제 코드 찍기 — 카메라 미리보기 + ML Kit(QR · Code128). 읽은 글자를 그대로 [onCode]로(토큰 꺼내기는 ViewModel).
 * 카메라 권한은 이 화면에서만 묻는다. 거절하면 안내만 — [번호로 넣기]는 화면에 따로 있다.
 */
@Composable
fun PosCodeScanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Box(modifier.background(YamuTheme.colors.surfaceVariant), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("카메라를 허용하면 고객 코드를 찍을 수 있어요", style = YamuTheme.typography.bodyM, color = YamuTheme.colors.text2, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                GreyChip("카메라 허용", onClick = { ask.launch(Manifest.permission.CAMERA) })
            }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onCode)
    val controller = remember { LifecycleCameraController(context) }
    DisposableEffect(lifecycleOwner) {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128).build(),
        )
        val executor = ContextCompat.getMainExecutor(context)
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, executor) { result ->
                result.getValue(scanner)?.firstOrNull()?.rawValue?.let { latest(it) }
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            scanner.close()
        }
    }
    AndroidView(
        factory = { ctx -> PreviewView(ctx).apply { this.controller = controller } },
        modifier = modifier,
    )
}
