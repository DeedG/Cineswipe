package io.github.deedg.cineswipe.ui.friends

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.repository.FriendMatch
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.TasteShareRepository
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.domain.taste.TasteCode
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object FriendsTestTags {
    const val QR = "friends_qr"
    const val NAME = "friends_name"
    const val SHARE = "friends_share"
    const val TAB_CODE = "friends_tab_code"
    const val TAB_SCAN = "friends_tab_scan"
    const val PASTE = "friends_paste"
    const val PASTE_GO = "friends_paste_go"
    const val SCORE = "friends_score"
    const val ERROR = "friends_error"
}

enum class FriendsTab { CODE, SCAN }

/** Ce que le code lu n'a pas permis de faire. */
enum class FriendsError { INVALID, OTHER_WORLD }

data class FriendsState(
    val tab: FriendsTab = FriendsTab.CODE,
    val name: String = "",
    val code: String? = null,
    val comparing: Boolean = false,
    val result: FriendMatch? = null,
    val error: FriendsError? = null,
)

class FriendsViewModel(
    private val share: TasteShareRepository,
    private val settings: SettingsRepository,
    private val series: Boolean,
    incoming: String?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val name = settings.tasteName.first()
            mutableState.update { it.copy(name = name) }
            refreshCode(name)
        }
        // Code reçu par un partage (message, mail) : comparaison tout de suite.
        incoming?.let(::compare)
    }

    fun setTab(tab: FriendsTab) = mutableState.update { it.copy(tab = tab, error = null) }

    fun setName(name: String) {
        mutableState.update { it.copy(name = name.take(24)) }
        viewModelScope.launch {
            settings.setTasteName(name)
            refreshCode(name)
        }
    }

    private suspend fun refreshCode(name: String) {
        val code = share.myCode(name).encode()
        mutableState.update { it.copy(code = code) }
    }

    /** Un texte lu (QR code, collé, partagé) : comparaison s'il contient un code CineSwipe du même monde. */
    fun compare(text: String): Boolean {
        val friend = TasteCode.decode(text)
        if (friend == null) {
            mutableState.update { it.copy(error = FriendsError.INVALID) }
            return false
        }
        if (friend.series != series) {
            mutableState.update { it.copy(error = FriendsError.OTHER_WORLD) }
            return false
        }
        if (mutableState.value.comparing) return true
        mutableState.update { it.copy(comparing = true, error = null) }
        viewModelScope.launch {
            val result = try {
                share.compare(friend)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
            mutableState.update { it.copy(comparing = false, result = result, error = if (result == null) FriendsError.INVALID else null) }
        }
        return true
    }

    fun closeResult() = mutableState.update { it.copy(result = null, error = null) }
}

/**
 * Comparer ses goûts avec un ami : chacun montre son QR code (ses préférés, ses moins aimés, sa
 * liste À voir), l'autre le scanne. Rien ne passe par Internet.
 */
@Composable
fun FriendsRoute(incoming: String?, onBack: () -> Unit, onOpenMovie: (Long) -> Unit) {
    val viewModel = cineViewModel { FriendsViewModel(it.tasteShare, it.settings, it.kind.isSeries, incoming) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    FriendsScreen(
        state = state,
        onBack = { if (state.result != null) viewModel.closeResult() else onBack() },
        onTab = viewModel::setTab,
        onName = viewModel::setName,
        onCode = viewModel::compare,
        onOpenMovie = onOpenMovie,
        onScanAgain = viewModel::closeResult,
    )
}

@Composable
fun FriendsScreen(
    state: FriendsState,
    onBack: () -> Unit,
    onTab: (FriendsTab) -> Unit,
    onName: (String) -> Unit,
    onCode: (String) -> Boolean,
    onOpenMovie: (Long) -> Unit,
    onScanAgain: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(CineColors.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(
            text = stringResource(R.string.friends_title),
            leading = { GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack) },
        )
        val result = state.result
        when {
            result != null -> MatchResult(result, onOpenMovie, onScanAgain)
            state.comparing -> Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CineColors.Red, strokeWidth = 2.dp)
            }
            else -> {
                Text(stringResource(R.string.friends_intro), color = CineColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CineChip(stringResource(R.string.friends_tab_code), state.tab == FriendsTab.CODE, { onTab(FriendsTab.CODE) }, Modifier.testTag(FriendsTestTags.TAB_CODE))
                    CineChip(stringResource(R.string.friends_tab_scan), state.tab == FriendsTab.SCAN, { onTab(FriendsTab.SCAN) }, Modifier.testTag(FriendsTestTags.TAB_SCAN))
                }
                when (state.tab) {
                    FriendsTab.CODE -> MyCode(state, onName)
                    FriendsTab.SCAN -> Scan(state, onCode)
                }
            }
        }
    }
}

