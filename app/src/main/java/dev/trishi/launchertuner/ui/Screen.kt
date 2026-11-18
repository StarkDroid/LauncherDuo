package dev.trishi.launchertuner.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.trishi.launchertuner.R
import kotlin.math.cos

@Composable
fun DuoScreen(vm: DuoViewModel = viewModel()) {
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_duo_mark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Launcher DUO",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Turn the phone and the home screen bends into the edge you turn toward.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            TiltPreview()

            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Enable effect", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                !vm.accessibility -> "Needs the accessibility service"
                                vm.enabled -> "Home screen only"
                                else -> "Off"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = vm.enabled,
                        onCheckedChange = { vm.updateEnabled(it) },
                        enabled = vm.accessibility,
                    )
                }
            }

            if (!vm.accessibility) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "The fold is drawn by an accessibility service, which is also how it takes the single screenshot it needs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Open Accessibility") }
            }

            if (vm.enabled) {
                Spacer(Modifier.height(18.dp))
                Text("How far you turn", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Full bend at ${vm.angle.toInt()}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = vm.angle,
                    onValueChange = { vm.updateAngle(it) },
                    valueRange = 16f..45f,
                    steps = 28,
                )

                Text("Warp amount", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${"%.1f".format(vm.strength)}×",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = vm.strength,
                    onValueChange = { vm.updateStrength(it) },
                    valueRange = 0.4f..2.5f,
                    steps = 20,
                )

                Text(
                    "Home screen only. The pose is re-learned when you arrive here, when you touch the screen, when you pull down the shade, and when you open recents — the fold pauses during each of those and resumes once the reading settles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A hand holding the phone and turning it, foreshortened to read as yaw. */
@Composable
private fun TiltPreview() {
    val transition = rememberInfiniteTransition(label = "tilt")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phase",
    )

    val skin = Color(0xFFD3A183)
    val skinShade = Color(0xFFB98466)
    val body = Color(0xFF15151A)
    val glass = Color(0xFF22222B)
    val accent = MaterialTheme.colorScheme.primary

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.fillMaxWidth().height(210.dp).clipToBounds()) {
            val pivot = Offset(size.width / 2f, size.height * 0.92f)
            val scaleX = 0.90f + 0.10f * cos(phase * Math.PI.toFloat())

            scale(scaleX, 1f, pivot = pivot) {
                val phoneW = size.width * 0.28f
                val phoneH = size.height * 0.62f
                val cx = size.width / 2f
                val top = size.height * 0.10f

                drawRoundRect(
                    color = skinShade,
                    topLeft = Offset(cx - phoneW * 0.42f, pivot.y - 10f),
                    size = Size(phoneW * 0.84f, size.height * 0.35f),
                    cornerRadius = CornerRadius(26f, 26f),
                )
                drawRoundRect(
                    color = skin,
                    topLeft = Offset(cx - phoneW * 0.60f, top + phoneH * 0.55f),
                    size = Size(phoneW * 1.20f, phoneH * 0.50f),
                    cornerRadius = CornerRadius(30f, 30f),
                )

                drawRoundRect(
                    color = body,
                    topLeft = Offset(cx - phoneW / 2f, top),
                    size = Size(phoneW, phoneH),
                    cornerRadius = CornerRadius(20f, 20f),
                )
                drawRoundRect(
                    color = glass,
                    topLeft = Offset(cx - phoneW / 2f + 6f, top + 6f),
                    size = Size(phoneW - 12f, phoneH - 12f),
                    cornerRadius = CornerRadius(16f, 16f),
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.35f),
                    topLeft = Offset(cx - phoneW / 2f + 14f, top + phoneH * 0.16f),
                    size = Size(phoneW - 28f, phoneH * 0.10f),
                    cornerRadius = CornerRadius(8f, 8f),
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.18f),
                    topLeft = Offset(cx - phoneW / 2f + 14f, top + phoneH * 0.34f),
                    size = Size(phoneW - 28f, phoneH * 0.34f),
                    cornerRadius = CornerRadius(12f, 12f),
                )

                val fingerW = phoneW * 0.20f
                for (i in 0 until 4) {
                    val y = top + phoneH * (0.30f + i * 0.14f)
                    drawRoundRect(
                        color = skin,
                        topLeft = Offset(cx - phoneW / 2f - fingerW * 0.55f, y),
                        size = Size(fingerW, phoneH * 0.10f),
                        cornerRadius = CornerRadius(14f, 14f),
                    )
                }
                drawRoundRect(
                    color = skin,
                    topLeft = Offset(cx + phoneW / 2f - fingerW * 0.35f, top + phoneH * 0.52f),
                    size = Size(fingerW * 0.95f, phoneH * 0.26f),
                    cornerRadius = CornerRadius(16f, 16f),
                )
            }
        }
        Text(
            "Turn the phone left or right, as if showing the screen to someone",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
