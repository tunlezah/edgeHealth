package au.mark.kinetiq.anim

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural exercise animation player.
 *
 * Rendering quality bar (spec §8): rounded caps everywhere, consistent stroke weights,
 * theme-aware colors, subtle motion-path arc, optional working-muscle tint, cyclic
 * ease-in/out interpolation driven by frame time (60fps: one pose solve + a dozen
 * line draws per frame — trivially cheap).
 */
@Composable
fun ExerciseAnimationView(
    animationId: String?,
    modifier: Modifier = Modifier,
    contentDesc: String = "Exercise animation",
    paused: Boolean = false,
) {
    val anim = animationId?.let { AnimationRegistry.byId[it] } ?: return
    var timeMs by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(anim.id, paused) {
        if (paused) return@LaunchedEffect
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) timeMs += (now - last) / 1_000_000f
                last = now
            }
        }
    }

    val bodyColor = MaterialTheme.colorScheme.primary
    val farColor = bodyColor.copy(alpha = 0.38f)
    val highlight = MaterialTheme.colorScheme.tertiary
    val propColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val groundColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    val pathColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f)

    Canvas(
        modifier = modifier
            .testTag("anim:${anim.id}")
            .semantics { contentDescription = contentDesc },
    ) {
        val style = RenderStyle(bodyColor, farColor, highlight, propColor, groundColor, pathColor)
        when (anim) {
            is KeyframeAnim -> drawKeyframeAnim(anim, timeMs, style)
            is SpinAnim -> drawSpinAnim(anim, timeMs, style)
            is EllipticalAnim -> drawEllipticalAnim(anim, timeMs, style)
        }
    }
}

data class RenderStyle(
    val body: Color,
    val far: Color,
    val highlight: Color,
    val prop: Color,
    val ground: Color,
    val path: Color,
)

// Author-space viewport mapped into the canvas.
private const val VIEW_LEFT = -0.72f
private const val VIEW_RIGHT = 0.72f
private const val VIEW_TOP = -0.66f
private const val VIEW_BOTTOM = 0.56f

private fun DrawScope.mapX(x: Float): Float = (x - VIEW_LEFT) / (VIEW_RIGHT - VIEW_LEFT) * size.width
private fun DrawScope.mapY(y: Float): Float = (y - VIEW_TOP) / (VIEW_BOTTOM - VIEW_TOP) * size.height
private fun DrawScope.pt(j: Joint): Offset = Offset(mapX(j.x), mapY(j.y))
private fun DrawScope.scale(v: Float): Float = v / (VIEW_RIGHT - VIEW_LEFT) * size.width

private fun DrawScope.limb(a: Joint, b: Joint, color: Color, width: Float) {
    drawLine(color, pt(a), pt(b), strokeWidth = width, cap = StrokeCap.Round)
}

