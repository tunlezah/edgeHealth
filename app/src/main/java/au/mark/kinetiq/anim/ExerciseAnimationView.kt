package au.mark.kinetiq.anim

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Procedural exercise animation player.
 *
 * Rendering quality bar: tapered capsule limbs with a line-weight hierarchy (torso heaviest,
 * distal segments lightest), a curved two-segment spine, near/far limb depth via a blended
 * darker shade + smaller width (never bare alpha, which washes out on light themes), feet and
 * hand shapes, a soft radial contact shadow that tracks foot spread and jump height, a subtle
 * breathing overlay so holds never freeze, per-segment easing driven by frame time, and a
 * working-muscle tint.
 *
 * Per frame: one pose solve, one rig solve and ~13 filled paths. The motion-path arc — declared by
 * 25 of the 50 registry animations — needs a further 29 pose and rig solves, but it is a pure
 * function of the animation and the canvas size, so it is solved once in `remember` rather than on
 * every frame.
 */
@Composable
fun ExerciseAnimationView(
    animationId: String?,
    modifier: Modifier = Modifier,
    contentDesc: String = "Exercise animation",
    paused: Boolean = false,
) {
    val anim = animationId?.let { AnimationRegistry.byId[it] } ?: return
    // Re-keyed on the animation so a recycled LazyColumn row starts at its authored keyframe 0
    // instead of inheriting the previous exercise's phase. Not keyed on `paused` — unpausing must
    // continue the loop, not restart it.
    var timeMs by remember(anim.id) { mutableFloatStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

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
    val surface = MaterialTheme.colorScheme.surface
    val farColor = lerp(bodyColor, surface, 0.42f)
    val highlight = MaterialTheme.colorScheme.tertiary
    val farHighlight = lerp(highlight, surface, 0.42f)
    val propColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val groundColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    val pathColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f)
    val shadowColor = MaterialTheme.colorScheme.onSurface

    // Keyed on canvas size as well as animation id: the points are in pixel space and scale with
    // the canvas, so a size change must re-solve. Re-keying on id also covers a LazyColumn
    // recycling a row into a different exercise.
    val motionPath: Path? = remember(anim.id, canvasSize) {
        val keyframe = anim as? KeyframeAnim ?: return@remember null
        val points = motionPathPoints(keyframe, canvasSize.width.toFloat(), canvasSize.height.toFloat())
        if (points.isEmpty()) null
        else Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1..points.lastIndex) lineTo(points[i].x, points[i].y)
        }
    }

    Canvas(
        modifier = modifier
            .testTag("anim:${anim.id}")
            .onSizeChanged { canvasSize = it }
            .semantics { contentDescription = contentDesc },
    ) {
        val style = RenderStyle(bodyColor, farColor, highlight, farHighlight, propColor, groundColor, pathColor, shadowColor)
        when (anim) {
            is KeyframeAnim -> drawKeyframeAnim(anim, timeMs, style, motionPath)
            is SpinAnim -> drawSpinAnim(anim, timeMs, style)
            is EllipticalAnim -> drawEllipticalAnim(anim, timeMs, style)
        }
    }
}

data class RenderStyle(
    val body: Color,
    val far: Color,
    val highlight: Color,
    val farHighlight: Color,
    val prop: Color,
    val ground: Color,
    val path: Color,
    val shadow: Color,
)

// Author-space viewport mapped into the canvas.
private const val VIEW_LEFT = -0.72f
private const val VIEW_RIGHT = 0.72f
private const val VIEW_TOP = -0.66f
private const val VIEW_BOTTOM = 0.56f

/** Drawn surface lines (author space). Contact joints are authored at [AnimationRegistry.GY]. */
private val GROUND_Y = AnimationRegistry.GY + 0.032f
private val MAT_Y = AnimationRegistry.GY + 0.030f

/**
 * The motion-path arc in canvas pixels.
 *
 * Pure in (anim, width, height) — never in time, which is the whole point: this used to be solved
 * inside the draw lambda, so 29 pose solves and 29 full rig solves ran on every frame to redraw an
 * identical polyline. 25 of the 50 registry animations declare a path joint. Returning plain
 * [Offset]s keeps it testable without an Android runtime.
 */
