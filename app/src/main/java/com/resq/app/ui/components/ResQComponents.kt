package com.resq.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resq.app.model.SafetyState
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQShapes
import com.resq.app.ui.theme.ResQTextStyles

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

/** Shared button body so every action has identical padding and hit target. */
@Composable
private fun ResQButtonShell(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    minHeight: Dp,
    fill: Color,
    contentColor: Color,
    textStyle: TextStyle,
    borderColor: Color? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .minimumInteractiveComponentSize()
            .clip(ResQShapes.pill)
            .background(fill)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, ResQShapes.pill)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = textStyle,
            color = contentColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}

/** Primary action: 72dp, accent fill, onAccent text, 20sp/600. */
@Composable
fun ResQPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 72.dp,
) {
    val c = LocalResQColors.current
    ResQButtonShell(
        text = text,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        minHeight = minHeight,
        fill = c.accent,
        contentColor = c.onAccent,
        textStyle = ResQTextStyles.buttonLarge,
    )
}

/** Secondary action: 60dp, glass fill + border, text colour, 18sp/600. */
@Composable
fun ResQSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 60.dp,
) {
    val c = LocalResQColors.current
    ResQButtonShell(
        text = text,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        minHeight = minHeight,
        fill = c.glass,
        contentColor = c.text,
        textStyle = ResQTextStyles.button,
        borderColor = c.glassBorder,
    )
}

/** Danger action: 72dp, #5A0610 fill, white text, 20sp/600. Emergency only. */
@Composable
fun ResQDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 72.dp,
) {
    val c = LocalResQColors.current
    ResQButtonShell(
        text = text,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        minHeight = minHeight,
        fill = c.danger,
        contentColor = c.onDanger,
        textStyle = ResQTextStyles.buttonLarge,
    )
}

/** Text action: min 48dp, no fill, 16sp/600. Used for Settings, Back. */
@Composable
fun ResQTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalResQColors.current
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .minimumInteractiveComponentSize()
            .clip(ResQShapes.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ResQTextStyles.button,
            color = c.text,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

/** Soft halo + solid core used by every status indicator. */
@Composable
fun StatusDot(
    color: Color,
    haloSize: Dp,
    coreSize: Dp,
    modifier: Modifier = Modifier,
    haloAlpha: Float = 0.25f,
) {
    Box(modifier = modifier.size(haloSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .clip(ResQShapes.round)
                .background(color.copy(alpha = haloAlpha)),
        )
        Box(
            Modifier
                .size(coreSize)
                .clip(ResQShapes.round)
                .background(color),
        )
    }
}

/** Status capsule: ~36dp, glass fill + border, 14sp/600, optional accent dot. */
@Composable
fun StatusPill(
    label: String,
    modifier: Modifier = Modifier,
    state: SafetyState = SafetyState.ACTIVE,
    dot: Boolean = true,
) {
    val c = LocalResQColors.current
    val dotColor = when (state) {
        SafetyState.ACTIVE -> c.statusActive
        SafetyState.CAUTION -> c.statusCaution
        SafetyState.CRITICAL -> c.statusCritical
    }
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(ResQShapes.pill)
            .background(c.glass)
            .border(1.dp, c.glassBorder, ResQShapes.pill)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dot) StatusDot(color = dotColor, haloSize = 14.dp, coreSize = 6.dp)
        Text(
            text = label.uppercase(),
            style = ResQTextStyles.labelLg,
            color = c.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Glass rows
// ---------------------------------------------------------------------------

/** Glass card (row group) per the DESIGN.md recipe. */
@Composable
fun GlassRowGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ResQSurface(
        modifier = modifier,
        padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
        content = content,
    )
}

/** 1dp divider between rows inside a [GlassRowGroup]. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = LocalResQColors.current.divider,
    )
}

/**
 * Label-left / value-right row for a [GlassRowGroup]. Rows are min 52dp; status
 * values start with a 10dp accent dot. Optional [description] is shown below
 * the label as a caption.
 *
 * WHY THE VALUE IS CAPPED RATHER THAN WEIGHTED
 *
 * This row used to give the label column and the value column an EQUAL
 * `weight(1f)` share. On a narrow phone that is close to unusable, because the
 * value's share is fixed even when the value is empty:
 *
 *  - an empty value ("Permissions") still reserved half the row, so the label
 *    wrapped for no reason;
 *  - a long value ("Waiting for permission") pushed the label down to ~130dp,
 *    which broke "Text messages" across two lines and squeezed its description
 *    to five;
 *  - "Emergency settings" wrapped, and "Developer / Test Mode" plus its
 *    description took five lines between them.
 *
 * So the value now takes only the width it actually needs, bounded by
 * [ValueColumnMaxWidth] so a genuinely long value (a hospital address) wraps
 * inside a known share instead of eating the label, and the label absorbs the
 * remainder. Nothing about the type scale changes - the labels are still the
 * 17 sp DESIGN.md specifies; the rows were simply starving them of space.
 *
 * A value that is genuinely content rather than a status belongs under the
 * label instead of beside it: pass [stackedValue].
 */
@Composable
fun GlassRow(
    label: String,
    value: String = "",
    modifier: Modifier = Modifier,
    valueColor: Color = LocalResQColors.current.text,
    showDot: Boolean = false,
    onClick: (() -> Unit)? = null,
    description: String? = null,
    stackedValue: Boolean = false,
) {
    val c = LocalResQColors.current
    val clickModifier = if (onClick != null) {
        Modifier
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
    } else {
        Modifier
    }

    if (stackedValue || shouldStackRowValue(value)) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(clickModifier)
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GlassRowLabel(label = label, showDot = showDot)
            description?.let {
                Text(text = it, style = ResQTextStyles.caption, color = c.textMuted)
            }
            if (value.isNotEmpty()) {
                Text(text = value, style = ResQTextStyles.rowStrong, color = valueColor)
            }
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(clickModifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            GlassRowLabel(label = label, showDot = showDot)
            description?.let {
                Text(
                    text = it,
                    style = ResQTextStyles.caption,
                    color = c.textMuted,
                )
            }
        }
        Text(
            text = value,
            style = ResQTextStyles.rowStrong,
            color = valueColor,
            textAlign = TextAlign.End,
            // Deliberately unweighted: measured first at its intrinsic width, so
            // a short or empty value costs the label nothing. Capped so a long
            // one wraps rather than starving the label - which is what the old
            // equal weighting could not do.
            modifier = Modifier.widthIn(max = ValueColumnMaxWidth),
        )
        if (onClick != null) ChevronRight(color = c.textMuted)
    }
}

