package com.alexdyakin.lexicon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.alexdyakin.lexicon.ui.theme.Sizes
import com.alexdyakin.lexicon.ui.theme.Spacing

/**
 * A transport row that is centred **by construction**.
 *
 * The rule: the centre control is unweighted, so it is measured first at its own width; both side
 * groups then take `weight(1f)` of whatever remains, which makes them *identical widths* however
 * many controls each one holds. The centre therefore sits at exactly half the row, and adding a
 * control to one side can never push the other side off the screen.
 *
 * That is precisely what was broken before, in two different ways:
 *
 * - `PlayerScreen` laid seven fixed 48dp controls into a row with
 *   `spacedBy(4.dp, Alignment.CenterHorizontally)`. In audiobook mode that is 400dp of children in
 *   304dp of space, and because the arrangement centres the overflow the row started at x = −48 —
 *   so shuffle was clipped off the *left* edge and the speed button off the *right*.
 * - `DedicatedMediaPlayerScreen` put two **unweighted, content-sized** pills either side of a fixed
 *   play button. Compose measures unweighted children in declaration order, so "Previous" took the
 *   ~128dp it wanted and "Next" — measured last — was handed ~76dp when it needed ~99dp. Next came
 *   out visibly narrower, its label clipped, and the play button sat ~14dp right of centre.
 *
 * CONTRACT: at most two [Sizes.transportSlot] controls per side, a centre no wider than
 * [Sizes.transportPrimary], and a host that uses the standard [Spacing.gutter].
 * `(48 + 4 + 48) + 8 + 64 + 8 + (48 + 4 + 48) = 280dp`, inside the 288dp of content a 320dp phone
 * has after two 16dp gutters. Anything beyond two a side belongs in [extras], which wraps onto a
 * second row rather than clipping — so the controls that move are shuffle and speed, never
 * prev/play/next.
 *
 * The side groups are weighted, so they can never push each other off screen; but a *fixed* child
 * can still overflow its own weighted slot, which is why the budget above has to hold. A host with
 * wider padding or a bigger centre control breaks it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransportBar(
    center: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    extras: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.touch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // End-aligned so the leading controls hug the centre, mirroring the Start-aligned
            // trailing group. Both groups are the same width, so the mirroring is exact.
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = leading,
            )
            Spacer(Modifier.width(Spacing.sm))
            center()
            Spacer(Modifier.width(Spacing.sm))
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.Start),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        }

        if (extras != null) {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) { extras() }
        }
    }
}

/**
 * Three or more peer actions, or any row of chips.
 *
 * The blanket replacement for the plain `Row`s that overflowed: six speed chips needing 318dp in
 * 256dp, three `Button`s needing 418dp in 328dp, filter chips that fit at 360dp and clipped at
 * 320dp. A `FlowRow` wraps instead, so the failure mode is a taller row rather than a button you
 * cannot reach.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, horizontalAlignment),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) { content() }
}

/**
 * Exactly two actions, always equal width, secondary first and primary second.
 *
 * Equal weights mean neither can starve the other, and fixing the order settles by construction
 * the question screens kept answering differently — `MediaUploadScreen` made two peer view
 * switchers a filled `Button` and an `OutlinedButton`, `ProfileScreen` rendered a destructive
 * "Remove" as the heaviest control on the card, and `QueueManagerScreen` put the outlined button
 * first and the filled one second.
 */
@Composable
fun SplitActions(
    secondary: @Composable RowScope.() -> Unit,
    primary: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), content = secondary)
        Row(Modifier.weight(1f), content = primary)
    }
}

/**
 * A label and a switch as one unit, so a pair of them can wrap inside an [ActionRow] instead of
 * running off the card the way "Auto-play" and "Shuffle" did (283dp of content in 256dp).
 */
@Composable
fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.heightIn(min = Sizes.touch),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * A full-width setting: a label that takes the room it needs, and a switch pinned to the end.
 *
 * Distinct from [LabeledSwitch], which is content-sized so that a pair of them can wrap inside an
 * [ActionRow]. This is the settings-list form, and it exists because the same
 * `Row { Text(weight(1f)); Switch(...) }` was retyped at eight call sites — with the label left
 * uncapped at every one of them, and nothing holding the row to the touch minimum.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touch),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * A list row: text that is allowed to grow only so far, and a trailing action slot of **fixed
 * width**.
 *
 * Two bugs die here. Uncapped `Text` in a `weight(1f)` column pushed trailing buttons out of
 * shape all over the app — worst in `AlchemyScreen`, where an ingredient's joined "Known effects"
 * list is unbounded server data and could grow a row to six lines with two tiny buttons floating
 * at its centre. And a fixed action slot means a label that changes length on tap — Alchemy's
 * "Brew" becoming "Selected" — can no longer shift the whole row sideways.
 */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    actionSlotWidth: Dp = Sizes.listAction,
    action: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touch),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A RowScope, so the one text column a list row almost always has can claim `weight(1f)`
        // directly. Without it every call site wrapped the content in a Row of its own purely to
        // get the weight back, and each one picked its own spacing and alignment while doing so.
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (action != null) {
            Row(
                Modifier.widthIn(min = actionSlotWidth),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                action()
            }
        }
    }
}
