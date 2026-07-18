package com.linuxkernel44.llmstudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.linuxkernel44.llmstudio.data.ConversationEntity
import com.linuxkernel44.llmstudio.data.SettingsManager
import com.linuxkernel44.llmstudio.ui.ChatAdapter
import com.linuxkernel44.llmstudio.ui.ConversationAdapter
import com.linuxkernel44.llmstudio.viewmodel.ChatViewModel
import com.linuxkernel44.llmstudio.viewmodel.MicState
import kotlinx.coroutines.launch

/**
 * Compose-rendered chat screen used only for the "Liquid Glass" theme (see [ThemeUtils] /
 * SettingsManager.isLiquidGlassTheme). Every other theme uses the classic View-based [MainActivity].
 *
 * Reuses the SAME [ChatViewModel], [ChatAdapter] and [ConversationAdapter] as MainActivity - none of
 * the app's actual chat/voice/conversation logic is duplicated, only the visual chrome is different:
 * a floating glass top bar, mic button, input pill and drawer, all drawing a live blurred/refracted
 * glimpse of the scrolling message list behind them via the AndroidLiquidGlass ("backdrop") library.
 *
 * NOTE: `com.kyant.backdrop` is this library's package inferred from its Maven coordinates
 * (io.github.kyant0:backdrop) and its documented API names - if the real package differs, Android
 * Studio's "import quick fix" on the few unresolved symbols above will find and correct it instantly.
 */
class GlassMainActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager
    private var appliedThemeMode = SettingsManager.THEME_MODE_LIQUID_GLASS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)
        appliedThemeMode = settingsManager.getThemeMode()
        enableEdgeToEdge()
        // Pure AMOLED black behind everything, including the brief window before Compose's first
        // frame draws - without this the Activity's default (non-black) theme background can flash.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))

        val viewModel = ViewModelProvider(this).get(ChatViewModel::class.java)

        setContent {
            GlassChatScreen(
                activity = this,
                viewModel = viewModel,
                settingsManager = settingsManager,
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val currentMode = settingsManager.getThemeMode()
        if (currentMode != appliedThemeMode) {
            if (SettingsManager.isLiquidGlassFamily(currentMode)) {
                // Switched between Liquid Glass <-> Oled Liquid Glass while this screen was in the
                // back stack - recreate so onCreate re-reads the mode and applies the right variant.
                recreate()
            } else {
                // Switched away from the Liquid Glass family entirely.
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }
}

@Composable
private fun GlassChatScreen(
    activity: GlassMainActivity,
    viewModel: ChatViewModel,
    settingsManager: SettingsManager,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val messages by viewModel.messages.observeAsState(emptyList())
    val micState by viewModel.micState.observeAsState(MicState.IDLE)
    val partialTranscript by viewModel.partialTranscript.observeAsState("")
    val errorEvent by viewModel.errorEvent.observeAsState()
    val conversations by viewModel.conversations.observeAsState(emptyList())
    val currentConversationId by viewModel.currentConversationId.observeAsState()

    // Plain remember (not rememberSaveable): the drawer should always start closed on a fresh
    // composition - including after recreate() (e.g. switching Liquid Glass <-> Oled Liquid Glass in
    // Settings) - never restored open from a previous session's saved instance state. Restoring
    // "open" while drawerOffsetPx.value always re-initializes closed was exactly what made the drawer
    // silently animate itself half-open right after launch.
    var drawerOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    var inputText by rememberSaveable { mutableStateOf("") }
    val continuousMode = remember { settingsManager.isContinuousListening }

    // Same one-shot-at-entry approach as continuousMode above: applied once when this screen is
    // first shown - only affects speech recognition/TTS, never the (always-English) UI text.
    LaunchedEffect(Unit) {
        viewModel.setVoiceLanguage(settingsManager.getVoiceLocale())
        viewModel.setSpeechRate(settingsManager.getSpeechRateForCurrentVoiceLanguage())
        // Kokoro is English-only; French always forces the system engine regardless of the setting.
        val wantsKokoro = settingsManager.isUseKokoroTts &&
            SettingsManager.VOICE_LANGUAGE_ENGLISH_US == settingsManager.voiceLanguageTag
        viewModel.setTtsEngine(wantsKokoro, settingsManager.kokoroSpeakerId)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && continuousMode) {
            viewModel.onContinuousToggle()
        }
    }
    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorEvent) {
        // errorEvent is a SingleLiveEvent - each distinct value observeAsState delivers here is a
        // fresh error to surface exactly once, same as the Snackbar shown in every other theme.
        errorEvent?.let { snackbarHostState.showSnackbar(it) }
    }

    val isOled = remember { settingsManager.isOledLiquidGlassTheme }

    // Oled Liquid Glass: background/surface pinned to pure black (the default dark scheme uses a
    // dark GRAY, not true black) so any Material3 component that implicitly paints its own
    // background - not just our backdrop rect below - stays AMOLED black too. Base Liquid Glass
    // just uses the normal Material dark scheme colors.
    val colorScheme = if (isOled) {
        androidx.compose.material3.darkColorScheme(background = Color.Black, surface = Color.Black)
    } else {
        androidx.compose.material3.darkColorScheme()
    }

    // Edge-swipe-from-left-to-open, mirroring DrawerLayout's native gesture in the classic theme.
    // drawerOffsetPx tracks the drawer's live horizontal position - -drawerWidthPx (fully hidden)
    // to 0f (fully shown) - so it can follow the finger 1:1 while dragging, then settle with a real
    // animation (not a snap) on release, exactly like DrawerLayout's own behavior.
    val density = LocalDensity.current
    val edgeWidthPx = with(density) { 24.dp.toPx() }
    val drawerWidthPx = with(density) { 300.dp.toPx() }
    val drawerOffsetPx = remember { Animatable(-drawerWidthPx) }
    val gestureScope = rememberCoroutineScope()

    // Keeps the animatable in sync whenever drawerOpen is toggled by non-drag means (hamburger
    // icon, tapping the scrim, picking a conversation, back button).
    LaunchedEffect(drawerOpen) {
        val target = if (drawerOpen) 0f else -drawerWidthPx
        if (drawerOffsetPx.value != target) {
            drawerOffsetPx.animateTo(target, animationSpec = tween(220))
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .pointerInput(Unit) {
                    // PointerEventPass.Initial runs top-down (ancestor before descendant), so this
                    // claims the gesture before the full-screen RecyclerView (AndroidView) below can
                    // intercept it for its own scrolling - without this, only areas NOT covered by
                    // that RecyclerView (like the very top status-bar strip) would ever see the drag.
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val startedAtEdge = !drawerOpen && down.position.x < edgeWidthPx
                        if (!startedAtEdge && !drawerOpen) return@awaitEachGesture
                        var claimed = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dragX = change.position.x - change.previousPosition.x
                            if (!claimed) {
                                // Only take over once a real horizontal intent is shown (comparing
                                // total distance since the initial touch, not a single frame's
                                // delta), so a vertical scroll started near the edge still reaches
                                // the list underneath.
                                val totalDx = change.position.x - down.position.x
                                val totalDy = change.position.y - down.position.y
                                if (kotlin.math.abs(totalDx) < 4f && kotlin.math.abs(totalDy) < 4f) continue
                                if (kotlin.math.abs(totalDx) < kotlin.math.abs(totalDy)) break
                                claimed = true
                            }
                            change.consume()
                            // snapTo/animateTo are suspend calls that can't run inside this
                            // restricted awaitPointerEventScope directly - launched on a plain
                            // CoroutineScope instead (standard workaround for gesture-driven Animatables).
                            val target = (drawerOffsetPx.value + dragX).coerceIn(-drawerWidthPx, 0f)
                            gestureScope.launch { drawerOffsetPx.snapTo(target) }
                        }
                        if (claimed) {
                            // Settle open/closed based on how far past the midpoint it was dragged.
                            // Launched directly (not just via the LaunchedEffect above) so it always
                            // animates even when the boolean itself doesn't change, e.g. dragging
                            // partway open then releasing back towards fully open.
                            val openNow = drawerOffsetPx.value > -drawerWidthPx / 2f
                            drawerOpen = openNow
                            gestureScope.launch {
                                drawerOffsetPx.animateTo(if (openNow) 0f else -drawerWidthPx, animationSpec = tween(220))
                            }
                        }
                    }
                }
        ) {
            // The message bubbles and text scrolling underneath still give the glass surfaces
            // something to refract/blur, so the effect stays visible either way.
            val backdrop = rememberLayerBackdrop {
                drawRect(colorScheme.background)
                drawContent()
            }

            // Real measured pixel heights of the floating bars, so the message list can reserve
            // exactly that much space at rest (see the AndroidView's update block below) without
            // hardcoding bar/status-bar sizes that vary by device and font scale.
            var topBarHeightPx by remember { mutableStateOf(0) }
            var bottomBarHeightPx by remember { mutableStateOf(0) }

            val chatAdapter = remember { ChatAdapter() }
            val recyclerView = remember {
                RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = chatAdapter
                    // clipToPadding=false lets scrolled-past messages still flow (blurred) behind
                    // the glass bars while scrolling; the padding itself keeps the list from resting
                    // with a message hidden behind a bar once scrolling stops.
                    clipToPadding = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    overScrollMode = View.OVER_SCROLL_NEVER
                }
            }
            AndroidView(
                factory = { recyclerView },
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
                update = { view ->
                    view.setPadding(0, topBarHeightPx, 0, bottomBarHeightPx)
                    chatAdapter.submitList(messages)
                    if (messages.isNotEmpty()) view.scrollToPosition(messages.size - 1)
                }
            )

            // Floating glass top bar: hamburger, title, settings.
            // onSizeChanged is the FIRST modifier so it reports the whole chain's resolved size
            // (status bar inset + margins + bar height) - placed later, it would only see the
            // inner 56dp Row itself and miss the space actually occupied above the screen's y=0.
            Row(
                modifier = Modifier
                    .onSizeChanged { topBarHeightPx = it.height }
                    .statusBarsPadding()
                    .padding(12.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(28.dp) },
                        effects = {
                            vibrancy()
                            blur(6.dp.toPx())
                            lens(14.dp.toPx(), 20.dp.toPx())
                        },
                        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.12f)) }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { drawerOpen = true }) {
                    Icon(Icons.Default.Menu, contentDescription = stringRes(context, R.string.cd_open_drawer), tint = Color.White)
                }
                Text(stringRes(context, R.string.app_name), color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.startNewConversation() }) {
                    Icon(Icons.Default.Add, contentDescription = stringRes(context, R.string.drawer_new_chat), tint = Color.White)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringRes(context, R.string.action_settings), tint = Color.White)
                }
            }

            // Bottom chrome: status text, glass input pill, glass mic button. Same
            // onSizeChanged-first trick as the top bar, to capture the whole column's real height.
            // imePadding() (after navigationBarsPadding(), the standard ordering) rises the whole
            // column - input pill and mic button together - as the keyboard opens/closes, matching
            // the classic theme's IME-aware inset handling (see MainActivity.setupImeAwareInsets).
            Column(
                modifier = Modifier
                    .onSizeChanged { bottomBarHeightPx = it.height }
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (partialTranscript.isNotEmpty()) {
                    Text(partialTranscript, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(28.dp) },
                            effects = {
                                vibrancy()
                                blur(6.dp.toPx())
                                lens(14.dp.toPx(), 20.dp.toPx())
                            },
                            onDrawSurface = { drawRect(Color.White.copy(alpha = 0.12f)) }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringRes(context, R.string.hint_message_input), color = Color.White.copy(alpha = 0.6f)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    IconButton(onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendManualMessage(inputText)
                            inputText = ""
                        }
                    }) {
                        Icon(Icons.Default.Send, contentDescription = stringRes(context, R.string.action_send), tint = Color.White)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = micStatusText(context, micState, continuousMode),
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { CircleShape },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx())
                                lens(16.dp.toPx(), 28.dp.toPx())
                            },
                            onDrawSurface = { drawRect(micColor(micState).copy(alpha = 0.55f)) }
                        )
                        .pointerInput(continuousMode) {
                            if (continuousMode) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    waitForUpOrCancellation()
                                    if (hasMicPermission()) {
                                        viewModel.onContinuousToggle()
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            } else {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    if (hasMicPermission()) {
                                        viewModel.onPushToTalkStart()
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                    waitForUpOrCancellation()
                                    viewModel.onPushToTalkStop()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(micIcon(micState), contentDescription = stringRes(context, R.string.cd_mic_button), tint = Color.White)
                }
            }

            // Drawer: conversation list, slides in from the start edge over everything else.
            // Always composed (never conditionally added/removed) - only its graphicsLayer alpha/
            // translationX change per animation frame, which Compose can update WITHOUT recomposing
            // this subtree (unlike reading drawerOffsetPx.value directly in composition, which was
            // forcing the whole drawer - including the blur/backdrop draws and the RecyclerView's
            // AndroidView update - to recompose on every single frame, causing the stutter). The
            // (cheap, discrete) drawerOpen boolean still gates actual touch handling, so the fully-
            // closed drawer never intercepts taps meant for the screen behind it.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = ((drawerOffsetPx.value + drawerWidthPx) / drawerWidthPx).coerceIn(0f, 1f)
                    }
                    .background(Color.Black.copy(alpha = 0.35f))
                    .then(
                        if (drawerOpen) Modifier.pointerInputCloseOnTap { drawerOpen = false } else Modifier
                    )
            )
            Column(
                    modifier = Modifier
                        // width() BEFORE fillMaxHeight() (not fillMaxSize() first) - fillMaxSize()
                        // forces an exact (min=max=screen width) incoming constraint that a later
                        // .width(300.dp) can only clamp UP to, never shrink below, since Compose's
                        // width() modifier constrains its requested size to the incoming [min,max]
                        // range. That silently made this column render at FULL SCREEN width instead
                        // of 300dp, so translating it by -300dp worth of pixels when "closed" always
                        // left a permanent sliver on screen - the drawer was never actually closeable.
                        .width(300.dp)
                        .fillMaxHeight()
                        .graphicsLayer { translationX = drawerOffsetPx.value }
                        .statusBarsPadding()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(0.dp) },
                            effects = {
                                vibrancy()
                                blur(10.dp.toPx())
                            },
                            onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.35f)) }
                        )
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringRes(context, R.string.drawer_new_chat),
                            color = Color.White,
                            modifier = Modifier
                                .weight(1f)
                                .pointerInputCloseOnTap {
                                    viewModel.startNewConversation()
                                    drawerOpen = false
                                }
                        )
                    }

                    val conversationAdapter = remember {
                        ConversationAdapter(object : ConversationAdapter.Listener {
                            override fun onConversationClicked(conversation: ConversationEntity) {
                                viewModel.switchToConversation(conversation.id)
                                drawerOpen = false
                            }

                            override fun onConversationMenuClicked(anchor: View, conversation: ConversationEntity) {
                                showConversationMenu(activity, viewModel, anchor, conversation)
                            }
                        })
                    }
                    val conversationsRecyclerView = remember {
                        // ConversationAdapter/item_conversation.xml pull their text/highlight colors
                        // from the ambient Activity theme (correct for the classic MainActivity's own
                        // drawer, where ThemeUtils applies the right theme) - but GlassMainActivity
                        // never applies one, so this would otherwise inherit the default DayNight
                        // theme's LIGHT colors (near-black text) against this drawer's pure black
                        // background, rendering titles invisible. Reusing the existing always-dark
                        // AMOLED theme here fixes it without touching the shared adapter/layout.
                        val darkContext = android.view.ContextThemeWrapper(context, R.style.Theme_LLMStudio_Amoled)
                        RecyclerView(darkContext).apply {
                            layoutManager = LinearLayoutManager(context)
                            adapter = conversationAdapter
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            // Android's default overscroll glow is a light/white arc that otherwise
                            // flashes at the top/bottom of this list against the dark glass drawer.
                            overScrollMode = View.OVER_SCROLL_NEVER
                        }
                    }
                    AndroidView(
                        factory = { conversationsRecyclerView },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        update = { _ ->
                            conversationAdapter.submitList(conversations)
                            currentConversationId?.let { conversationAdapter.setActiveConversationId(it) }
                        }
                    )

                    if (conversations.isNotEmpty()) {
                        Text(
                            stringRes(context, R.string.drawer_delete_all_chats),
                            color = Color(0xFFFFB4AB),
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(16.dp)
                                .pointerInputCloseOnTap {
                                    AlertDialog.Builder(activity)
                                        .setTitle(R.string.drawer_delete_all_chats_title)
                                        .setMessage(R.string.drawer_delete_all_chats_message)
                                        .setPositiveButton(R.string.action_delete) { _, _ ->
                                            viewModel.deleteAllConversations()
                                            drawerOpen = false
                                        }
                                        .setNegativeButton(R.string.action_cancel, null)
                                        .show()
                                }
                        )
                    }
                }

            SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

