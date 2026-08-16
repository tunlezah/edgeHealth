package au.mark.kinetiq.anim

import kotlin.math.cos

/**
 * Every exercise animation in the app, keyed by animationId.
 *
 * Authoring space: Y grows down, ankle-level ground contact at y = [GY]; a standing figure's
 * pelvis is at y ~ 0. Joints that touch the floor/mat (ankles, planted wrists, kneeling knees)
 * are authored AT GY; a body lying on the mat has its joint line at [SUPINE_Y]; a body lying on
 * the reformer carriage at [CARRIAGE_LIE]. Every contact below was solved numerically against
 * the rig FK (see the geometry unit tests), so feet land on floors, hands land on bars and
 * nothing pokes through a surface.
 *
 * Timing: strength moves use asymmetric tempo — the eccentric (lowering) phase is slower than
 * the concentric, with a short dwell at end range (duplicated keyframes), which is how reps are
 * actually performed; ballistic moves use ACCEL/DECEL segments for flight. Reformer moves are
 * closer to symmetric by design (springs load the return).
 *
 * Spin and elliptical animations are procedural ([SpinAnim]/[EllipticalAnim]): legs are IK-solved
 * onto the moving pedals at render time, so cadence is exact and loops are perfectly smooth.
 */
object AnimationRegistry {

    const val GY = 0.46f
    /** Joint height of a body lying flat on the mat. */
    const val SUPINE_Y = 0.45f
    /** Joint height of a body lying on the reformer carriage. */
    const val CARRIAGE_LIE = 0.40f

    private fun cosd(deg: Float) = cos(Math.toRadians(deg.toDouble())).toFloat()

    /** Pelvis height that puts the support foot's ankle on the ground line. */
    private fun grounded(thigh: Float, knee: Float): Float =
        GY - Proportions.THIGH * cosd(thigh) - Proportions.SHANK * cosd(thigh - knee)

    /** Reformer carriage center X for a given prop value (0 = home, 1 = fully out). */
    private fun carriageX(prop: Float): Float = -0.16f + 0.24f * prop

    // ------------------------------------------------------------------ FLOOR

    private val standing = Pose(pelvisY = grounded(0f, 0f), torso = 3f, neck = -2f,
        uArmL = 14f, uArmR = 14f, elbowL = 8f, elbowR = 8f)