private fun DrawScope.drawGround(style: RenderStyle) {
    drawLine(
        style.ground,
        Offset(mapX(-0.62f), mapY(AnimationRegistry.GY + 0.078f)),
        Offset(mapX(0.62f), mapY(AnimationRegistry.GY + 0.078f)),
        strokeWidth = scale(0.012f),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawSkeleton(sk: Skeleton, style: RenderStyle, muscle: MuscleGroup) {
    val w = scale(0.042f)
    val wFar = scale(0.036f)

    fun colorFor(group: MuscleGroup, base: Color): Color =
        if (muscle == group || muscle == MuscleGroup.FULL_BODY) style.highlight.copy(alpha = base.alpha) else base

    val legColor = colorFor(MuscleGroup.LEGS, style.body)
    val legColorG = colorFor(MuscleGroup.GLUTES, legColor)
    val armColor = colorFor(MuscleGroup.ARMS, style.body)
    val torsoColor = when (muscle) {
        MuscleGroup.CORE, MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.FULL_BODY -> style.highlight
        else -> style.body
    }
    val shoulderColor = colorFor(MuscleGroup.SHOULDERS, armColor)

    // far side first (dimmer)
    val farLeg = legColorG.copy(alpha = 0.38f)
    val farArm = shoulderColor.copy(alpha = 0.38f)
    limb(sk.far.hip, sk.far.knee, farLeg, wFar)
    limb(sk.far.knee, sk.far.ankle, farLeg, wFar)
    limb(sk.far.ankle, sk.far.toe, farLeg, wFar)
    limb(sk.far.shoulder, sk.far.elbow, farArm, wFar)
    limb(sk.far.elbow, sk.far.wrist, farArm, wFar)

    // torso + head
    limb(sk.pelvis, sk.chest, torsoColor, scale(0.052f))
    limb(sk.chest, sk.neckTop, style.body, w)
    drawCircle(style.body, radius = scale(Proportions.HEAD_R * 0.92f), center = pt(sk.headCenter))

    // near side
    limb(sk.near.hip, sk.near.knee, legColorG, w)
    limb(sk.near.knee, sk.near.ankle, legColorG, w)
    limb(sk.near.ankle, sk.near.toe, legColorG, w)
    limb(sk.near.shoulder, sk.near.elbow, shoulderColor, w)
    limb(sk.near.elbow, sk.near.wrist, shoulderColor, w)
}

// ---------------------------------------------------------------------- keyframed

private fun DrawScope.drawKeyframeAnim(anim: KeyframeAnim, timeMs: Float, style: RenderStyle) {
    val phase = (timeMs % anim.durationMs) / anim.durationMs
    val pose = anim.poseAt(phase)

    when (anim.prop) {
        Prop.MAT -> drawMat(style)
        Prop.WALL -> { drawGround(style); drawWall(style) }
        Prop.REFORMER -> drawReformer(style, pose.prop)
        else -> drawGround(style)
    }

    // subtle motion-path arc for the key moving joint
    if (anim.pathJoint != PathJoint.NONE) {
        val path = Path()
        var first = true
        for (i in 0..28) {
            val p = anim.poseAt(i / 28f)
            val sk = Rig.solve(p, anim.facing)
            val j = when (anim.pathJoint) {
                PathJoint.WRIST -> sk.near.wrist
                PathJoint.ANKLE -> sk.near.ankle
                PathJoint.PELVIS -> sk.pelvis
                PathJoint.HEAD -> sk.headCenter
                PathJoint.NONE -> sk.pelvis
            }
            val o = pt(j)
            if (first) { path.moveTo(o.x, o.y); first = false } else path.lineTo(o.x, o.y)
        }
        drawPath(path, style.path, style = Stroke(width = scale(0.014f), cap = StrokeCap.Round))
    }

    drawSkeleton(Rig.solve(pose, anim.facing), style, anim.muscle)
}

// ---------------------------------------------------------------------- props

private fun DrawScope.drawMat(style: RenderStyle) {
    val y = AnimationRegistry.GY + 0.055f
    drawLine(
        style.prop,
        Offset(mapX(-0.52f), mapY(y)), Offset(mapX(0.52f), mapY(y)),
        strokeWidth = scale(0.03f), cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawWall(style: RenderStyle) {
    drawLine(
        style.prop,
        Offset(mapX(0.245f), mapY(-0.55f)), Offset(mapX(0.245f), mapY(AnimationRegistry.GY + 0.07f)),
        strokeWidth = scale(0.03f), cap = StrokeCap.Round,
    )
}

/** Reformer: rail, sliding carriage (animated by carriage 0..1), springs, foot bar, headrest. */
private fun DrawScope.drawReformer(style: RenderStyle, carriage: Float) {
    val railY = AnimationRegistry.GY + 0.02f
    val w = scale(0.024f)
    // frame rail + legs
    limbLine(-0.56f, railY, 0.56f, railY, style.prop, w)
    limbLine(-0.52f, railY, -0.52f, railY + 0.06f, style.prop, w)
    limbLine(0.52f, railY, 0.52f, railY + 0.06f, style.prop, w)
    // foot bar at the -X end
    limbLine(-0.46f, railY, -0.46f, railY - 0.135f, style.prop, w)
    limbLine(-0.46f, railY - 0.135f, -0.38f, railY - 0.165f, style.prop, w)
    // carriage: slides right as it opens
    val cx = -0.16f + 0.24f * carriage
    limbLine(cx - 0.17f, railY - 0.035f, cx + 0.17f, railY - 0.035f, style.prop.copy(alpha = 0.9f), scale(0.035f))
    // shoulder rests
    limbLine(cx + 0.05f, railY - 0.035f, cx + 0.05f, railY - 0.085f, style.prop, scale(0.02f))
    // springs: zigzag between the front frame and the carriage nose
    val springPath = Path()
    val sx0 = -0.5f
    val sx1 = cx - 0.17f
    springPath.moveTo(mapX(sx0), mapY(railY - 0.01f))
    val n = 7
    for (i in 1..n) {
        val t = i / n.toFloat()
        val x = sx0 + (sx1 - sx0) * t
        val dy = if (i % 2 == 0) -0.018f else 0.018f
        springPath.lineTo(mapX(x), mapY(railY - 0.01f + dy))
    }
    drawPath(springPath, style.prop.copy(alpha = 0.7f), style = Stroke(scale(0.012f), cap = StrokeCap.Round))
}

private fun DrawScope.limbLine(x0: Float, y0: Float, x1: Float, y1: Float, c: Color, w: Float) {
    drawLine(c, Offset(mapX(x0), mapY(y0)), Offset(mapX(x1), mapY(y1)), strokeWidth = w, cap = StrokeCap.Round)
}

// ---------------------------------------------------------------------- spin bike

private fun DrawScope.drawSpinAnim(anim: SpinAnim, timeMs: Float, style: RenderStyle) {
    drawGround(style)

    val crank = Joint(0.05f, 0.27f)
    val crankR = 0.085f
    val seat = Joint(-0.135f, -0.015f)
    val bars = Joint(0.235f, -0.075f)

    // frame
    val w = scale(0.024f)
    limbLine(crank.x, crank.y, seat.x, seat.y + 0.02f, style.prop, w)          // seat tube
    limbLine(seat.x, seat.y + 0.02f, seat.x - 0.05f, seat.y + 0.02f, style.prop, w) // saddle... (drawn below)
    limbLine(crank.x, crank.y, bars.x - 0.015f, bars.y + 0.05f, style.prop, w) // down/head tube
    limbLine(bars.x - 0.015f, bars.y + 0.05f, bars.x, bars.y, style.prop, w)
    limbLine(bars.x - 0.06f, bars.y, bars.x + 0.05f, bars.y, style.prop, scale(0.03f)) // handlebar
    // saddle
    limbLine(seat.x - 0.055f, seat.y, seat.x + 0.045f, seat.y, style.prop, scale(0.032f))
    // rear flywheel (GR7: rear drum) + stabilizers
    drawCircle(style.prop.copy(alpha = 0.6f), radius = scale(0.115f), center = pt(Joint(-0.235f, 0.335f)), style = Stroke(w))
    limbLine(crank.x, crank.y, -0.235f, 0.335f, style.prop, w)
    limbLine(-0.36f, AnimationRegistry.GY + 0.05f, -0.11f, AnimationRegistry.GY + 0.05f, style.prop, w)
    limbLine(0.16f, AnimationRegistry.GY + 0.05f, 0.40f, AnimationRegistry.GY + 0.05f, style.prop, w)
    limbLine(-0.235f, 0.335f, -0.235f, AnimationRegistry.GY + 0.05f, style.prop, w)
    limbLine(0.28f, AnimationRegistry.GY + 0.05f, bars.x - 0.015f, bars.y + 0.05f, style.prop, w)

    // crank phase
    val phase = (timeMs % anim.durationMs) / anim.durationMs * 2f * PI.toFloat()
    val pedalNear = Joint(crank.x + crankR * cos(phase), crank.y + crankR * sin(phase))
    val pedalFar = Joint(crank.x - crankR * cos(phase), crank.y - crankR * sin(phase))
    limb(Joint(crank.x, crank.y), pedalNear, style.prop, scale(0.018f))
    limb(Joint(crank.x, crank.y), pedalFar, style.prop.copy(alpha = 0.5f), scale(0.016f))

    // standing blend (jumps oscillate)
    val standing = if (anim.jumpPeriodMs > 0) {
        val jp = (timeMs % anim.jumpPeriodMs) / anim.jumpPeriodMs
        // smooth square-ish wave: up for half the period
        ((1f - cos(jp * 2f * PI.toFloat())) / 2f)
    } else anim.standing

    val bob = 0.008f * sin(phase * 2f) * (1f - standing * 0.5f)
    val pelvis = Joint(
        seat.x + (0.13f * standing),
        seat.y - 0.02f - (0.115f * standing) + bob,
    )
    val lean = 38f + anim.extraLean + standing * 8f
    val leanR = Math.toRadians(lean.toDouble())
    val chest = Joint(
        pelvis.x + (Proportions.TORSO * sin(leanR)).toFloat(),
        pelvis.y - (Proportions.TORSO * cos(leanR)).toFloat(),
    )
    drawRiderOnMachine(chest, pelvis, bars, pedalNear, pedalFar, style, anim.muscle, headAlong = lean * 0.65f)
}

// ---------------------------------------------------------------------- elliptical

private fun DrawScope.drawEllipticalAnim(anim: EllipticalAnim, timeMs: Float, style: RenderStyle) {
    drawGround(style)

    val w = scale(0.024f)
    val center = Joint(-0.02f, 0.40f)
    val a = 0.155f // stride half-length
    val b = 0.035f // vertical excursion
    val column = Joint(0.30f, 0.02f)
    val pivot = Joint(0.285f, -0.10f)

    // base + column + static rail
    limbLine(-0.30f, AnimationRegistry.GY + 0.05f, 0.38f, AnimationRegistry.GY + 0.05f, style.prop, w)
    limbLine(column.x, AnimationRegistry.GY + 0.05f, column.x, column.y, style.prop, w)
    limbLine(column.x, column.y, pivot.x, pivot.y, style.prop, w)
    limbLine(0.20f, 0.06f, 0.30f, 0.045f, style.prop, scale(0.028f)) // static handles

    val dir = if (anim.reverse) -1f else 1f
    val phase = (timeMs % anim.durationMs) / anim.durationMs * 2f * PI.toFloat() * dir

    fun pedal(off: Float): Joint = Joint(
        center.x + a * cos(phase + off),
        center.y + b * sin(phase + off) * -1f + 0.02f,
    )

    val pedalNear = pedal(0f)
    val pedalFar = pedal(PI.toFloat())

    // pedal arms from a rear pivot
    val rear = Joint(-0.30f, 0.30f)
    limb(rear, pedalFar, style.prop.copy(alpha = 0.5f), scale(0.016f))
    limb(rear, pedalNear, style.prop, scale(0.018f))
    limbLine(rear.x, rear.y, rear.x, AnimationRegistry.GY + 0.05f, style.prop, w)
    // foot plates
    limb(Joint(pedalNear.x - 0.05f, pedalNear.y), Joint(pedalNear.x + 0.05f, pedalNear.y), style.prop, scale(0.024f))

    // moving handle levers swing anti-phase with the same-side pedal
    val handleSwing = 0.10f * cos(phase + PI.toFloat())
    val gripNear = Joint(pivot.x - 0.10f + handleSwing, pivot.y + 0.16f)
    val gripFar = Joint(pivot.x - 0.10f - handleSwing, pivot.y + 0.16f)
    limb(pivot, gripFar, style.prop.copy(alpha = 0.5f), scale(0.016f))
    limb(pivot, gripNear, style.prop, scale(0.018f))

    // rider
    val bob = 0.010f * sin(phase * 2f)
    val pelvis = Joint(0.015f + 0.008f * cos(phase), -0.028f + bob)
    val lean = 12f
    val leanR = Math.toRadians(lean.toDouble())
    val chest = Joint(
        pelvis.x + (Proportions.TORSO * sin(leanR)).toFloat(),
        pelvis.y - (Proportions.TORSO * cos(leanR)).toFloat(),
    )
    val handTargetNear = if (anim.armsDrive) gripNear else Joint(0.25f, 0.055f)
    val handTargetFar = if (anim.armsDrive) gripFar else Joint(0.25f, 0.055f)
    drawRiderOnMachine(chest, pelvis, null, pedalNear, pedalFar, style, anim.muscle, headAlong = 8f,
        handNear = handTargetNear, handFar = handTargetFar)
}

/** Shared rider: IK legs onto pedals, IK arms onto hand targets, torso + head. */
private fun DrawScope.drawRiderOnMachine(
    chest: Joint,
    pelvis: Joint,
    bars: Joint?,
    pedalNear: Joint,
    pedalFar: Joint,
    style: RenderStyle,
    muscle: MuscleGroup,
    headAlong: Float,
    handNear: Joint? = null,
    handFar: Joint? = null,
) {
    val w = scale(0.042f)
    val wFar = scale(0.036f)

    val kneeNear = Rig.ik(pelvis, pedalNear, Proportions.THIGH, Proportions.SHANK, -1)
    val kneeFar = Rig.ik(pelvis, pedalFar, Proportions.THIGH, Proportions.SHANK, -1)
    val hNear = handNear ?: bars ?: chest
    val hFar = handFar ?: bars ?: chest
    val elbowNear = Rig.ik(chest, hNear, Proportions.UPPER_ARM, Proportions.FOREARM, +1)
    val elbowFar = Rig.ik(chest, hFar, Proportions.UPPER_ARM, Proportions.FOREARM, +1)

    val headR = Math.toRadians(headAlong.toDouble())
    val neckTop = Joint(
        chest.x + (Proportions.NECK * sin(headR)).toFloat(),
        chest.y - (Proportions.NECK * cos(headR)).toFloat(),
    )
    val headCenter = Joint(
        neckTop.x + (Proportions.HEAD_R * sin(headR)).toFloat(),
        neckTop.y - (Proportions.HEAD_R * cos(headR)).toFloat(),
    )

    val legColor = if (muscle == MuscleGroup.LEGS || muscle == MuscleGroup.GLUTES || muscle == MuscleGroup.FULL_BODY)
        style.highlight else style.body
    val armColor = if (muscle == MuscleGroup.ARMS || muscle == MuscleGroup.FULL_BODY) style.highlight else style.body

    // far side
    limb(pelvis, kneeFar, legColor.copy(alpha = 0.38f), wFar)
    limb(kneeFar, pedalFar, legColor.copy(alpha = 0.38f), wFar)
    limb(chest, elbowFar, armColor.copy(alpha = 0.38f), wFar)
    limb(elbowFar, hFar, armColor.copy(alpha = 0.38f), wFar)
    // torso + head
    limb(pelvis, chest, if (muscle == MuscleGroup.CORE) style.highlight else style.body, scale(0.052f))
    drawCircle(style.body, radius = scale(Proportions.HEAD_R * 0.92f), center = pt(headCenter))
    // near side
    limb(pelvis, kneeNear, legColor, w)
    limb(kneeNear, pedalNear, legColor, w)
    limb(chest, elbowNear, armColor, w)
    limb(elbowNear, hNear, armColor, w)
}