private fun showConversationMenu(
    activity: GlassMainActivity,
    viewModel: ChatViewModel,
    anchor: View,
    conversation: ConversationEntity
) {
    val popup = PopupMenu(activity, anchor)
    popup.menu.add(0, 1, 0, R.string.conversation_menu_rename)
    popup.menu.add(0, 2, 1, R.string.conversation_menu_delete)
    popup.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            1 -> {
                val input = EditText(activity).apply {
                    setText(conversation.title)
                    setSelection(text.length)
                }
                AlertDialog.Builder(activity)
                    .setTitle(R.string.conversation_rename_title)
                    .setView(input)
                    .setPositiveButton(R.string.action_ok) { _, _ ->
                        viewModel.renameConversation(conversation.id, input.text.toString())
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
                true
            }
            2 -> {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.conversation_delete_title)
                    .setMessage(activity.getString(R.string.conversation_delete_message, conversation.title))
                    .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deleteConversation(conversation.id) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
                true
            }
            else -> false
        }
    }
    popup.show()
}

private fun stringRes(context: android.content.Context, resId: Int): String = context.getString(resId)

private fun micIcon(state: MicState) = when (state) {
    MicState.IDLE -> Icons.Default.Mic
    MicState.LISTENING -> Icons.Default.Stop
    MicState.PROCESSING -> Icons.Default.Stop
    MicState.SPEAKING -> Icons.Default.VolumeUp
}

private fun micColor(state: MicState) = when (state) {
    MicState.IDLE -> Color(0xFF6750A4)
    MicState.LISTENING -> Color(0xFFB3261E)
    MicState.PROCESSING -> Color(0xFF6750A4)
    MicState.SPEAKING -> Color(0xFF386A20)
}

private fun micStatusText(context: android.content.Context, state: MicState, continuousMode: Boolean): String = when (state) {
    MicState.IDLE -> stringRes(context, if (continuousMode) R.string.status_idle_tap else R.string.status_idle_hold)
    MicState.LISTENING -> stringRes(context, R.string.status_listening)
    MicState.PROCESSING -> stringRes(context, R.string.status_processing)
    MicState.SPEAKING -> stringRes(context, R.string.status_speaking)
}

/** Small helper so a Box can be "tap to dismiss" without pulling in a full clickable ripple. */
private fun Modifier.pointerInputCloseOnTap(onTap: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown()
        waitForUpOrCancellation()
        onTap()
    }
}
