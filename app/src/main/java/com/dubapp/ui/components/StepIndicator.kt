package com.dubapp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dubapp.ui.theme.StepActive
import com.dubapp.ui.theme.StepComplete
import com.dubapp.ui.theme.StepInactive

@Composable
fun StepIndicator(
    currentStep: Int,
    totalSteps: Int = 3,
    modifier: Modifier = Modifier
) {
    val stepLabels = listOf("استخراج SRT", "ترجمة", "دبلجة")

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (step in 1..totalSteps) {
                val isCompleted = step < currentStep
                val isActive = step == currentStep

                val circleColor by animateColorAsState(
                    targetValue = when {
                        isCompleted -> StepComplete
                        isActive -> StepActive
                        else -> StepInactive
                    },
                    animationSpec = tween(durationMillis = 400),
                    label = "stepColor"
                )

                val scale by animateFloatAsState(
                    targetValue = if (isActive) 1.15f else 1f,
                    animationSpec = tween(durationMillis = 300),
                    label = "stepScale"
                )

                // Step circle + label
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .scale(scale)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(circleColor)
                    ) {
                        if (isCompleted) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "مكتمل",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Text(
                                text = "$step",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = stepLabels.getOrElse(step - 1) { "" },
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = if (isActive || isCompleted)
                            MaterialTheme.colorScheme.onSurface
                        else
                            StepInactive,
                        modifier = Modifier.padding(top = 6.dp),
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                    )
                }

                // Connector line between steps
                if (step < totalSteps) {
                    val lineColor by animateColorAsState(
                        targetValue = if (step < currentStep) StepComplete else StepInactive.copy(alpha = 0.4f),
                        animationSpec = tween(durationMillis = 400),
                        label = "lineColor"
                    )
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    ) {
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, size.height / 2),
                            end = Offset(size.width, size.height / 2),
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                }
            }
        }
    }
}