internal fun motionPathPoints(anim: KeyframeAnim, width: Float, height: Float): List<Offset> {
    if (anim.pathJoint == PathJoint.NONE || width <= 0f || height <= 0f) return emptyList()
    fun mx(x: Float) = (x - VIEW_LEFT) / (VIEW_RIGHT - VIEW_LEFT) * width
    fun my(y: Float) = (y - VIEW_TOP) / (VIEW_BOTTOM - VIEW_TOP) * height
    return (0..28).map { i ->
        val s = Rig.solve(anim.poseAt(i / 28f), anim.facing)
        val j = when (anim.pathJoint) {
            PathJoint.WRIST -> s.near.wrist
            PathJoint.ANKLE -> s.near.ankle
            PathJoint.PELVIS -> s.pelvis
            PathJoint.HEAD -> s.headCenter
            PathJoint.NONE -> s.pelvis
        }
        Offset(mx(j.x), my(j.y))
    }
}

private fun DrawScope.mapX(x: Float): Float = (x - VIEW_LEFT) / (VIEW_RIGHT - VIEW_LEFT) * size.width
private fun DrawScope.mapY(y: Float): Float = (y - VIEW_TOP) / (VIEW_BOTTOM - VIEW_TOP) * size.height
private fun DrawScope.pt(j: Joint): Offset = Offset(mapX(j.x), mapY(j.y))
private fun DrawScope.scale(v: Float): Float = v / (VIEW_RIGHT - VIEW_LEFT) * size.width

// ---------------------------------------------------------------------- figure primitives

/** Segment radii (author units) — proximal -> distal taper and torso-heavy weight hierarchy. */
private object Widths {
    const val TORSO_PELVIS = 0.030f
    const val TORSO_CHEST = 0.024f
    const val NECK = 0.011f
    const val THIGH_HIP = 0.023f
    const val THIGH_KNEE = 0.018f
    const val SHANK_KNEE = 0.018f
    const val SHANK_ANKLE = 0.013f
    const val UARM_SHOULDER = 0.019f
    const val UARM_ELBOW = 0.015f
    const val FARM_ELBOW = 0.015f
    const val FARM_WRIST = 0.011f
    const val FOOT = 0.013f
    const val HAND = 0.016f
    const val FAR_SCALE = 0.85f
}

/** Filled tapered capsule from [a] (radius ra) to [b] (radius rb) with round caps. */
private fun DrawScope.capsule(a: Joint, b: Joint, ra: Float, rb: Float, color: Color) {
    val pa = pt(a); val pb = pt(b)
    val sra = scale(ra); val srb = scale(rb)
    val dx = pb.x - pa.x; val dy = pb.y - pa.y
    val len = hypot(dx, dy)
    if (len < 0.5f) {
        drawCircle(color, radius = maxOf(sra, srb), center = pa)
        return
    }
    val nx = -dy / len; val ny = dx / len
    val angDeg = (atan2(ny, nx) * 180.0 / PI).toFloat()
    val path = Path()
    path.moveTo(pa.x + nx * sra, pa.y + ny * sra)
    path.lineTo(pb.x + nx * srb, pb.y + ny * srb)
    path.arcTo(Rect(pb.x - srb, pb.y - srb, pb.x + srb, pb.y + srb), angDeg, -180f, false)
    path.lineTo(pa.x - nx * sra, pa.y - ny * sra)
    path.arcTo(Rect(pa.x - sra, pa.y - sra, pa.x + sra, pa.y + sra), angDeg + 180f, -180f, false)
    path.close()
    drawPath(path, color)
}

