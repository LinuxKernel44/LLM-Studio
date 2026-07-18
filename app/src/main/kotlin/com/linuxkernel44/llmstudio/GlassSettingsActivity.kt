package com.linuxkernel44.llmstudio

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.linuxkernel44.llmstudio.databinding.ActivitySettingsBinding

/**
 * Same Settings form as [SettingsActivity], driven by the same [SettingsController], just shown
 * behind a floating Liquid Glass top bar instead of a normal AppCompat toolbar - see
 * GlassMainActivity's doc comment for the general approach and the `com.kyant.backdrop` package note.
 */
class GlassSettingsActivity : AppCompatActivity() {

    private var controller: SettingsController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Same pure-black window background as GlassMainActivity - see its comment for why.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))

        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        // The Compose top bar below replaces this; hiding it lets the NestedScrollView (weight=1
        // in the same LinearLayout) simply expand to fill the space it would have used.
        binding.toolbar.visibility = View.GONE

        controller = SettingsController(this, binding) { _, newMode ->
            if (com.linuxkernel44.llmstudio.data.SettingsManager.isLiquidGlassFamily(newMode)) {
                // Switched between Liquid Glass <-> Oled Liquid Glass - recreate so onCreate re-reads
                // the mode and this screen's Compose colors/window background pick up the new variant.
                recreate()
            } else {
                // Picked something outside the Liquid Glass family entirely - hand back to the
                // classic SettingsActivity, which will render the newly chosen theme correctly.
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
            }
        }
        controller?.setup()

        setContent {
            GlassSettingsScreen(settingsContent = binding.root, onBack = { finish() })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller?.release()
    }
}

@Composable
private fun GlassSettingsScreen(settingsContent: View, onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { com.linuxkernel44.llmstudio.data.SettingsManager(context) }
    val isOled = remember { settingsManager.isOledLiquidGlassTheme }

    // Oled Liquid Glass pins background/surface to pure black (the default darkColorScheme() alone
    // uses a dark gray, not true black); base Liquid Glass just uses the normal dark scheme.
    val colorScheme = if (isOled) {
        androidx.compose.material3.darkColorScheme(background = Color.Black, surface = Color.Black)
    } else {
        androidx.compose.material3.darkColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme) {
        Box(Modifier.fillMaxSize().background(colorScheme.background)) {
            val backdrop = rememberLayerBackdrop {
                drawRect(colorScheme.background)
                drawContent()
            }

            // Measured (not hardcoded) so the form's first field never starts out hidden behind
            // the floating top bar - see the equivalent comment in GlassMainActivity.
            var topBarHeightPx by remember { mutableStateOf(0) }

            AndroidView(
                factory = { settingsContent },
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
                update = { view -> view.setPadding(0, topBarHeightPx, 0, 0) }
            )

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
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = context.getString(R.string.cd_back), tint = Color.White)
                }
                Text(context.getString(R.string.settings_title), color = Color.White, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
