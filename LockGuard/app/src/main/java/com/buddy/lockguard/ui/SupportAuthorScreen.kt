package com.buddy.lockguard.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buddy.lockguard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SupportMethod(val label: String, val appPackage: String, @DrawableRes val qr: Int?) {
    WECHAT("微信", "com.tencent.mm", R.drawable.support_wechat),
    ALIPAY("支付宝", "com.eg.android.AlipayGphone", R.drawable.support_alipay),
}

@Composable
fun SupportAuthorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var methodName by rememberSaveable { mutableStateOf(SupportMethod.WECHAT.name) }
    var pendingSaveName by rememberSaveable { mutableStateOf(SupportMethod.WECHAT.name) }
    var saving by remember { mutableStateOf(false) }
    val method = SupportMethod.valueOf(methodName)
    BackHandler(onBack = onBack)

    fun save(selected: SupportMethod, destination: Uri? = null) {
        if (saving || selected.qr == null) return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { saveSupportCard(context, selected, destination) }
            }
            saving = false
            Toast.makeText(context, if (result.isSuccess) {
                if (destination == null) "已保存到相册 · Pictures/LockGuard" else "收款码已保存"
            } else "保存失败，请重试或截屏保存", Toast.LENGTH_LONG).show()
        }
    }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) save(SupportMethod.valueOf(pendingSaveName), uri)
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("支持作者", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onBack) { Text("返回") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("谢谢你，让这份提醒继续。", style = MaterialTheme.typography.headlineSmall)
                Text("如果它帮到了你，可以随心支持。反馈一次问题，或分享给朋友，也同样珍贵。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SupportMethod.entries.forEach { option ->
                        FilterChip(selected = method == option, onClick = { methodName = option.name },
                            enabled = !saving, label = { Text(option.label) })
                    }
                }
                val qr = method.qr
                if (qr != null) {
                    Column(Modifier.fillMaxWidth().background(Color(0xFFF5F2E9), RoundedCornerShape(24.dp)).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("一份心意 · 一路安心", color = Color(0xFF22634E), fontWeight = FontWeight.SemiBold)
                        Image(painterResource(qr), "${method.label}支持作者收款码", modifier = Modifier
                            .widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).background(Color.White))
                        Text("锁车卫士  /  ${method.label}支持", color = Color(0xFF22634E), style = MaterialTheme.typography.labelMedium)
                    }
                    Text("保存收款码 → 打开${method.label}扫一扫 → 从相册选择。\n也可以用另一部手机扫描。",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) save(method)
                        else {
                            pendingSaveName = method.name
                            documentLauncher.launch("LockGuard-${method.name.lowercase()}-${System.currentTimeMillis()}.png")
                        }
                    }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "保存中…" else "保存收款码") }
                    OutlinedButton(onClick = { openPaymentApp(context, method) }, modifier = Modifier.fillMaxWidth()) {
                        Text("打开${method.label}")
                    }
                } else {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("支付宝支持暂未开通", fontWeight = FontWeight.Medium)
                            Text("作者尚未配置收款码。你可以选择微信，或通过反馈和分享支持。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text("完全自愿，不影响任何功能。请量力而行；未成年人请通过反馈和分享支持。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("页面和保存的图片不展示昵称、姓名或头像。扫码后的收款人信息由支付平台显示，请核对后自行确认。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun openPaymentApp(context: Context, method: SupportMethod) {
    val opened = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(method.appPackage)
        if (intent == null) false else { context.startActivity(intent); true }
    }.getOrDefault(false)
    if (!opened) Toast.makeText(context, "未找到${method.label}，请手动打开或先安装", Toast.LENGTH_LONG).show()
}

/** Decorations stay outside the unmodified QR and its four-module white quiet zone. */
private fun supportCard(context: Context, method: SupportMethod): Bitmap {
    val qr = BitmapFactory.decodeResource(context.resources, requireNotNull(method.qr))
        ?: error("Missing payment QR")
    val bitmap = Bitmap.createBitmap(1080, 1380, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(245, 242, 233))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(34, 99, 78) }
    canvas.drawRoundRect(72f, 72f, 1008f, 1308f, 48f, 48f, paint)
    paint.color = android.graphics.Color.rgb(226, 172, 88)
    canvas.drawCircle(936f, 144f, 18f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
    paint.textSize = 58f
    canvas.drawText("锁车卫士", 540f, 230f, paint)
    paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    paint.textSize = 32f
    canvas.drawText("一份心意 · 一路安心", 540f, 300f, paint)
    // No smoothing or logo overlay on the payment modules.
    canvas.drawBitmap(qr, null, Rect(180, 380, 900, 1100), Paint().apply { isFilterBitmap = false })
    paint.textSize = 36f
    canvas.drawText("${method.label}支持作者", 540f, 1190f, paint)
    paint.textSize = 26f
    canvas.drawText("自愿支持 · 感谢同行", 540f, 1248f, paint)
    qr.recycle()
    return bitmap
}

private fun saveSupportCard(context: Context, method: SupportMethod, destination: Uri?) {
    val resolver = context.contentResolver
    val bitmap = supportCard(context, method)
    var created: Uri? = null
    try {
        val uri = destination ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "LockGuard-${method.name.lowercase()}-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LockGuard")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })?.also { created = it } ?: error("Cannot create image")
        } else error("A document destination is required")
        resolver.openOutputStream(uri)?.use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        } ?: error("Cannot write image")
        if (created != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0)
        }
    } catch (error: Exception) {
        created?.let { runCatching { resolver.delete(it, null, null) } }
        throw error
    } finally { bitmap.recycle() }
}