@Composable
private fun MyCode(state: FriendsState, onName: (String) -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable(state.name.isEmpty()) { mutableStateOf(state.name) }
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it.take(24)
            onName(name)
        },
        modifier = Modifier.fillMaxWidth().testTag(FriendsTestTags.NAME),
        singleLine = true,
        label = { Text(stringResource(R.string.friends_name)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        shape = RoundedCornerShape(10.dp),
        colors = fieldColors(),
    )
    val code = state.code
    val qr = remember(code) { code?.let { qrBitmap(it, QR_PIXELS) } }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(14.dp)
            .testTag(FriendsTestTags.QR),
        contentAlignment = Alignment.Center,
    ) {
        if (qr != null) {
            Image(qr.asImageBitmap(), contentDescription = stringResource(R.string.friends_qr_description), modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
        } else {
            CircularProgressIndicator(color = CineColors.Red, strokeWidth = 2.dp)
        }
    }
    Text(stringResource(R.string.friends_code_hint), color = CineColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp)
    val shareText = stringResource(R.string.friends_share_text)
    val shareTitle = stringResource(R.string.friends_share)
    CineSecondaryButton(
        text = shareTitle,
        icon = CineIcons.Share,
        enabled = code != null,
        onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$shareText\n\n$code")
            runCatching { context.startActivity(Intent.createChooser(send, shareTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        modifier = Modifier.fillMaxWidth().testTag(FriendsTestTags.SHARE),
    )
}

@Composable
private fun Scan(state: FriendsState, onCode: (String) -> Boolean) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) runCatching { launcher.launch(Manifest.permission.CAMERA) } }
    if (allowed) {
        QrScanner(onCode, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).border(2.dp, CineColors.Red, RoundedCornerShape(18.dp)))
        Text(stringResource(R.string.friends_scan_hint), color = CineColors.TextMuted, fontSize = 13.sp)
    } else {
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.friends_camera_needed), color = CineColors.TextSoft, fontSize = 14.sp)
                CineButton(
                    text = stringResource(R.string.friends_camera_allow),
                    icon = CineIcons.QrCode,
                    onClick = { runCatching { launcher.launch(Manifest.permission.CAMERA) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    state.error?.let { error ->
        Text(
            stringResource(if (error == FriendsError.OTHER_WORLD) R.string.friends_error_world else R.string.friends_error_invalid),
            color = CineColors.Warning,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(FriendsTestTags.ERROR),
        )
    }
    // Loin l'un de l'autre : le code reçu par message, collé ici.
    var pasted by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it },
        modifier = Modifier.fillMaxWidth().testTag(FriendsTestTags.PASTE),
        label = { Text(stringResource(R.string.friends_paste)) },
        maxLines = 3,
        shape = RoundedCornerShape(10.dp),
        colors = fieldColors(),
    )
    CineSecondaryButton(
        text = stringResource(R.string.friends_compare),
        icon = CineIcons.Users,
        enabled = pasted.isNotBlank(),
        onClick = { onCode(pasted) },
        modifier = Modifier.fillMaxWidth().testTag(FriendsTestTags.PASTE_GO),
    )
}