/**
 * Upper bound on the value column of a side-by-side [GlassRow].
 *
 * A safety net, not the primary mechanism: [shouldStackRowValue] has already
 * moved genuinely long values onto their own line, so the values still sharing
 * the row are short statuses. The cap only guarantees the label never drops
 * below roughly 130dp on the narrowest supported phone, where the row is
 *
 *   387dp screen - 2x24dp gutter - horizontal safe-area inset - 2x20dp group
 *   inset - 2x12dp gaps - 24dp chevron = ~251dp for label + value
 */
private val ValueColumnMaxWidth = 120.dp

/**
 * Longest value that still reads as a status beside its label rather than as
 * body text under it.
 *
 * Fourteen characters is where "Not granted" ends and "Waiting for permission"
 * begins. On the narrowest supported phone the label and value together have
 * about 251dp to share, so a long value and a long label cannot both be served
 * side by side - trying produced two-line labels ("Motion sensors") next to
 * two-line values, and descriptions squeezed to five lines each. Splitting by
 * length lets the common short status sit beside the label and gives anything
 * longer the full row width on its own line.
 *
 * Pure and length-based so it is directly testable; measured against Inter
 * semibold at the 17sp the DESIGN.md row style specifies.
 */
internal const val MaxSideBySideRowValueLength = 14

/** True when [value] is long enough to read as body text and belongs under the label. */
internal fun shouldStackRowValue(value: String): Boolean =
    value.length > MaxSideBySideRowValueLength

/** The label line of a [GlassRow]: text plus its optional accent status dot. */
@Composable
private fun GlassRowLabel(label: String, showDot: Boolean) {
    val c = LocalResQColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = ResQTextStyles.row,
            color = c.textMuted,
        )
        if (showDot) StatusDot(color = c.accent, haloSize = 10.dp, coreSize = 6.dp)
    }
}

/** Uppercase section label. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = ResQTextStyles.sectionLabel,
        color = LocalResQColors.current.textMuted,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

/**
 * Toggle: accent when on, knob onAccent, 48dp min touch area.
 *
 * The whole row is one toggleable node with Role.Switch so a screen reader
 * announces "label, switch, on/off" in a single focus stop; the inner Material
 * Switch keeps handling the visual state but no longer adds a second,
 * label-less focus stop.
 */
@Composable
fun ResQToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalResQColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(vertical = 8.dp)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = ResQTextStyles.row,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            // The row owns the interaction and the semantics; forwarding keeps
            // the visual knob in sync without a duplicate click target.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.accent,
                checkedBorderColor = c.accent,
                uncheckedThumbColor = c.text,
                uncheckedTrackColor = c.glass,
                uncheckedBorderColor = c.glassBorder,
            ),
        )
    }
}

/** Segmented control: glass container, accent selected segment, 12dp padding. */
@Composable
fun ResQSegmentedControl(
    labels: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalResQColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ResQShapes.pill)
            .background(c.glass)
            .border(1.dp, c.glassBorder, ResQShapes.pill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(ResQShapes.pill)
                    .background(if (isSelected) c.accent else Color.Transparent)
                    .minimumInteractiveComponentSize()
                    .clickable { onSelected(index) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = ResQTextStyles.labelLg,
                    color = if (isSelected) c.onAccent else c.text,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Single-line text input in the ResQ look: glass fill, 1dp border, accent cursor
 * and focus ring, caption-sized supporting text.
 *
 * Used by the emergency-contact editor. It carries no validation logic of its
 * own - the contact rules live in [com.resq.app.contacts.EmergencyContactsPolicy]
 * so they can be unit tested without a device.
 */
@Composable
fun ResQTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    val c = LocalResQColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supportingText?.let { text ->
            { Text(text, style = ResQTextStyles.caption) }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.text,
            unfocusedTextColor = c.text,
            errorTextColor = c.text,
            focusedContainerColor = c.glass,
            unfocusedContainerColor = c.glass,
            errorContainerColor = c.glass,
            cursorColor = c.accent,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.glassBorder,
            errorBorderColor = c.statusCritical,
            focusedLabelColor = c.accent,
            unfocusedLabelColor = c.textMuted,
            errorLabelColor = c.statusCritical,
            focusedSupportingTextColor = c.textMuted,
            unfocusedSupportingTextColor = c.textMuted,
            errorSupportingTextColor = c.statusCritical,
        ),
    )
}

/** Confidence bar: 90x6dp, divider track, accent fill. Developer only. */
@Composable
fun ConfidenceBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val c = LocalResQColors.current
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(ResQShapes.round)
            .background(c.divider),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(ResQShapes.round)
                .background(c.accent),
        )
    }
}
