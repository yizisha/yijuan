package com.flowreader.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flowreader.app.core.designsystem.token.FlowRadius
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.core.R

/** Quiet text tabs with a short underline; the complete cell remains a 48dp target. */
@Composable
fun FlowTabBar(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                    .drawBehind {
                        if (active) {
                            val half = 12.dp.toPx()
                            drawLine(
                                colors.primary,
                                Offset(size.width / 2 - half, size.height - 1.dp.toPx()),
                                Offset(size.width / 2 + half, size.height - 1.dp.toPx()),
                                1.5.dp.toPx()
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) colors.onSurface else colors.onSurfaceVariant
                )
            }
        }
    }
}

/** One surface for one functional group, shared by settings and the reader sheet. */
@Composable
fun FlowPreferenceGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: Dp = FlowSpacing.lg,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = FlowSpacing.sm)
            )
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(
                Modifier.fillMaxWidth().padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm),
                content = content
            )
        }
    }
}

@Composable
fun FlowExpandableGroup(title: String, summary: String, customized: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.flow_expanded else R.string.flow_collapsed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = state }
                .clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = FlowSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlowSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (customized) {
                    Text(
                        stringResource(R.string.flow_customized),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm), content = content)
    }
}

@Composable
fun FlowChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
            contentColor = (if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant).copy(alpha = if (enabled) 1f else .4f),
            shape = RoundedCornerShape(FlowRadius.sm),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (selected) colors.primary.copy(alpha = .35f) else colors.outlineVariant.copy(alpha = .5f)
            )
        ) {
            Row(
                Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                leadingIcon?.invoke()
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelMedium) { label() }
                trailingIcon?.invoke()
            }
        }
    }
}

/** The same quiet slider geometry on full pages and reading shortcuts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Slider(
        value = value.coerceIn(valueRange),
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 48.dp),
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        thumb = { Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) },
        track = {
            SliderDefaults.Track(
                it,
                Modifier.height(3.dp),
                thumbTrackGapSize = 0.dp,
                drawStopIndicator = null,
                drawTick = { _, _ -> }
            )
        }
    )
}