/** Caméra arrière et lecture des QR codes (ZXing) ; le premier code CineSwipe lu est transmis. */
@Composable
private fun QrScanner(onCode: (String) -> Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val done = AtomicBoolean(false)
        val main = ContextCompat.getMainExecutor(context)
        val futureProvider = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        futureProvider.addListener({
            val cameraProvider = runCatching { futureProvider.get() }.getOrNull() ?: return@addListener
            provider = cameraProvider
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { image ->
                val text = if (done.get()) null else decodeQr(image)
                image.close()
                if (text != null && text.contains(TasteCode.PREFIX) && done.compareAndSet(false, true)) {
                    main.execute { if (!currentOnCode(text)) done.set(false) }
                }
            }
            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, main)
        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun decodeQr(image: ImageProxy): String? = runCatching {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
}.getOrNull()

/** QR code net (un module = plusieurs pixels pleins), noir sur blanc. */
fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 0),
    )
    val modules = matrix.width
    val scale = maxOf(1, size / modules)
    val pixels = IntArray(modules * scale * modules * scale)
    val side = modules * scale
    for (y in 0 until side) {
        for (x in 0 until side) {
            pixels[y * side + x] = if (matrix.get(x / scale, y / scale)) BLACK else WHITE
        }
    }
    Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
}.getOrNull()

private const val QR_PIXELS = 900
private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

@Composable
private fun MatchResult(result: FriendMatch, onOpenMovie: (Long) -> Unit, onScanAgain: () -> Unit) {
    val match = result.match
    val name = result.friend.name.ifBlank { stringResource(R.string.friends_someone) }
    SectionCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.friends_you_and, name), color = CineColors.TextSecondary, fontSize = 15.sp)
            Text(
                match.score?.let { "$it %" } ?: "?",
                color = CineColors.Red,
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.testTag(FriendsTestTags.SCORE),
            )
            Text(
                if (match.score != null) stringResource(R.string.friends_score_label, verdict(match.score)) else stringResource(R.string.friends_score_unknown),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(stringResource(R.string.friends_common, match.common), color = CineColors.TextMuted, fontSize = 13.sp)
        }
    }
    PosterRow(result, match.bothWant, stringResource(R.string.friends_both_want), onOpenMovie)
    PosterRow(result, match.theyLoved, stringResource(R.string.friends_they_loved, name), onOpenMovie)
    PosterRow(result, match.toRecommend, stringResource(R.string.friends_to_recommend, name), onOpenMovie)
    PosterRow(result, match.agreements, stringResource(R.string.friends_agreements), onOpenMovie)
    PosterRow(result, match.disagreements, stringResource(R.string.friends_disagreements), onOpenMovie)
    CineSecondaryButton(
        text = stringResource(R.string.friends_again),
        icon = CineIcons.QrCode,
        onClick = onScanAgain,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun verdict(score: Int): String = stringResource(
    when {
        score >= 85 -> R.string.friends_verdict_twins
        score >= 70 -> R.string.friends_verdict_close
        score >= 55 -> R.string.friends_verdict_ok
        else -> R.string.friends_verdict_far
    },
)

/** Une rangée d'affiches (rien si la liste est vide). */
@Composable
private fun PosterRow(result: FriendMatch, ids: List<Long>, title: String, onOpenMovie: (Long) -> Unit) {
    if (ids.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ids.forEach { id -> Poster(result.movies[id], id, result, onOpenMovie) }
        }
    }
}

@Composable
private fun Poster(movie: MovieEntity?, id: Long, result: FriendMatch, onOpenMovie: (Long) -> Unit) {
    Column(Modifier.width(96.dp).clickable { onOpenMovie(id) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PosterImage(
            path = movie?.posterPath,
            imageSize = TmdbImageSize.Thumb,
            contentDescription = movie?.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
        )
        Text(movie?.title ?: "…", color = CineColors.TextSoft, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        result.friend.ratings[id]?.let { half ->
            Text("★ ${half / 2}${if (half % 2 == 1) "½" else ""}", color = CineColors.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = CineColors.SurfaceHigh,
    unfocusedContainerColor = CineColors.SurfaceHigh,
    focusedBorderColor = CineColors.Red,
    unfocusedBorderColor = CineColors.BorderStrong,
    cursorColor = CineColors.Red,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = CineColors.Red,
    unfocusedLabelColor = CineColors.TextMuted,
)
