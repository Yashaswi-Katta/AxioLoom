package com.axioloom.app.ui.canvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axioloom.app.domain.dag.FastNumericPropagator
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive Parameter Slider Pill for 60fps live parameter scrubbing.
 * Features auto-bounds calculation based on initial scalar v0, 1-tap preset range chips,
 * and a 60fps Play/Oscillate toggle for dynamic animation.
 */
@Composable
fun ParameterSliderPill(
    variableName: String,
    initialValue: Double,
    onValueChange: (Double) -> Unit,
    onValueChangeFinished: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    // Determine initial default bounds based on v0
    val defaultMin = remember(variableName, initialValue) {
        if (initialValue == 0.0) -10.0f
        else min(-abs(initialValue).toFloat() * 2f, (initialValue * 0.1).toFloat())
    }
    val defaultMax = remember(variableName, initialValue) {
        if (initialValue == 0.0) 10.0f
        else max(abs(initialValue).toFloat() * 2f, (initialValue * 2.0).toFloat())
    }

    var rangeMin by remember(variableName, defaultMin) {
        val minVal = if (initialValue.toFloat() < defaultMin) initialValue.toFloat() * 1.5f else defaultMin
        mutableFloatStateOf(minVal)
    }
    var rangeMax by remember(variableName, defaultMax) {
        val maxVal = if (initialValue.toFloat() > defaultMax) initialValue.toFloat() * 1.5f else defaultMax
        mutableFloatStateOf(maxVal)
    }
    var sliderValue by remember(variableName, initialValue) {
        mutableFloatStateOf(initialValue.toFloat().coerceIn(rangeMin, rangeMax))
    }
    var isPlaying by remember { mutableStateOf(false) }

    androidx.compose.runtime.DisposableEffect(variableName) {
        onDispose {
            onValueChangeFinished(sliderValue.toDouble())
        }
    }

    // 60fps Oscillating animation loop
    LaunchedEffect(isPlaying, rangeMin, rangeMax) {
        if (!isPlaying) return@LaunchedEffect
        var forward = true
        val speed = (rangeMax - rangeMin) / 120f // ~2 second sweep across range at 60fps
        while (isActive && isPlaying) {
            val step = if (forward) speed else -speed
            var next = sliderValue + step
            if (next >= rangeMax) {
                next = rangeMax
                forward = false
            } else if (next <= rangeMin) {
                next = rangeMin
                forward = true
            }
            sliderValue = next
            onValueChange(next.toDouble())
            kotlinx.coroutines.delay(16L) // ~60fps frame pace
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1E232A),
        tonalElevation = 4.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Header Row: Var name, current value, play/pause button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = variableName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB300)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "=  ${FastNumericPropagator.formatSignificant(sliderValue.toDouble(), 4)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFF4F5F7)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            isPlaying = !isPlaying
                            if (!isPlaying) {
                                onValueChangeFinished(sliderValue.toDouble())
                            }
                        }
                    ) {
                        Text(
                            text = if (isPlaying) "\u23F8" else "\u25B6", // Pause or Play
                            fontSize = 18.sp,
                            color = if (isPlaying) Color(0xFFFFD54F) else Color(0xFFFFB300)
                        )
                    }
                }
            }

            // Slider Track
            Slider(
                value = sliderValue.coerceIn(rangeMin, rangeMax),
                onValueChange = { newVal ->
                    if (isPlaying) isPlaying = false
                    sliderValue = newVal
                    onValueChange(newVal.toDouble())
                },
                onValueChangeFinished = {
                    onValueChangeFinished(sliderValue.toDouble())
                },
                valueRange = rangeMin..rangeMax,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFFB300),
                    activeTrackColor = Color(0xFFFFB300),
                    inactiveTrackColor = Color(0xFF333B44)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Range Bounds and Preset Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "[${FastNumericPropagator.formatSignificant(rangeMin.toDouble(), 3)}, ${FastNumericPropagator.formatSignificant(rangeMax.toDouble(), 3)}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF88929E),
                    modifier = Modifier.padding(end = 4.dp)
                )

                PresetChip(
                    label = "[-10, 10]",
                    isSelected = rangeMin == -10f && rangeMax == 10f,
                    onClick = {
                        rangeMin = -10f
                        rangeMax = 10f
                        sliderValue = sliderValue.coerceIn(rangeMin, rangeMax)
                        onValueChange(sliderValue.toDouble())
                        onValueChangeFinished(sliderValue.toDouble())
                    }
                )

                PresetChip(
                    label = "[0, 100]",
                    isSelected = rangeMin == 0f && rangeMax == 100f,
                    onClick = {
                        rangeMin = 0f
                        rangeMax = 100f
                        sliderValue = sliderValue.coerceIn(rangeMin, rangeMax)
                        onValueChange(sliderValue.toDouble())
                        onValueChangeFinished(sliderValue.toDouble())
                    }
                )

                PresetChip(
                    label = "[0, 2\u03C0]",
                    isSelected = rangeMin == 0f && abs(rangeMax - (2f * PI.toFloat())) < 0.01f,
                    onClick = {
                        rangeMin = 0f
                        rangeMax = (2.0 * PI).toFloat()
                        sliderValue = sliderValue.coerceIn(rangeMin, rangeMax)
                        onValueChange(sliderValue.toDouble())
                        onValueChangeFinished(sliderValue.toDouble())
                    }
                )

                PresetChip(
                    label = "Default",
                    isSelected = rangeMin == defaultMin && rangeMax == defaultMax,
                    onClick = {
                        rangeMin = defaultMin
                        rangeMax = defaultMax
                        sliderValue = sliderValue.coerceIn(rangeMin, rangeMax)
                        onValueChange(sliderValue.toDouble())
                        onValueChangeFinished(sliderValue.toDouble())
                    }
                )
            }
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF2E3842),
            selectedLabelColor = Color(0xFFFFB300),
            containerColor = Color(0xFF191D22),
            labelColor = Color(0xFFC0C7D0)
        )
    )
}