    private val flSquat = KeyframeAnim(
        id = "fl_squat", durationMs = 3000, prop = Prop.NONE, muscle = MuscleGroup.LEGS,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, standing),                       // eccentric: 44% of the cycle
            Keyframe(0.44f, Pose(                          // bottom: hips back, flat feet, trunk counterlean
                pelvisX = -0.05f, pelvisY = grounded(78f, 96f), torso = 24f, spine = 8f, neck = -14f,
                uArmL = 52f, uArmR = 52f, elbowL = 22f, elbowR = 22f,
                thighL = 78f, kneeL = 96f, footL = 18f, thighR = 78f, kneeR = 96f, footR = 18f,
            )),
            Keyframe(0.55f, Pose(                          // bottom dwell
                pelvisX = -0.05f, pelvisY = grounded(78f, 96f), torso = 24f, spine = 8f, neck = -14f,
                uArmL = 52f, uArmR = 52f, elbowL = 22f, elbowR = 22f,
                thighL = 78f, kneeL = 96f, footL = 18f, thighR = 78f, kneeR = 96f, footR = 18f,
            )),
            Keyframe(0.92f, standing),                     // concentric faster than eccentric; short top rest
        ),
    )

    private val puTop = Pose(
        pelvisX = 0.029f, pelvisY = 0.268f, torso = 69.4f, neck = -57.4f,
        uArmL = -79.5f, uArmR = -79.5f, elbowL = 22.9f, elbowR = 22.9f,
        thighL = -69.4f, thighR = -69.4f, footL = 45.8f, footR = 45.8f,
    )
    private val puBot = Pose(
        pelvisX = 0.053f, pelvisY = 0.366f, torso = 82f, neck = -72f,
        uArmL = -155.4f, uArmR = -155.4f, elbowL = 124.6f, elbowR = 124.6f,
        thighL = -82f, thighR = -82f, footL = 58.4f, footR = 58.4f,
    )

    private val flPushup = KeyframeAnim(
        id = "fl_pushup", durationMs = 2600, prop = Prop.MAT, muscle = MuscleGroup.CHEST,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, puTop),
            Keyframe(0.42f, puBot),
            Keyframe(0.52f, puBot),   // bottom dwell
            Keyframe(0.90f, puTop),
        ),
    )

    private fun lungeBottom(front: Boolean, shift: Float): Pose =
        if (front) Pose(
            pelvisX = shift, pelvisY = 0.187f, torso = 6f, neck = -4f,
            thighR = 78f, kneeR = 83f, footR = 5f, thighL = -7.3f, kneeL = 83.9f, footL = 54.3f,
            uArmL = 22f, uArmR = -22f, elbowL = 15f, elbowR = 15f,
        ) else Pose( // mirrored legs: left leg lunges forward
            pelvisX = shift, pelvisY = 0.187f, torso = 6f, neck = -4f,
            thighL = 78f, kneeL = 83f, footL = 5f, thighR = -7.3f, kneeR = 83.9f, footR = 54.3f,
            uArmR = 22f, uArmL = -22f, elbowL = 15f, elbowR = 15f,
        )

    /** Forward lunge, alternating legs each rep. */
    private val flLunge = KeyframeAnim(
        id = "fl_lunge", durationMs = 4200, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, standing),
            Keyframe(0.20f, lungeBottom(front = true, shift = 0.03f)),
            Keyframe(0.30f, lungeBottom(front = true, shift = 0.03f)),
            Keyframe(0.46f, standing),
            Keyframe(0.70f, lungeBottom(front = false, shift = 0.03f)),
            Keyframe(0.80f, lungeBottom(front = false, shift = 0.03f)),
            Keyframe(0.96f, standing),
        ),
    )

    /** Reverse lunge: the working leg steps back instead (pelvis travels backward). */
    private val flReverseLunge = KeyframeAnim(
        id = "fl_reverse_lunge", durationMs = 4200, muscle = MuscleGroup.GLUTES,
        keyframes = listOf(
            Keyframe(0f, standing),
            Keyframe(0.20f, lungeBottom(front = true, shift = -0.08f)),
            Keyframe(0.30f, lungeBottom(front = true, shift = -0.08f)),
            Keyframe(0.46f, standing),
            Keyframe(0.70f, lungeBottom(front = false, shift = -0.08f)),
            Keyframe(0.80f, lungeBottom(front = false, shift = -0.08f)),
            Keyframe(0.96f, standing),
        ),
    )

    private val plankPose = Pose(
        pelvisX = -0.026f, pelvisY = 0.353f, torso = 80.4f, neck = -72.4f,
        uArmL = -80.4f, uArmR = -80.4f, elbowL = 90f, elbowR = 90f,
        thighL = -80.4f, thighR = -80.4f, footL = 56.8f, footR = 56.8f,
    )

    private val flPlank = KeyframeAnim(
        id = "fl_plank", durationMs = 3600, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, plankPose),
            Keyframe(0.5f, plankPose.copy(torso = 80.9f, thighL = -80.9f, thighR = -80.9f, pelvisY = 0.350f)),
        ),
    )

    private val spDn = Pose(
        pelvisX = -0.052f, pelvisY = 0.397f, torso = 72.5f, neck = -66.5f,
        uArmL = -72.5f, uArmR = 29.5f, elbowL = 90f, elbowR = 4f,
        thighL = -72.3f, kneeL = 22.8f, footL = 95.1f, thighR = -72.3f, kneeR = 22.8f, footR = 95.1f,
    )
    private val spUp = Pose(
        pelvisX = -0.046f, pelvisY = 0.358f, torso = 79.5f, neck = -73.5f,
        uArmL = -79.5f, uArmR = 22.5f, elbowL = 90f, elbowR = 4f,
        thighL = -77.6f, kneeL = 2.4f, footL = 80f, thighR = -77.6f, kneeR = 2.4f, footR = 80f,
    )

    private val flSidePlank = KeyframeAnim(
        id = "fl_side_plank", durationMs = 3800, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, spDn),
            Keyframe(0.42f, spUp),
            Keyframe(0.58f, spUp),   // top hold
            Keyframe(0.95f, spDn),
        ),
    )

    private val brDn = Pose(
        pelvisY = SUPINE_Y, torso = 93f, neck = -14f, uArmL = 177f, uArmR = 177f, elbowL = 2f, elbowR = 2f,
        thighL = -149.8f, kneeL = -131.2f, footL = -161f, thighR = -149.8f, kneeR = -131.2f, footR = -161f,
    )
    private val brUp = Pose(
        pelvisY = 0.335f, torso = 110.3f, neck = -24f, uArmL = 159.7f, uArmR = 159.7f, elbowL = 2f, elbowR = 2f,
        thighL = -114.9f, kneeL = -120.8f, footL = -186f, thighR = -114.9f, kneeR = -120.8f, footR = -186f,
    )

    private val flBridge = KeyframeAnim(
        id = "fl_bridge", durationMs = 3400, prop = Prop.MAT, muscle = MuscleGroup.GLUTES,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, brDn),
            Keyframe(0.32f, brUp),
            Keyframe(0.60f, brUp),   // top squeeze
            Keyframe(0.95f, brDn),
        ),
    )

    private val burCrouch = Pose(
        pelvisY = 0.30f, torso = 52f, spine = 24f, neck = -30f,
        thighL = 85f, kneeL = 132f, footL = 47f, thighR = 85f, kneeR = 132f, footR = 47f,
        uArmL = -53.9f, uArmR = -53.9f, elbowL = 2.7f, elbowR = 2.7f,
    )
    private val burPlank = puTop.copy(neck = -63.4f)
    private val burApex = Pose(
        pelvisY = grounded(0f, 0f) - 0.09f, torso = -4f, neck = 2f,
        uArmL = 155f, uArmR = 155f, elbowL = 4f, elbowR = 4f,
        thighL = 4f, kneeL = 12f, footL = -80f, thighR = 4f, kneeR = 12f, footR = -80f,
    )
    private val burLand = Pose(
        pelvisX = -0.02f, pelvisY = grounded(38f, 55f), torso = 16f, neck = -8f,
        thighL = 38f, kneeL = 55f, footL = 17f, thighR = 38f, kneeR = 55f, footR = 17f,
        uArmL = 30f, uArmR = 30f, elbowL = 15f, elbowR = 15f,
    )
    private val burTakeoff = Pose(
        pelvisY = grounded(8f, 14f), torso = 2f, neck = 0f,
        uArmL = 95f, uArmR = 95f, elbowL = 10f, elbowR = 10f,
        thighL = 8f, kneeL = 14f, footL = 6f, thighR = 8f, kneeR = 14f, footR = 6f,
    )

    // Mid jump-back/jump-in: hands planted, hips high, legs tucked — the arc a real burpee
    // travels, and it keeps the knees clear of the floor while the legs sweep under.
    private val burPike = Pose(
        pelvisX = 0.019f, pelvisY = 0.236f, torso = 76f, neck = -66f,
        uArmL = -86.1f, uArmR = -86.1f, elbowL = 22.9f, elbowR = 22.9f,
        thighL = 35f, kneeL = 115f, footL = 45f, thighR = 35f, kneeR = 115f, footR = 45f,
    )

    private val flBurpee = KeyframeAnim(
        id = "fl_burpee", durationMs = 3800, prop = Prop.MAT, muscle = MuscleGroup.FULL_BODY,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, standing),
            Keyframe(0.14f, burCrouch, Ease.LINEAR),   // hands down
            Keyframe(0.185f, burPike, Ease.LINEAR),    // feet snap back through a pike
            Keyframe(0.24f, burPlank),
            Keyframe(0.355f, burPlank, Ease.LINEAR),   // brief plank, feet snap in
            Keyframe(0.40f, burPike, Ease.LINEAR),
            Keyframe(0.45f, burCrouch),
            Keyframe(0.55f, burTakeoff, Ease.DECEL),   // drive up; rising flight decelerates
            Keyframe(0.63f, burApex, Ease.ACCEL),      // falling flight accelerates
            Keyframe(0.71f, burLand),                  // soft absorb
            Keyframe(0.86f, standing),
        ),
    )

    // Mountain-climber plank rides with slightly raised hips (pelvis a full femur length above
    // the floor) so the knee joint clears the ground as each leg sweeps under the body.
    private val mcBase = Pose(
        pelvisX = 0.015f, pelvisY = 0.22f, torso = 79.3f, neck = -63.4f,
        uArmL = -89.4f, uArmR = -89.4f, elbowL = 22.9f, elbowR = 22.9f,
        thighL = -61f, kneeL = 3.7f, footL = 41.1f, thighR = -61f, kneeR = 3.7f, footR = 41.1f,
    )

    private val flMountain = KeyframeAnim(
        id = "fl_mountain", durationMs = 1100, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, mcBase.copy(thighR = 100.5f, kneeR = 136.1f, footR = -40f)),
            Keyframe(0.5f, mcBase.copy(thighL = 100.5f, kneeL = 136.1f, footL = -40f)),
        ),
    )

    private val flJack = KeyframeAnim(
        id = "fl_jack", durationMs = 1000, facing = Facing.FRONT, muscle = MuscleGroup.FULL_BODY,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, Pose(pelvisY = grounded(0f, 0f), uArmL = 8f, uArmR = 8f)),
            Keyframe(0.5f, Pose(
                pelvisY = grounded(22f, 8f), uArmL = 165f, uArmR = 165f,
                thighL = 22f, thighR = 22f, kneeL = 8f, kneeR = 8f,
            )),
        ),
    )

    private val flHighKnees = KeyframeAnim(
        id = "fl_highknees", durationMs = 900, muscle = MuscleGroup.LEGS, pathJoint = PathJoint.ANKLE,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = grounded(-8f, 12f) - 0.02f, torso = 4f,
                thighL = -8f, kneeL = 12f, thighR = 95f, kneeR = 110f, footR = -45f,
                uArmL = 35f, elbowL = 70f, uArmR = -30f, elbowR = 70f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = grounded(-8f, 12f) - 0.02f, torso = 4f,
                thighR = -8f, kneeR = 12f, thighL = 95f, kneeL = 110f, footL = -45f,
                uArmR = 35f, elbowR = 70f, uArmL = -30f, elbowL = 70f,
            )),
        ),
    )

    private val flSkater = KeyframeAnim(
        id = "fl_skater", durationMs = 1600, facing = Facing.FRONT, muscle = MuscleGroup.LEGS,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisX = -0.16f, pelvisY = grounded(30f, 45f), torso = -10f, spine = -3f,
                thighL = 30f, kneeL = 45f, thighR = -18f, kneeR = 35f,
                uArmL = 40f, uArmR = 15f, elbowL = 40f, elbowR = 45f,
            )),
            Keyframe(0.5f, Pose(
                pelvisX = 0.16f, pelvisY = grounded(30f, 45f), torso = 10f, spine = 3f,
                thighR = 30f, kneeR = 45f, thighL = -18f, kneeL = 35f,
                uArmR = 40f, uArmL = 15f, elbowR = 40f, elbowL = 45f,
            )),
        ),
    )

    // Quadruped base: shoulders over wrists, hips over knees, shins flat on the mat.
    private val quadruped = Pose(
        pelvisY = 0.225f, torso = 77f, neck = -62f,
        uArmL = -77f, uArmR = -77f, elbowL = 2f, elbowR = 2f,
        thighL = 0f, kneeL = 90f, footL = -90f, thighR = 0f, kneeR = 90f, footR = -90f,
    )

    /** Bird dog, alternating diagonals with a hold at extension. */
    private val flBirdDog = KeyframeAnim(
        id = "fl_birddog", durationMs = 4400, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, quadruped),
            Keyframe(0.18f, quadruped.copy(uArmR = 13f, thighL = -92f, kneeL = -2f, footL = -100f)),
            Keyframe(0.32f, quadruped.copy(uArmR = 13f, thighL = -92f, kneeL = -2f, footL = -100f)),
            Keyframe(0.46f, quadruped),
            Keyframe(0.64f, quadruped.copy(uArmL = 13f, thighR = -92f, kneeR = -2f, footR = -100f)),
            Keyframe(0.78f, quadruped.copy(uArmL = 13f, thighR = -92f, kneeR = -2f, footR = -100f)),
            Keyframe(0.94f, quadruped),
        ),
    )

    // Supine base: lying on the back, head toward +X.
    private val supineTabletop = Pose(
        pelvisY = SUPINE_Y, torso = 93f, neck = -14f,
        uArmL = 87f, uArmR = 87f, elbowL = 4f, elbowR = 4f,
        thighL = -178f, kneeL = -88f, footL = -115f, thighR = -178f, kneeR = -88f, footR = -115f,
    )

    /** Dead bug, alternating opposite arm/leg. */
    private val flDeadBug = KeyframeAnim(
        id = "fl_deadbug", durationMs = 3800, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, supineTabletop),
            Keyframe(0.20f, supineTabletop.copy(thighR = -110f, kneeR = -4f, footR = -100f, uArmL = 177f, elbowL = 2f)),
            Keyframe(0.32f, supineTabletop.copy(thighR = -110f, kneeR = -4f, footR = -100f, uArmL = 177f, elbowL = 2f)),
            Keyframe(0.48f, supineTabletop),
            Keyframe(0.68f, supineTabletop.copy(thighL = -110f, kneeL = -4f, footL = -100f, uArmR = 177f, elbowR = 2f)),
            Keyframe(0.80f, supineTabletop.copy(thighL = -110f, kneeL = -4f, footL = -100f, uArmR = 177f, elbowR = 2f)),
            Keyframe(0.96f, supineTabletop),
        ),
    )

    // Prone base (head toward -X).
    private val smFlat = Pose(
        pelvisY = SUPINE_Y, torso = -88f, spine = -6f, neck = 6f,
        uArmL = 8f, uArmR = 8f, elbowL = 2f, elbowR = 2f,
        thighL = 90f, kneeL = -2f, footL = -65f, thighR = 90f, kneeR = -2f, footR = -65f,
    )
    private val smLift = Pose(
        pelvisY = SUPINE_Y, torso = -80f, spine = -20f, neck = 14f,
        uArmL = 4f, uArmR = 4f, elbowL = 2f, elbowR = 2f,
        thighL = 99f, kneeL = -2f, footL = -65f, thighR = 99f, kneeR = -2f, footR = -65f,
    )

    /** Superman: arms overhead, chest + legs lift together. */
    private val flSuperman = KeyframeAnim(
        id = "fl_superman", durationMs = 3200, prop = Prop.MAT, muscle = MuscleGroup.BACK,
        keyframes = listOf(
            Keyframe(0f, smFlat),
            Keyframe(0.35f, smLift),
            Keyframe(0.62f, smLift),   // hold at the top
            Keyframe(0.92f, smFlat),
        ),
    )

    /** Back extension hold: same prone lift but arms alongside the body. */
    private val bkExtension = KeyframeAnim(
        id = "bk_extension", durationMs = 3600, prop = Prop.MAT, muscle = MuscleGroup.BACK,
        keyframes = listOf(
            Keyframe(0f, smFlat.copy(uArmL = 186f, uArmR = 186f)),
            Keyframe(0.30f, smLift.copy(uArmL = 195f, uArmR = 195f)),
            Keyframe(0.68f, smLift.copy(uArmL = 195f, uArmR = 195f)),
            Keyframe(0.94f, smFlat.copy(uArmL = 186f, uArmR = 186f)),
        ),
    )

    private val sjCrouch = Pose(
        pelvisX = -0.04f, pelvisY = grounded(75f, 95f), torso = 28f, spine = 6f, neck = -16f,
        thighL = 75f, kneeL = 95f, footL = 20f, thighR = 75f, kneeR = 95f, footR = 20f,
        uArmL = 55f, uArmR = 55f, elbowL = 20f, elbowR = 20f,
    )

    private val flSquatJump = KeyframeAnim(
        id = "fl_squatjump", durationMs = 1600, muscle = MuscleGroup.LEGS, pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, sjCrouch),
            Keyframe(0.16f, Pose(                        // full extension at takeoff
                pelvisY = grounded(2f, 6f), torso = -2f,
                uArmL = 150f, uArmR = 150f, thighL = 2f, kneeL = 6f, thighR = 2f, kneeR = 6f,
                footL = -25f, footR = -25f,
            ), Ease.DECEL),
            Keyframe(0.28f, Pose(                        // apex
                pelvisY = grounded(0f, 0f) - 0.115f, torso = -3f,
                uArmL = 140f, uArmR = 140f, footL = -70f, footR = -70f,
                thighL = 6f, kneeL = 14f, thighR = 6f, kneeR = 14f,
            ), Ease.ACCEL),
            Keyframe(0.40f, Pose(                        // touchdown, pre-flexed
                pelvisY = grounded(18f, 26f), torso = 8f,
                thighL = 18f, kneeL = 26f, footL = 8f, thighR = 18f, kneeR = 26f, footR = 8f,
                uArmL = 30f, uArmR = 30f, elbowL = 12f, elbowR = 12f,
            )),
            Keyframe(0.52f, Pose(                        // absorb deep
                pelvisY = grounded(45f, 62f), torso = 16f,
                thighL = 45f, kneeL = 62f, footL = 17f, thighR = 45f, kneeR = 62f, footR = 17f,
                uArmL = 35f, uArmR = 35f, elbowL = 15f, elbowR = 15f,
            )),
            Keyframe(0.80f, sjCrouch),                   // settle back to the loaded crouch
        ),
    )

    private val wallSit = Pose(
        pelvisX = -0.215f, pelvisY = grounded(88f, 88f), torso = -2f, neck = 2f,
        thighL = 88f, kneeL = 88f, footL = 0f, thighR = 88f, kneeR = 88f, footR = 0f,
        uArmL = 8f, uArmR = 8f,
    )

    private val flWallSit = KeyframeAnim(
        id = "fl_wallsit", durationMs = 4000, prop = Prop.WALL, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, wallSit),
            Keyframe(0.5f, wallSit.copy(uArmL = 11f, uArmR = 11f, neck = 1f)),
        ),
    )

    // Inchworm: the feet stay planted (toes at x ~ -0.33) the whole loop while the hands
    // walk out from the fold to the plank hand position and back.
    private val iwFold = Pose(
        pelvisX = -0.558f, pelvisY = grounded(24f, 10f), torso = 112f, spine = 22f, neck = -15f,
        thighL = 24f, kneeL = 10f, footL = -14f, thighR = 24f, kneeR = 10f, footR = -14f,
        uArmL = -109.5f, uArmR = -109.5f, elbowL = 2.9f, elbowR = 2.9f,
    )
    private val iwShift = Pose( // weight shifts forward over the hands, shank passes vertical
        pelvisX = -0.44f, pelvisY = 0.003f, torso = 106f, spine = 26f, neck = -18f,
        thighL = 8f, kneeL = 8f, footL = 0f, thighR = 8f, kneeR = 8f, footR = 0f,
        uArmL = -124.6f, uArmR = -124.6f, elbowL = 2.7f, elbowR = 2.7f,
    )
    private val iwMid = Pose( // halfway through the hand-walk
        pelvisX = -0.155f, pelvisY = 0.056f, torso = 99.5f, spine = 30f, neck = -20f,
        thighL = -30f, kneeL = 6f, footL = 22f, thighR = -30f, kneeR = 6f, footR = 22f,
        uArmL = -151.3f, uArmR = -151.3f, elbowL = 28.8f, elbowR = 28.8f,
    )

    private val flInchworm = KeyframeAnim(
        id = "fl_inchworm", durationMs = 5000, prop = Prop.MAT, muscle = MuscleGroup.FULL_BODY,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, standing.copy(pelvisX = -0.47f)),
            Keyframe(0.12f, iwFold),
            Keyframe(0.19f, iwShift),
            Keyframe(0.28f, iwMid.copy(uArmL = -156.3f, uArmR = -146.3f)),
            Keyframe(0.40f, burPlank),
            Keyframe(0.50f, burPlank),
            Keyframe(0.62f, iwMid.copy(uArmL = -146.3f, uArmR = -156.3f)),
            Keyframe(0.71f, iwShift),
            Keyframe(0.78f, iwFold),
            Keyframe(0.92f, standing.copy(pelvisX = -0.47f)),
        ),
    )

    private val bcBase = Pose(
        pelvisY = 0.178f, torso = 87.1f, neck = -75.1f,
        uArmL = -87.1f, uArmR = -87.1f, elbowL = 2f, elbowR = 2f,
        thighL = 30f, kneeL = 110f, footL = 48.7f, thighR = 30f, kneeR = 110f, footR = 48.7f,
    )

    private val flBearCrawl = KeyframeAnim(
        id = "fl_bearcrawl", durationMs = 1400, prop = Prop.MAT, muscle = MuscleGroup.FULL_BODY,
        keyframes = listOf(
            Keyframe(0f, bcBase.copy(thighR = 45f, kneeR = 100f, thighL = 18f, kneeL = 115f,
                uArmL = -75f, uArmR = -99f)),
            Keyframe(0.5f, bcBase.copy(thighL = 45f, kneeL = 100f, thighR = 18f, kneeR = 115f,
                uArmR = -75f, uArmL = -99f)),
        ),
    )

    private val crFlat = Pose(
        pelvisY = SUPINE_Y, torso = 94f, neck = -30f,
        uArmL = 60f, elbowL = 118f, uArmR = 60f, elbowR = 118f,
        thighL = -149.8f, kneeL = -131.2f, footL = -161f, thighR = -149.8f, kneeR = -131.2f, footR = -161f,
    )

    private val flCrunch = KeyframeAnim(
        id = "fl_crunch", durationMs = 2800, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.HEAD,
        keyframes = listOf(
            Keyframe(0f, crFlat),
            Keyframe(0.36f, crFlat.copy(spine = -32f, neck = -42f)),  // chest curls via the spine
            Keyframe(0.48f, crFlat.copy(spine = -32f, neck = -42f)),
            Keyframe(0.92f, crFlat),                                   // slower eccentric
        ),
    )

    private val ruBase = Pose(
        pelvisY = 0.445f, torso = 42f, spine = 6f, neck = -18f,
        thighL = -125f, kneeL = -30f, footL = -110f, thighR = -125f, kneeR = -30f, footR = -110f,
        uArmL = -151f, uArmR = -135f, elbowL = 30f, elbowR = 30f,
    )

    private val flRussian = KeyframeAnim(
        id = "fl_russian", durationMs = 1900, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, ruBase),
            Keyframe(0.5f, ruBase.copy(torso = 45f, uArmL = -93f, elbowL = -7f, uArmR = -109f, elbowR = 21f)),
        ),
    )

    private val flMarch = KeyframeAnim(
        id = "fl_march", durationMs = 1300, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = grounded(0f, 4f), torso = 2f,
                thighL = 0f, kneeL = 4f, thighR = 62f, kneeR = 85f, footR = -42f,
                uArmL = 22f, elbowL = 45f, uArmR = -20f, elbowR = 45f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = grounded(0f, 4f), torso = 2f,
                thighR = 0f, kneeR = 4f, thighL = 62f, kneeL = 85f, footL = -42f,
                uArmR = 22f, elbowR = 45f, uArmL = -20f, elbowL = 45f,
            )),
        ),
    )

    private val flArmCircles = KeyframeAnim(
        id = "fl_armcircles", durationMs = 2000, facing = Facing.FRONT, muscle = MuscleGroup.SHOULDERS,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, Pose(pelvisY = grounded(0f, 0f), uArmL = 90f, uArmR = 90f)),
            Keyframe(0.25f, Pose(pelvisY = grounded(0f, 0f), uArmL = 140f, uArmR = 140f)),
            Keyframe(0.5f, Pose(pelvisY = grounded(0f, 0f), uArmL = 100f, uArmR = 100f, elbowL = 12f, elbowR = 12f)),
            Keyframe(0.75f, Pose(pelvisY = grounded(0f, 0f), uArmL = 55f, uArmR = 55f)),
        ),
    )

    // Cat-cow: real spinal articulation, wrists and knees pinned.
    private val ccCow = Pose(
        pelvisY = 0.222f, torso = 89.9f, spine = -24f, neck = -88f,
        uArmL = -70.8f, uArmR = -70.8f, elbowL = 14.4f, elbowR = 14.4f,
        thighL = 0f, kneeL = 90f, footL = -90f, thighR = 0f, kneeR = 90f, footR = -90f,
    )
    private val ccCat = Pose(
        pelvisY = 0.230f, torso = 63.3f, spine = 26f, neck = -2f,
        uArmL = -94.2f, uArmR = -94.2f, elbowL = 15.6f, elbowR = 15.6f,
        thighL = 0f, kneeL = 90f, footL = -90f, thighR = 0f, kneeR = 90f, footR = -90f,
    )

    private val flCatCow = KeyframeAnim(
        id = "fl_catcow", durationMs = 4200, prop = Prop.MAT, muscle = MuscleGroup.BACK,
        keyframes = listOf(
            Keyframe(0f, ccCow),
            Keyframe(0.08f, ccCow),
            Keyframe(0.50f, ccCat),
            Keyframe(0.58f, ccCat),
        ),
    )

    private val hsStart = Pose(
        pelvisX = 0.02f, pelvisY = 0.445f, torso = -24f, spine = -10f, neck = -8f,
        thighL = -89f, kneeL = -2f, footL = -178f, thighR = -90f, kneeR = -1f, footR = -178f,
        uArmL = -19.2f, uArmR = -16.2f, elbowL = -2.3f, elbowR = -2.3f,
    )
    private val hsDeep = Pose(
        pelvisX = 0.02f, pelvisY = 0.445f, torso = -38f, spine = -16f, neck = -8f,
        thighL = -89f, kneeL = -2f, footL = -178f, thighR = -90f, kneeR = -1f, footR = -178f,
        uArmL = 0f, uArmR = 3f, elbowL = -2.8f, elbowR = -2.8f,
    )

    /** Seated forward fold: slow reach, long hold — a static stretch, not a bounce. */
    private val flHamStretch = KeyframeAnim(
        id = "fl_hamstretch", durationMs = 5000, prop = Prop.MAT, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, hsStart),
            Keyframe(0.35f, hsDeep),
            Keyframe(0.72f, hsDeep),
            Keyframe(0.97f, hsStart),
        ),
    )

    private val qsHold = Pose(
        pelvisY = grounded(0f, 2f), torso = 8f, spine = 2f, neck = -6f,
        thighL = 0f, kneeL = 2f, footL = 0f, thighR = -8f, kneeR = 152f, footR = -30f,
        uArmL = 28f, elbowL = 10f, uArmR = -35.8f, elbowR = -2.9f,
    )

    private val flQuadStretch = KeyframeAnim(
        id = "fl_quadstretch", durationMs = 4200, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, qsHold),
            Keyframe(0.5f, qsHold.copy(torso = 9.5f, kneeR = 148f)),  // gentle pull, no bounce
        ),
    )

    private val childPose = Pose(
        pelvisX = -0.06f, pelvisY = 0.35f, torso = 88f, spine = 22f, neck = -20f,
        thighL = 62f, kneeL = 152f, footL = -90f, thighR = 62f, kneeR = 152f, footR = -90f,
        uArmL = -5.4f, uArmR = -5.4f, elbowL = -55.2f, elbowR = -55.2f,
    )

    private val flChild = KeyframeAnim(
        id = "fl_child", durationMs = 5000, prop = Prop.MAT, muscle = MuscleGroup.BACK,
        keyframes = listOf(
            Keyframe(0f, childPose),
            Keyframe(0.5f, childPose.copy(pelvisY = 0.345f, torso = 89f)),
        ),
    )

    // ------------------------------------------------------------------ REFORMER
    // Carriage rides along the rail with pose.prop (0 = home, 1 = fully out). The figure lies
    // head toward +X; the foot bar is at the -X end, strap risers at the +X end.

    private fun supineOnCarriage(prop: Float, thigh: Float, knee: Float, foot: Float) = Pose(
        pelvisX = carriageX(prop) + 0.03f, pelvisY = CARRIAGE_LIE, torso = 91f, neck = -12f,
        uArmL = 179f, uArmR = 179f,
        thighL = thigh, kneeL = knee, footL = foot, thighR = thigh, kneeR = knee, footR = foot,
        prop = prop,
    )

    private fun footworkAnim(id: String, foot: Float) = KeyframeAnim(
        id = id, durationMs = 3000, prop = Prop.REFORMER, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, supineOnCarriage(0f, -156.4f, -105f, foot)),
            Keyframe(0.42f, supineOnCarriage(0.75f, -106.7f, -14.7f, foot)),
            Keyframe(0.52f, supineOnCarriage(0.75f, -106.7f, -14.7f, foot)),
            Keyframe(0.94f, supineOnCarriage(0f, -156.4f, -105f, foot)),
        ),
    )

    // Supine foot convention: the figure lies face-up with the head toward +X, which is the
    // MIRRORED chirality of the standing facing-+X rig — anatomically neutral is foot ≈ ±180
    // (toes to the ceiling), dorsiflexion moves toward +90 and plantarflexion toward -90.
    // Small |foot| values point the toes INTO the carriage/floor.

    /** Footwork on heels: feet dorsiflexed on the bar, toes to the ceiling. */
    private val rfFootworkHeels = footworkAnim("rf_footwork_heels", foot = 165f)
    /** Footwork on toes: ankles in relevé, toes pressing the bar. */
    private val rfFootworkToes = footworkAnim("rf_footwork_toes", foot = -105f)

    private val rfHundred = KeyframeAnim(
        id = "rf_hundred", durationMs = 1300, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisX = carriageX(0.35f) + 0.03f, pelvisY = CARRIAGE_LIE, torso = 94f, spine = -24f, neck = -26f,
                thighL = -105f, kneeL = -4f, footL = -99f, thighR = -105f, kneeR = -4f, footR = -99f,
                uArmL = -146f, uArmR = -146f, elbowL = 4f, elbowR = 4f, prop = 0.35f,
            )),
            Keyframe(0.5f, Pose(
                pelvisX = carriageX(0.35f) + 0.03f, pelvisY = CARRIAGE_LIE, torso = 94f, spine = -24f, neck = -26f,
                thighL = -105f, kneeL = -4f, footL = -99f, thighR = -105f, kneeR = -4f, footR = -99f,
                uArmL = -158f, uArmR = -158f, elbowL = 4f, elbowR = 4f, prop = 0.35f,
            )),
        ),
    )

    private fun strapsLegs(prop: Float, thigh: Float, knee: Float, foot: Float) = Pose(
        pelvisX = carriageX(prop) + 0.03f, pelvisY = CARRIAGE_LIE, torso = 91f, neck = -12f,
        uArmL = 179f, uArmR = 179f,
        thighL = thigh, kneeL = knee, footL = foot, thighR = thigh, kneeR = knee, footR = foot,
        prop = prop,
    )

    private val rfLegCircles = KeyframeAnim(
        id = "rf_legcircles", durationMs = 3000, prop = Prop.REFORMER_LEG_STRAPS, muscle = MuscleGroup.LEGS,
        pathJoint = PathJoint.ANKLE,
        keyframes = listOf(
            Keyframe(0f, strapsLegs(0.30f, -128f, -4f, -100f)),
            Keyframe(0.25f, strapsLegs(0.42f, -112f, -5f, -100f)),
            Keyframe(0.5f, strapsLegs(0.30f, -98f, -6f, -100f)),
            Keyframe(0.75f, strapsLegs(0.18f, -112f, -5f, -100f)),
        ),
    )

    private val rfFrog = KeyframeAnim(
        id = "rf_frog", durationMs = 2800, prop = Prop.REFORMER_LEG_STRAPS, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, strapsLegs(0.15f, -135f, -108f, -163f)),
            Keyframe(0.42f, strapsLegs(0.60f, -118f, -4f, -101f)),
            Keyframe(0.52f, strapsLegs(0.60f, -118f, -4f, -101f)),
            Keyframe(0.94f, strapsLegs(0.15f, -135f, -108f, -163f)),
        ),
    )

    /** Feet in straps: legs lower toward the bar and lift with control. */
    private val rfStraps = KeyframeAnim(
        id = "rf_straps", durationMs = 3200, prop = Prop.REFORMER_LEG_STRAPS, muscle = MuscleGroup.GLUTES,
        keyframes = listOf(
            Keyframe(0f, strapsLegs(0.20f, -128f, -4f, -105f)),
            Keyframe(0.42f, strapsLegs(0.60f, -95f, -6f, -105f)),
            Keyframe(0.55f, strapsLegs(0.60f, -95f, -6f, -105f)),
            Keyframe(0.94f, strapsLegs(0.20f, -128f, -4f, -105f)),
        ),
    )

    private fun pullStraps(prop: Float, uArm: Float) = Pose(
        pelvisX = carriageX(prop) + 0.03f, pelvisY = CARRIAGE_LIE, torso = 91f, neck = -12f,
        uArmL = uArm, uArmR = uArm, elbowL = 4f, elbowR = 4f,
        thighL = -100f, kneeL = -8f, footL = -118f, thighR = -100f, kneeR = -8f, footR = -118f,
        prop = prop,
    )

    private val rfPullStraps = KeyframeAnim(
        id = "rf_pullstraps", durationMs = 2900, prop = Prop.REFORMER_ARM_STRAPS, muscle = MuscleGroup.ARMS,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, pullStraps(0.15f, 94f)),
            Keyframe(0.40f, pullStraps(0.50f, 174f)),
            Keyframe(0.52f, pullStraps(0.50f, 174f)),
            Keyframe(0.92f, pullStraps(0.15f, 94f)),
        ),
    )

    // Inverted-V facing the foot bar: hips are the solved apex of a chest->pelvis->ankle
    // two-bone chain (the prop is short relative to the figure, so the pelvis cannot be
    // hand-placed without folding the knees). Legs stay near-straight, feet flat on the
    // carriage with toes lifted toward the bar.
    private val elIn = Pose(
        pelvisX = -0.124f, pelvisY = -0.039f, torso = -94.9f, spine = -26f, neck = -38f,
        thighL = 2.3f, kneeL = -10f, footL = 139.7f, thighR = 2.3f, kneeR = -10f, footR = 139.7f,
        uArmL = 153.2f, uArmR = 153.2f, elbowL = -63.3f, elbowR = -63.3f, prop = 0.2f,
    )
    private val elOut = Pose(
        pelvisX = -0.093f, pelvisY = -0.026f, torso = -97.4f, spine = -26f, neck = -38f,
        thighL = 10.6f, kneeL = -10f, footL = 131.4f, thighR = 10.6f, kneeR = -10f, footR = 131.4f,
        uArmL = 154.5f, uArmR = 154.5f, elbowL = -78.6f, elbowR = -78.6f, prop = 0.6f,
    )

    /** Elephant: pike with hands on the foot bar, heels press the carriage back. */
    private val rfElephant = KeyframeAnim(
        id = "rf_elephant", durationMs = 3000, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, elIn),
            Keyframe(0.42f, elOut),
            Keyframe(0.52f, elOut),
            Keyframe(0.94f, elIn),
        ),
    )

    private fun kneeStretch(prop: Float, uArm: Float, elbow: Float): Pose {
        // Facing -X (head toward the bar): knee flexion is NEGATIVE so the shins trail
        // behind (+X) along the carriage, feet pointed toward the shoulder rests.
        return Pose(
            pelvisX = carriageX(prop), pelvisY = 0.171f, torso = -72f, spine = 22f, neck = -60f,
            thighL = 5.6f, kneeL = -84.4f, footL = -92f, thighR = 5.6f, kneeR = -84.4f, footR = -92f,
            uArmL = uArm, uArmR = uArm, elbowL = elbow, elbowR = elbow, prop = prop,
        )
    }

    /** Knee stretches: rounded-back kneeling, knees pump the carriage. Dynamic — no dwell. */
    private val rfKneeStretch = KeyframeAnim(
        id = "rf_kneestretch", durationMs = 1900, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, kneeStretch(0.12f, 44.8f, -5.2f)),
            Keyframe(0.45f, kneeStretch(0.50f, 27.6f, -2.7f)),
            Keyframe(0.95f, kneeStretch(0.12f, 44.8f, -5.2f)),
        ),
    )

    // Straight-line plank on toes, feet riding the carriage. footR/L on the "back" pose is
    // authored past -180 (not wrapped to +170.6) so interpolation takes the short way round
    // instead of spinning the foot through a full turn.
    private val lsFwd = Pose(
        pelvisX = -0.145f, pelvisY = 0.050f, torso = -86.1f, spine = -6f, neck = -18f,
        thighL = 38.7f, kneeL = -14f, footL = -177.3f, thighR = 38.7f, kneeR = -14f, footR = -177.3f,
        uArmL = 130.8f, uArmR = 130.8f, elbowL = -57.8f, elbowR = -57.8f, prop = 0.72f,
    )
    private val lsBack = Pose(
        pelvisX = -0.138f, pelvisY = 0.125f, torso = -69.9f, spine = -6f, neck = -18f,
        thighL = 50.8f, kneeL = -14f, footL = -189.4f, thighR = 50.8f, kneeR = -14f, footR = -189.4f,
        uArmL = 109.4f, uArmR = 109.4f, elbowL = -56.3f, elbowR = -56.3f, prop = 1f,
    )

    /** Long stretch: plank with hands on the bar, whole body glides with the carriage. */
    private val rfLongStretch = KeyframeAnim(
        id = "rf_longstretch", durationMs = 2800, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, lsFwd),
            Keyframe(0.42f, lsBack),
            Keyframe(0.52f, lsBack),
            Keyframe(0.94f, lsFwd),
        ),
    )

    // Up-stretch: inverted V on toes, hips the solved apex (same chain solve as the elephant).
    private val pkUp = Pose(
        pelvisX = -0.136f, pelvisY = -0.080f, torso = -109.4f, spine = -10f, neck = -32f,
        thighL = 6.1f, kneeL = -12f, footL = -140.5f, thighR = 6.1f, kneeR = -12f, footR = -140.5f,
        uArmL = 149.9f, uArmR = 149.9f, elbowL = -56.9f, elbowR = -56.9f, prop = 0.15f,
    )
    private val pkOut = Pose(
        pelvisX = -0.114f, pelvisY = -0.062f, torso = -111f, spine = -10f, neck = -32f,
        thighL = 14.2f, kneeL = -12f, footL = -148.6f, thighR = 14.2f, kneeR = -12f, footR = -148.6f,
        uArmL = 154.2f, uArmR = 154.2f, elbowL = -74.6f, elbowR = -74.6f, prop = 0.5f,
    )

    /** Up-stretch / pike. */
    private val rfPike = KeyframeAnim(
        id = "rf_pike", durationMs = 3200, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, pkUp),
            Keyframe(0.42f, pkOut),
            Keyframe(0.52f, pkOut),
            Keyframe(0.94f, pkUp),
        ),
    )

    private fun chestExp(prop: Float, torso: Float, uArm: Float): Pose {
        val kx = carriageX(prop) + 0.10f
        return Pose(
            pelvisX = kx + 0.035f, pelvisY = 0.174f, torso = torso, neck = -torso,
            uArmL = uArm, uArmR = uArm, elbowL = 4f, elbowR = 4f,
            thighL = -8.6f, kneeL = 81.4f, footL = -88f, thighR = -8.6f, kneeR = 81.4f, footR = -88f,
            prop = prop,
        )
    }

    private val rfChestExp = KeyframeAnim(
        id = "rf_chestexp", durationMs = 2800, prop = Prop.REFORMER_ARM_STRAPS, muscle = MuscleGroup.BACK,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, chestExp(0.15f, 2f, 33f)),
            Keyframe(0.40f, chestExp(0.32f, -2f, -36f)),
            Keyframe(0.54f, chestExp(0.32f, -2f, -36f)),   // hold the open chest
            Keyframe(0.94f, chestExp(0.15f, 2f, 33f)),
        ),
    )

    private fun rowing(prop: Float, torso: Float, uArm: Float, elbow: Float) = Pose(
        pelvisX = carriageX(0.3f) - 0.02f, pelvisY = CARRIAGE_LIE - 0.02f, torso = torso, neck = -torso + 4f,
        uArmL = uArm, uArmR = uArm, elbowL = elbow, elbowR = elbow,
        thighL = 109.9f, kneeL = 47.7f, footL = -12f, thighR = 109.9f, kneeR = 47.7f, footR = -12f,
        prop = prop,
    )

    /** Rowing back: seated facing the risers, pull the straps to the chest. */
    private val rfRowing = KeyframeAnim(
        id = "rf_rowing", durationMs = 3200, prop = Prop.REFORMER_ARM_STRAPS, muscle = MuscleGroup.BACK,
        keyframes = listOf(
            Keyframe(0f, rowing(0.30f, 14f, 41f, 6f)),
            Keyframe(0.40f, rowing(0.42f, -8f, 28f, 95f)),
            Keyframe(0.52f, rowing(0.42f, -8f, 28f, 95f)),
            Keyframe(0.94f, rowing(0.30f, 14f, 41f, 6f)),
        ),
    )

    private fun rfLungePose(prop: Float, pelX: Float, pelY: Float, thighF: Float, kneeF: Float, footF: Float) = Pose(
        pelvisX = pelX, pelvisY = pelY, torso = -14f, neck = 8f,
        thighR = thighF, kneeR = kneeF, footR = footF,      // front leg: knee over the ankle, foot flat toward -X
        thighL = 44.5f, kneeL = -45.5f, footL = -85f,       // rear shin along the carriage
        uArmL = -6f, uArmR = -6f, elbowL = 10f, elbowR = 10f, prop = prop,
    )

    /** Hip-flexor lunge: front foot at the bar-end platform, rear knee rides the carriage. */
    private val rfLunge = KeyframeAnim(
        id = "rf_lunge", durationMs = 3600, prop = Prop.REFORMER, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, rfLungePose(0.2f, -0.315f, 0.235f, -91.0f, -115.9f, 143f)),
            Keyframe(0.40f, rfLungePose(0.62f, -0.21f, 0.26f, -102.2f, -98.3f, 171.9f)),
            Keyframe(0.56f, rfLungePose(0.62f, -0.21f, 0.26f, -102.2f, -98.3f, 171.9f)),   // sink into the stretch
            Keyframe(0.94f, rfLungePose(0.2f, -0.315f, 0.235f, -91.0f, -115.9f, 143f)),
        ),
    )

    private val mmTall = Pose(
        pelvisX = carriageX(0.15f) + 0.02f, pelvisY = 0.20f, torso = -6f, spine = -6f, neck = -4f,
        thighL = -30f, kneeL = -120f, footL = -80f, thighR = -34f, kneeR = -118f, footR = -80f,
        uArmL = 52f, elbowL = 30f, uArmR = -130f, elbowR = 6f, prop = 0.15f,
    )
    private val mmReach = mmTall.copy(
        torso = -16f, spine = -16f, uArmR = -110f, uArmL = 88f, prop = 0.42f,
        pelvisX = carriageX(0.15f) + 0.02f,
    )

    /** Mermaid: kneel-seated side bend, reach arm sweeps overhead as the carriage glides. */
    private val rfMermaid = KeyframeAnim(
        id = "rf_mermaid", durationMs = 4000, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, mmTall),
            Keyframe(0.38f, mmReach),
            Keyframe(0.58f, mmReach),
            Keyframe(0.94f, mmTall),
        ),
    )

    /** Side splits, front view: feet slide apart and pull back together. */
    private val rfSideSplit = KeyframeAnim(
        id = "rf_sidesplit", durationMs = 3400, facing = Facing.FRONT, prop = Prop.NONE,
        muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = grounded(14f, 4f), thighL = 14f, thighR = 14f, kneeL = 4f, kneeR = 4f,
                uArmL = 88f, uArmR = 88f,
            )),
            Keyframe(0.42f, Pose(
                pelvisY = grounded(32f, 4f), thighL = 32f, thighR = 32f, kneeL = 4f, kneeR = 4f,
                uArmL = 88f, uArmR = 88f,
            )),
            Keyframe(0.55f, Pose(
                pelvisY = grounded(32f, 4f), thighL = 32f, thighR = 32f, kneeL = 4f, kneeR = 4f,
                uArmL = 88f, uArmR = 88f,
            )),
            Keyframe(0.94f, Pose(
                pelvisY = grounded(14f, 4f), thighL = 14f, thighR = 14f, kneeL = 4f, kneeR = 4f,
                uArmL = 88f, uArmR = 88f,
            )),
        ),
    )

    private val pcDn = Pose(
        pelvisX = carriageX(0.1f) + 0.03f, pelvisY = CARRIAGE_LIE, torso = 91f, neck = -12f,
        uArmL = 179f, uArmR = 179f,
        thighL = -151.6f, kneeL = -97.5f, footL = 175f, thighR = -151.6f, kneeR = -97.5f, footR = 175f,
        prop = 0.1f,
    )
    private val pcUp = Pose(
        pelvisX = carriageX(0.12f) + 0.03f, pelvisY = 0.315f, torso = 118.2f, spine = -16f, neck = -7f,
        uArmL = 152f, uArmR = 152f,
        thighL = -136.1f, kneeL = -99f, footL = 175f, thighR = -136.1f, kneeR = -99f, footR = 175f,
        prop = 0.12f,
    )

    /** Pelvic curl on the reformer: hips roll up, shoulders stay heavy, feet stay on the bar. */
    private val rfPelvicCurl = KeyframeAnim(
        id = "rf_pelviccurl", durationMs = 3600, prop = Prop.REFORMER, muscle = MuscleGroup.GLUTES,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, pcDn),
            Keyframe(0.36f, pcUp),
            Keyframe(0.60f, pcUp),
            Keyframe(0.94f, pcDn),
        ),
    )

    // ------------------------------------------------------------------ BACK (physio mat work)

    private val cuStart = Pose(
        pelvisY = SUPINE_Y, torso = 94f, neck = -16f,
        uArmL = 122.1f, elbowL = 101.4f, uArmR = 122.1f, elbowR = 101.4f,   // hands under the low back
        thighL = -149.8f, kneeL = -131.2f, footL = -161f, thighR = -90f, kneeR = -1f, footR = -171f,
    )

    /** McGill curl-up: one knee bent, tiny head/shoulder lift, spine stays neutral. */
    private val bkCurlUp = KeyframeAnim(
        id = "bk_curlup", durationMs = 3400, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.HEAD,
        keyframes = listOf(
            Keyframe(0f, cuStart),
            Keyframe(0.38f, cuStart.copy(spine = -14f, neck = -26f)),
            Keyframe(0.58f, cuStart.copy(spine = -14f, neck = -26f)),
            Keyframe(0.92f, cuStart),
        ),
    )

    private val sbDn = Pose(
        pelvisX = -0.03f, pelvisY = 0.408f, torso = 64.4f, neck = -58.4f,
        uArmL = -64.4f, uArmR = 35.6f, elbowL = 90f, elbowR = 6f,
        thighL = -78f, kneeL = 30f, footL = 60f, thighR = -78f, kneeR = 30f, footR = 60f,
    )
    private val sbUp = Pose(
        pelvisX = -0.035f, pelvisY = 0.36f, torso = 75.8f, neck = -69.8f,
        uArmL = -75.8f, uArmR = 26.2f, elbowL = 90f, elbowR = 6f,
        thighL = -65.6f, kneeL = 30f, footL = 60f, thighR = -65.6f, kneeR = 30f, footR = 60f,
    )

    /** Side bridge from the knees. */
    private val bkSideBridge = KeyframeAnim(
        id = "bk_sidebridge", durationMs = 3600, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, sbDn),
            Keyframe(0.40f, sbUp),
            Keyframe(0.58f, sbUp),
            Keyframe(0.94f, sbDn),
        ),
    )

    private val ptNeutral = Pose(
        pelvisY = SUPINE_Y, torso = 94f, neck = -14f, uArmL = 177f, uArmR = 177f,
        thighL = -149.8f, kneeL = -131.2f, footL = -161f, thighR = -149.8f, kneeR = -131.2f, footR = -161f,
    )

    /** Gentle posterior pelvic tilt: low back presses into the mat. */
    private val bkPelvicTilt = KeyframeAnim(
        id = "bk_pelvictilt", durationMs = 3000, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, ptNeutral),
            Keyframe(0.42f, ptNeutral.copy(pelvisY = 0.443f, torso = 98f, spine = -5f,
                thighL = -153f, kneeL = -134f, footL = -160f, thighR = -153f, kneeR = -134f, footR = -160f)),
            Keyframe(0.56f, ptNeutral.copy(pelvisY = 0.443f, torso = 98f, spine = -5f,
                thighL = -153f, kneeL = -134f, footL = -160f, thighR = -153f, kneeR = -134f, footR = -160f)),
            Keyframe(0.94f, ptNeutral),
        ),
    )

    private val prDn = Pose(
        pelvisX = 0.05f, pelvisY = SUPINE_Y, torso = -88f, spine = -4f, neck = 10f,
        uArmL = -57.7f, uArmR = -57.7f, elbowL = 156.8f, elbowR = 156.8f,
        thighL = 90f, kneeL = -2f, footL = -65f, thighR = 90f, kneeR = -2f, footR = -65f,
    )
    private val prUp = Pose(
        pelvisX = 0.05f, pelvisY = SUPINE_Y, torso = -64f, spine = -26f, neck = 18f,
        uArmL = -19.9f, uArmR = -19.9f, elbowL = 139.3f, elbowR = 139.3f,
        thighL = 90f, kneeL = -2f, footL = -65f, thighR = 90f, kneeR = -2f, footR = -65f,
    )

    /** McKenzie press-up: hands stay planted, chest arcs up, hips stay heavy on the mat. */
    private val bkPressUp = KeyframeAnim(
        id = "bk_pressup", durationMs = 4000, prop = Prop.MAT, muscle = MuscleGroup.BACK,
        pathJoint = PathJoint.HEAD,
        keyframes = listOf(
            Keyframe(0f, prDn),
            Keyframe(0.40f, prUp),
            Keyframe(0.62f, prUp),
            Keyframe(0.94f, prDn),
        ),
    )

    private val hingeTop = Pose(pelvisY = grounded(0f, 2f), torso = 3f, uArmL = 8f, uArmR = 8f,
        thighL = 0f, kneeL = 2f, thighR = 0f, kneeR = 2f)
    private val hingeDown = Pose( // hips travel back, flat back tips forward, soft knees
        pelvisX = -0.07f, pelvisY = grounded(18f, 22f), torso = 62f, neck = -30f,
        uArmL = -58f, uArmR = -58f, elbowL = 4f, elbowR = 4f,
        thighL = 18f, kneeL = 22f, thighR = 18f, kneeR = 22f,
    )

    private val bkHinge = KeyframeAnim(
        id = "bk_hinge", durationMs = 3400, muscle = MuscleGroup.GLUTES,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, hingeTop),
            Keyframe(0.42f, hingeDown),
            Keyframe(0.54f, hingeDown),
            Keyframe(0.94f, hingeTop),
        ),
    )

    private val clamBase = Pose(
        pelvisY = 0.415f, torso = 84f, neck = -30f,
        uArmL = 8f, elbowL = -6f, uArmR = 191f, elbowR = -4f,
        thighL = -80f, kneeL = 8f, footL = -92f, thighR = -80f, kneeR = 8f, footR = -92f,
    )

    /** Clamshell (side view proxy: the top leg opens while the pelvis stays stacked). */
    private val bkClam = KeyframeAnim(
        id = "bk_clam", durationMs = 2800, prop = Prop.MAT, muscle = MuscleGroup.GLUTES,
        pathJoint = PathJoint.ANKLE,
        keyframes = listOf(
            Keyframe(0f, clamBase),
            Keyframe(0.42f, clamBase.copy(kneeR = 45f)),
            Keyframe(0.55f, clamBase.copy(kneeR = 45f)),
            Keyframe(0.94f, clamBase),
        ),
    )

    // ------------------------------------------------------------------ SPIN (procedural)
    // Cadences follow the voice coaching; posture leans are degrees from vertical.

    private val spSeatedFlat = SpinAnim(id = "sp_seated_flat", cadenceRpm = 90, standing = 0f)
    private val spWarmup = SpinAnim(id = "sp_warmup", cadenceRpm = 84, standing = 0f, extraLean = -4f)
    private val spRecovery = SpinAnim(id = "sp_recovery", cadenceRpm = 76, standing = 0f, extraLean = -6f)
    private val spFastFlat = SpinAnim(id = "sp_fast_flat", cadenceRpm = 98, standing = 0f, extraLean = 6f)
    private val spSeatedClimb = SpinAnim(id = "sp_seated_climb", cadenceRpm = 68, standing = 0f, extraLean = 6f)
    private val spSeatedClimbHeavy = SpinAnim(id = "sp_seated_climb_heavy", cadenceRpm = 58, standing = 0f, extraLean = 8f)
    private val spStandingClimb = SpinAnim(id = "sp_standing_climb", cadenceRpm = 64, standing = 1f, extraLean = -3f, muscle = MuscleGroup.GLUTES)
    private val spStandingRun = SpinAnim(id = "sp_standing_run", cadenceRpm = 86, standing = 1f, extraLean = 4f)
    private val spJumps = SpinAnim(id = "sp_jumps", cadenceRpm = 84, standing = 0f, jumpPeriodMs = 4200, muscle = MuscleGroup.LEGS)
    private val spSprint = SpinAnim(id = "sp_sprint", cadenceRpm = 118, standing = 0f, extraLean = 8f, muscle = MuscleGroup.FULL_BODY)

    // ------------------------------------------------------------------ ELLIPTICAL (procedural)

    private val elForward = EllipticalAnim(id = "el_forward", strideRpm = 55, armsDrive = true)
    private val elWarmup = EllipticalAnim(id = "el_warmup", strideRpm = 50, armsDrive = true)
    private val elEasy = EllipticalAnim(id = "el_easy", strideRpm = 46, armsDrive = true)
    private val elForwardFast = EllipticalAnim(id = "el_forward_fast", strideRpm = 72, armsDrive = true, muscle = MuscleGroup.FULL_BODY)
    private val elReverse = EllipticalAnim(id = "el_reverse", strideRpm = 52, reverse = true, muscle = MuscleGroup.LEGS)
    private val elLegsOnly = EllipticalAnim(id = "el_legsonly", strideRpm = 55, armsDrive = false, muscle = MuscleGroup.LEGS)
    private val elArmsDrive = EllipticalAnim(id = "el_armsdrive", strideRpm = 58, armsDrive = true, muscle = MuscleGroup.ARMS)
    private val elHill = EllipticalAnim(id = "el_hill", strideRpm = 45, armsDrive = true, muscle = MuscleGroup.GLUTES)

    // ------------------------------------------------------------------ registry

    val all: List<ExerciseAnim> = listOf(
        flSquat, flPushup, flLunge, flReverseLunge, flPlank, flSidePlank, flBridge, flBurpee,
        flMountain, flJack, flHighKnees, flSkater, flBirdDog, flDeadBug, flSuperman, flSquatJump,
        flWallSit, flInchworm, flBearCrawl, flCrunch, flRussian, flMarch, flArmCircles,
        flCatCow, flHamStretch, flQuadStretch, flChild,
        rfFootworkHeels, rfFootworkToes, rfHundred, rfLegCircles, rfFrog, rfElephant,
        rfKneeStretch, rfLongStretch, rfPike, rfChestExp, rfRowing, rfLunge, rfMermaid,
        rfSideSplit, rfStraps, rfPullStraps, rfPelvicCurl,
        bkCurlUp, bkSideBridge, bkPelvicTilt, bkPressUp, bkHinge, bkClam, bkExtension,
        spSeatedFlat, spWarmup, spRecovery, spFastFlat, spSeatedClimb, spSeatedClimbHeavy,
        spStandingClimb, spStandingRun, spJumps, spSprint,
        elForward, elWarmup, elEasy, elForwardFast, elReverse, elLegsOnly, elArmsDrive, elHill,
    )

    val byId: Map<String, ExerciseAnim> = all.associateBy { it.id }
    val ids: Set<String> = byId.keys
}
