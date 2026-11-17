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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.sin

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
            Text(
                "Launcher DUO",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Tilt the phone and the home screen bends into the edge you tilt toward.",
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
                    "The effect is drawn over the home screen by an accessibility service, which is also how it takes the one screenshot it needs.",
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
                Text("How far you tilt", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Full bend at ${vm.angle.toInt()}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = vm.angle,
                    onValueChange = { vm.updateAngle(it) },
                    valueRange = 12f..45f,
                    steps = 32,
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dot = if (vm.centred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    Box(Modifier.width(7.dp).height(7.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (vm.centred) "Centre locked" else "Hold still to set the centre",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.recentre() }) { Text("Set neutral") }
                }
                Text(
                    "Home screen only. Holding the phone still for a second re-centres it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Looping illustration of the gesture: the card leans and its far edge bends. */
@Composable
private fun TiltPreview() {
    val transition = rememberInfiniteTransition(label = "tilt")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phase",
    )

    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceVariant

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(surface.copy(alpha = 0.35f))
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.fillMaxWidth().height(190.dp)) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val tilt = sin(phase * Math.PI.toFloat()) * 10f
            val phoneW = size.width * 0.30f
            val phoneH = size.height * 0.82f

            rotate(tilt, pivot = centre) {
                drawRoundRect(
                    color = Color(0xFF17171C),
                    topLeft = Offset(centre.x - phoneW / 2, centre.y - phoneH / 2),
                    size = Size(phoneW, phoneH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f),
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.85f),
                    topLeft = Offset(centre.x + phoneW * 0.28f, centre.y - phoneH / 2 + 8f),
                    size = Size(phoneW * 0.22f, phoneH - 16f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.25f),
                    topLeft = Offset(centre.x - phoneW / 2, centre.y - phoneH / 2),
                    size = Size(phoneW, phoneH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f),
                    style = Stroke(width = 3f),
                )
            }

            drawCircle(
                color = accent.copy(alpha = 0.18f),
                radius = 26f,
                center = Offset(centre.x - size.width * 0.30f, centre.y - size.height * 0.30f),
            )
            drawLine(
                color = accent.copy(alpha = 0.55f),
                start = Offset(centre.x - size.width * 0.30f, centre.y - size.height * 0.30f),
                end = Offset(centre.x - size.width * 0.18f, centre.y - size.height * 0.30f),
                strokeWidth = 4f,
            )
        }
        Text(
            "Tilt left or right — the edge you tilt toward folds inward",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