/** Curved tapered torso through pelvis -> midTorso -> chest (the line of action). */
private fun DrawScope.spineBlob(pelvis: Joint, mid: Joint, chest: Joint, rPelvis: Float, rChest: Float, color: Color) {
    // quad Bézier control so the curve passes through the mid joint
    val cx = 2f * mid.x - 0.5f * (pelvis.x + chest.x)
    val cy = 2f * mid.y - 0.5f * (pelvis.y + chest.y)
    val n = 8
    val pts = ArrayList<Offset>(n + 1)
    val radii = FloatArray(n + 1)
    for (i in 0..n) {
        val t = i / n.toFloat()
        val omt = 1f - t
        val x = omt * omt * pelvis.x + 2f * omt * t * cx + t * t * chest.x
        val y = omt * omt * pelvis.y + 2f * omt * t * cy + t * t * chest.y
        pts.add(Offset(mapX(x), mapY(y)))
        radii[i] = scale(rPelvis + (rChest - rPelvis) * t)
    }
    val path = Path()
    // left offsets forward, right offsets back
    for (i in 0..n) {
        val prev = pts[maxOf(i - 1, 0)]; val next = pts[minOf(i + 1, n)]
        val dx = next.x - prev.x; val dy = next.y - prev.y
        val len = maxOf(hypot(dx, dy), 0.001f)
        val nx = -dy / len; val ny = dx / len
        val o = Offset(pts[i].x + nx * radii[i], pts[i].y + ny * radii[i])
        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    for (i in n downTo 0) {
        val prev = pts[maxOf(i - 1, 0)]; val next = pts[minOf(i + 1, n)]
        val dx = next.x - prev.x; val dy = next.y - prev.y
        val len = maxOf(hypot(dx, dy), 0.001f)
        val nx = -dy / len; val ny = dx / len
        path.lineTo(pts[i].x - nx * radii[i], pts[i].y - ny * radii[i])
    }
    path.close()
    drawPath(path, color)
    drawCircle(color, radius = radii[0], center = pts[0])
    drawCircle(color, radius = radii[n], center = pts[n])
}

private fun DrawScope.drawSide(side: SkeletonSide, legColor: Color, armColor: Color, k: Float, hasFeet: Boolean) {
    capsule(side.hip, side.knee, Widths.THIGH_HIP * k, Widths.THIGH_KNEE * k, legColor)
    capsule(side.knee, side.ankle, Widths.SHANK_KNEE * k, Widths.SHANK_ANKLE * k, legColor)
    if (hasFeet) capsule(side.ankle, side.toe, Widths.FOOT * k, Widths.FOOT * 0.85f * k, legColor)
    capsule(side.shoulder, side.elbow, Widths.UARM_SHOULDER * k, Widths.UARM_ELBOW * k, armColor)
    capsule(side.elbow, side.wrist, Widths.FARM_ELBOW * k, Widths.FARM_WRIST * k, armColor)
    drawCircle(armColor, radius = scale(Widths.HAND * k), center = pt(side.wrist))
}

/** Soft contact shadow: tracks the support spread and fades/narrows as the figure leaves the ground. */
private fun DrawScope.drawShadow(sk: Skeleton, style: RenderStyle) {
    val supports = listOf(sk.near.ankle, sk.far.ankle, sk.near.toe, sk.far.toe, sk.near.wrist, sk.far.wrist)
        .filter { it.y > AnimationRegistry.GY - 0.28f }
    if (supports.isEmpty()) return
    val minY = supports.minOf { it.y }
    val lowest = supports.maxOf { it.y }
    // elevation 0 = grounded, 1 = fully airborne
    val elevation = ((AnimationRegistry.GY - lowest) / 0.12f).coerceIn(0f, 1f)
    val onGround = supports.filter { it.y > AnimationRegistry.GY - 0.10f }
    val xs = (onGround.ifEmpty { supports }).map { it.x }
    val cx = (xs.min() + xs.max()) / 2f
    val halfW = ((xs.max() - xs.min()) / 2f + 0.10f) * (1f - 0.35f * elevation)
    val alpha = 0.18f * (1f - 0.65f * elevation)
    if (alpha <= 0.01f) return
    val center = Offset(mapX(cx), mapY(GROUND_Y))
    val radius = scale(halfW)
    val brush = Brush.radialGradient(
        listOf(style.shadow.copy(alpha = alpha), style.shadow.copy(alpha = 0f)),
        center = center, radius = radius,
    )
    scale(scaleX = 1f, scaleY = 0.22f, pivot = center) {
        drawCircle(brush, radius = radius, center = center)
    }
}

private fun DrawScope.drawFigure(sk: Skeleton, style: RenderStyle, muscle: MuscleGroup, hasFeet: Boolean = true) {
    fun near(group: Boolean) = if (group) style.highlight else style.body
    fun far(group: Boolean) = if (group) style.farHighlight else style.far

    val legGroup = muscle == MuscleGroup.LEGS || muscle == MuscleGroup.GLUTES || muscle == MuscleGroup.FULL_BODY
    val armGroup = muscle == MuscleGroup.ARMS || muscle == MuscleGroup.SHOULDERS || muscle == MuscleGroup.FULL_BODY
    val torsoGroup = muscle == MuscleGroup.CORE || muscle == MuscleGroup.CHEST ||
        muscle == MuscleGroup.BACK || muscle == MuscleGroup.FULL_BODY

    // far side first (parallax silhouette)
    drawSide(sk.far, far(legGroup), far(armGroup), Widths.FAR_SCALE, hasFeet)
    // torso as a curved tapered blob, then neck + head
    spineBlob(sk.pelvis, sk.midTorso, sk.chest,
        Widths.TORSO_PELVIS, Widths.TORSO_CHEST, near(torsoGroup))
    capsule(sk.chest, sk.neckTop, Widths.NECK, Widths.NECK, style.body)
    drawCircle(style.body, radius = scale(Proportions.HEAD_R * 0.92f), center = pt(sk.headCenter))
    // near side
    drawSide(sk.near, near(legGroup), near(armGroup), 1f, hasFeet)
}

// ---------------------------------------------------------------------- surfaces & props

private fun DrawScope.drawGround(style: RenderStyle) {
    drawLine(
        style.ground,
        Offset(mapX(-0.62f), mapY(GROUND_Y)),
        Offset(mapX(0.62f), mapY(GROUND_Y)),
        strokeWidth = scale(0.012f),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawMat(style: RenderStyle) {
    drawLine(
        style.prop,
        Offset(mapX(-0.52f), mapY(MAT_Y)), Offset(mapX(0.52f), mapY(MAT_Y)),
        strokeWidth = scale(0.03f), cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawWall(style: RenderStyle) {
    drawLine(
        style.prop,
        Offset(mapX(-0.245f), mapY(-0.55f)), Offset(mapX(-0.245f), mapY(GROUND_Y)),
        strokeWidth = scale(0.03f), cap = StrokeCap.Round,
    )
}

/** Reformer: rail, sliding carriage (pose.prop 0..1), springs, foot bar, shoulder rests, riser. */
private fun DrawScope.drawReformer(style: RenderStyle, carriage: Float) {
    val railY = AnimationRegistry.GY + 0.02f
    val w = scale(0.024f)
    limbLine(-0.56f, railY, 0.56f, railY, style.prop, w)
    limbLine(-0.52f, railY, -0.52f, railY + 0.055f, style.prop, w)
    limbLine(0.52f, railY, 0.52f, railY + 0.055f, style.prop, w)
    // foot bar at the -X end
    limbLine(-0.46f, railY, -0.46f, railY - 0.135f, style.prop, w)
    limbLine(-0.46f, railY - 0.135f, -0.38f, railY - 0.165f, style.prop, w)
    // strap riser at the +X end
    limbLine(0.52f, railY, 0.52f, railY - 0.13f, style.prop, w)
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

/** Straps from the riser pulley to the given joints (drawn behind the figure). */
private fun DrawScope.drawStraps(style: RenderStyle, near: Joint, far: Joint) {
    val pulley = Joint(0.52f, AnimationRegistry.GY + 0.02f - 0.12f)
    limb(pulley, far, style.prop.copy(alpha = 0.35f), scale(0.008f))
    limb(pulley, near, style.prop.copy(alpha = 0.55f), scale(0.009f))
}

private fun DrawScope.limb(a: Joint, b: Joint, color: Color, width: Float) {
    drawLine(color, pt(a), pt(b), strokeWidth = width, cap = StrokeCap.Round)
}

private fun DrawScope.limbLine(x0: Float, y0: Float, x1: Float, y1: Float, c: Color, w: Float) {
    drawLine(c, Offset(mapX(x0), mapY(y0)), Offset(mapX(x1), mapY(y1)), strokeWidth = w, cap = StrokeCap.Round)
}

// ---------------------------------------------------------------------- keyframed

private fun DrawScope.drawKeyframeAnim(
    anim: KeyframeAnim,
    timeMs: Float,
    style: RenderStyle,
    motionPath: Path?,
) {
    val phase = (timeMs % anim.durationMs) / anim.durationMs
    var pose = anim.poseAt(phase)

    // Breathing overlay: continuous in real time, so holds never freeze and loops stay seamless.
    val breathe = sin(timeMs / 3600f * 2f * PI.toFloat())
    pose = pose.copy(spine = pose.spine + 0.9f * breathe, neck = pose.neck - 0.5f * breathe)

    val sk = Rig.solve(pose, anim.facing)

    drawShadow(sk, style)
    when (anim.prop) {
        Prop.MAT -> drawMat(style)
        Prop.WALL -> { drawGround(style); drawWall(style) }
        Prop.REFORMER -> drawReformer(style, pose.prop)
        Prop.REFORMER_LEG_STRAPS -> {
            drawReformer(style, pose.prop)
            drawStraps(style, sk.near.ankle, sk.far.ankle)
        }
        Prop.REFORMER_ARM_STRAPS -> {
            drawReformer(style, pose.prop)
            drawStraps(style, sk.near.wrist, sk.far.wrist)
        }
        else -> drawGround(style)
    }

    // Subtle motion-path arc for the key moving joint. Solved once per (animation, canvas size)
    // and passed in — it is a pure function of those two things, never of time.
    motionPath?.let {
        drawPath(it, style.path, style = Stroke(width = scale(0.012f), cap = StrokeCap.Round))
    }

    drawFigure(sk, style, anim.muscle)
}

// ---------------------------------------------------------------------- spin bike

private fun DrawScope.drawSpinAnim(anim: SpinAnim, timeMs: Float, style: RenderStyle) {
    drawGround(style)

    val baseY = GROUND_Y - 0.012f
    val crank = Joint(0.05f, 0.27f)
    val crankR = 0.085f
    val seat = Joint(-0.135f, -0.015f)
    val bars = Joint(0.235f, -0.075f)

    // frame
    val w = scale(0.024f)
    limbLine(crank.x, crank.y, seat.x, seat.y + 0.02f, style.prop, w)
    limbLine(crank.x, crank.y, bars.x - 0.015f, bars.y + 0.05f, style.prop, w)
    limbLine(bars.x - 0.015f, bars.y + 0.05f, bars.x, bars.y, style.prop, w)
    limbLine(bars.x - 0.06f, bars.y, bars.x + 0.05f, bars.y, style.prop, scale(0.03f))
    // saddle
    limbLine(seat.x - 0.055f, seat.y, seat.x + 0.045f, seat.y, style.prop, scale(0.032f))
    // rear flywheel (GR7: rear drum) + stabilizers
    drawCircle(style.prop.copy(alpha = 0.6f), radius = scale(0.115f), center = pt(Joint(-0.235f, 0.335f)), style = Stroke(w))
    limbLine(crank.x, crank.y, -0.235f, 0.335f, style.prop, w)
    limbLine(-0.36f, baseY, -0.11f, baseY, style.prop, w)
    limbLine(0.16f, baseY, 0.40f, baseY, style.prop, w)
    limbLine(-0.235f, 0.335f, -0.235f, baseY, style.prop, w)
    limbLine(0.28f, baseY, bars.x - 0.015f, bars.y + 0.05f, style.prop, w)

    // crank phase
    val phase = (timeMs % anim.durationMs) / anim.durationMs * 2f * PI.toFloat()
    val pedalNear = Joint(crank.x + crankR * cos(phase), crank.y + crankR * sin(phase))
    val pedalFar = Joint(crank.x - crankR * cos(phase), crank.y - crankR * sin(phase))
    limb(Joint(crank.x, crank.y), pedalNear, style.prop, scale(0.018f))
    limb(Joint(crank.x, crank.y), pedalFar, style.prop.copy(alpha = 0.5f), scale(0.016f))

    // standing blend (jumps oscillate)
    val standing = if (anim.jumpPeriodMs > 0) {
        val jp = (timeMs % anim.jumpPeriodMs) / anim.jumpPeriodMs
        ((1f - cos(jp * 2f * PI.toFloat())) / 2f)
    } else anim.standing

    val bob = 0.008f * sin(phase * 2f) * (1f - standing * 0.5f)
    val pelvis = Joint(
        seat.x + (0.13f * standing),
        seat.y - 0.02f - (0.115f * standing) + bob,
    )
    val lean = 38f + anim.extraLean + standing * 8f
    drawRiderOnMachine(pelvis, lean, bars, pedalNear, pedalFar, style, anim.muscle,
        crankPhase = phase, headAlong = lean * 0.65f)
}

// ---------------------------------------------------------------------- elliptical

private fun DrawScope.drawEllipticalAnim(anim: EllipticalAnim, timeMs: Float, style: RenderStyle) {
    drawGround(style)

    val baseY = GROUND_Y - 0.012f
    val w = scale(0.024f)
    val center = Joint(-0.02f, 0.40f)
    val a = 0.155f // stride half-length
    val b = 0.035f // vertical excursion
    val column = Joint(0.30f, 0.02f)
    val pivot = Joint(0.285f, -0.10f)

    // base + column + static rail
    limbLine(-0.30f, baseY, 0.38f, baseY, style.prop, w)
    limbLine(column.x, baseY, column.x, column.y, style.prop, w)
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
    limbLine(rear.x, rear.y, rear.x, baseY, style.prop, w)
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
    val handTargetNear = if (anim.armsDrive) gripNear else Joint(0.25f, 0.055f)
    val handTargetFar = if (anim.armsDrive) gripFar else Joint(0.25f, 0.055f)
    drawRiderOnMachine(pelvis, 12f, null, pedalNear, pedalFar, style, anim.muscle,
        crankPhase = phase, headAlong = 8f, handNear = handTargetNear, handFar = handTargetFar)
}

/**
 * Shared rider: IK legs onto pedals (with feet), IK arms onto hand targets, curved torso + head.
 */
private fun DrawScope.drawRiderOnMachine(
    pelvis: Joint,
    leanDeg: Float,
    bars: Joint?,
    pedalNear: Joint,
    pedalFar: Joint,
    style: RenderStyle,
    muscle: MuscleGroup,
    crankPhase: Float,
    headAlong: Float,
    handNear: Joint? = null,
    handFar: Joint? = null,
) {
    val leanR = Math.toRadians(leanDeg.toDouble())
    val chest = Joint(
        pelvis.x + (Proportions.TORSO * sin(leanR)).toFloat(),
        pelvis.y - (Proportions.TORSO * cos(leanR)).toFloat(),
    )
    // rounded-back mid point: pushed slightly behind the pelvis->chest chord
    val mx = (pelvis.x + chest.x) / 2f; val my = (pelvis.y + chest.y) / 2f
    val chordX = chest.x - pelvis.x; val chordY = chest.y - pelvis.y
    val chordLen = maxOf(hypot(chordX, chordY), 0.001f)
    val midTorso = Joint(mx - chordY / chordLen * 0.016f, my + chordX / chordLen * 0.016f)

    val farHip = Joint(pelvis.x + Proportions.FAR_HIP_DX, pelvis.y + Proportions.FAR_HIP_DY)
    val farShoulder = Joint(chest.x + Proportions.FAR_SHOULDER_DX, chest.y + Proportions.FAR_SHOULDER_DY)

    val kneeNear = Rig.ik(pelvis, pedalNear, Proportions.THIGH, Proportions.SHANK, -1)
    val kneeFar = Rig.ik(farHip, pedalFar, Proportions.THIGH, Proportions.SHANK, -1)
    val hNear = handNear ?: bars ?: chest
    val hFar = handFar ?: bars ?: chest
    val elbowNear = Rig.ik(chest, hNear, Proportions.UPPER_ARM, Proportions.FOREARM, +1)
    val elbowFar = Rig.ik(farShoulder, hFar, Proportions.UPPER_ARM, Proportions.FOREARM, +1)

    val headR = Math.toRadians(headAlong.toDouble())
    val neckTop = Joint(
        chest.x + (Proportions.NECK * sin(headR)).toFloat(),
        chest.y - (Proportions.NECK * cos(headR)).toFloat(),
    )
    val headCenter = Joint(
        neckTop.x + (Proportions.HEAD_R * sin(headR)).toFloat(),
        neckTop.y - (Proportions.HEAD_R * cos(headR)).toFloat(),
    )

    // feet on pedals with a subtle ankling pattern (toe drops through the downstroke)
    val ankling = 14f * sin(crankPhase - 3.5f)
    fun foot(pedal: Joint, phaseOffset: Float): Joint {
        val angle = Math.toRadians((90.0 + ankling * cos(phaseOffset.toDouble())))
        return Joint(
            pedal.x + (Proportions.FOOT * sin(angle)).toFloat(),
            pedal.y + (Proportions.FOOT * cos(angle)).toFloat(),
        )
    }
    val toeNear = foot(pedalNear, 0f)
    val toeFar = foot(pedalFar, PI.toFloat())

    val sk = Skeleton(
        pelvis = pelvis, midTorso = midTorso, chest = chest, neckTop = neckTop, headCenter = headCenter,
        near = SkeletonSide(chest, elbowNear, hNear, pelvis, kneeNear, pedalNear, toeNear),
        far = SkeletonSide(farShoulder, elbowFar, hFar, farHip, kneeFar, pedalFar, toeFar),
    )
    drawFigure(sk, style, muscle)
}
