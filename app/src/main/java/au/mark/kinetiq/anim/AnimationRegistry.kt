package au.mark.kinetiq.anim

import kotlin.math.cos

/**
 * Every exercise animation in the app, keyed by animationId.
 *
 * Authoring space: Y grows down, ground line at y = [GY]; a standing figure's pelvis is at y = 0.
 * Poses were authored against the rig FK in [Rig]; ground contact is computed with the same
 * trigonometry the renderer uses ([grounded]) so feet land on the floor.
 *
 * Spin and elliptical animations are procedural ([SpinAnim]/[EllipticalAnim]): legs are IK-solved
 * onto the moving pedals at render time, so cadence is exact and loops are perfectly smooth.
 */
object AnimationRegistry {

    const val GY = 0.46f

    private fun cosd(deg: Float) = cos(Math.toRadians(deg.toDouble())).toFloat()

    /** Pelvis height that puts the support foot's ankle on the ground line. */
    private fun grounded(thigh: Float, knee: Float): Float =
        GY - Proportions.THIGH * cosd(thigh) - Proportions.SHANK * cosd(thigh - knee)

    // ------------------------------------------------------------------ FLOOR

    private val flSquat = KeyframeAnim(
        id = "fl_squat", durationMs = 2600, prop = Prop.NONE, muscle = MuscleGroup.LEGS,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, Pose(pelvisY = grounded(0f, 0f), torso = 5f, uArmL = 15f, uArmR = 15f, elbowL = 10f, elbowR = 10f)),
            Keyframe(0.5f, Pose(
                pelvisY = grounded(78f, 96f), pelvisX = -0.05f, torso = 32f,
                uArmL = 55f, uArmR = 55f, elbowL = 25f, elbowR = 25f,
                thighL = 78f, kneeL = 96f, thighR = 78f, kneeR = 96f,
            )),
        ),
    )

    private val flPushup = KeyframeAnim(
        id = "fl_pushup", durationMs = 2400, prop = Prop.MAT, muscle = MuscleGroup.CHEST,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = 0.27f, torso = 68f, neck = -55f,
                uArmL = -68f, uArmR = -68f, elbowL = 2f, elbowR = 2f,
                thighL = -68f, thighR = -68f, footL = -25f, footR = -25f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = 0.35f, torso = 76f, neck = -60f,
                uArmL = -121f, uArmR = -121f, elbowL = 92f, elbowR = 92f,
                thighL = -76f, thighR = -76f, footL = -25f, footR = -25f,
            )),
        ),
    )

    private val flLunge = KeyframeAnim(
        id = "fl_lunge", durationMs = 3000, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, Pose(pelvisY = grounded(0f, 0f), torso = 3f, uArmL = 12f, uArmR = 12f)),
            Keyframe(0.5f, Pose(
                pelvisY = grounded(55f, 88f) + 0.02f, torso = 8f,
                thighR = 55f, kneeR = 88f, footR = 0f,
                thighL = -35f, kneeL = 65f, footL = -45f,
                uArmL = 20f, uArmR = -20f, elbowL = 15f, elbowR = 15f,
            )),
        ),
    )

    private val flPlank = KeyframeAnim(
        id = "fl_plank", durationMs = 3200, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = 0.315f, torso = 74f, neck = -58f,
                uArmL = -100f, uArmR = -100f, elbowL = 95f, elbowR = 95f,
                thighL = -74f, thighR = -74f, footL = -25f, footR = -25f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = 0.308f, torso = 74.5f, neck = -56f,
                uArmL = -100f, uArmR = -100f, elbowL = 95f, elbowR = 95f,
                thighL = -74.5f, thighR = -74.5f, footL = -25f, footR = -25f,
            )),
        ),
    )

    private val flSidePlank = KeyframeAnim(
        id = "fl_side_plank", durationMs = 3400, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = 0.30f, torso = 66f, neck = -50f,
                uArmL = -110f, uArmR = 96f, elbowL = 100f, elbowR = 4f,
                thighL = -66f, thighR = -66f, footL = -20f, footR = -20f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = 0.286f, torso = 68f, neck = -50f,
                uArmL = -110f, uArmR = 100f, elbowL = 100f, elbowR = 4f,
                thighL = -68f, thighR = -68f, footL = -20f, footR = -20f,
            )),
        ),
    )

    private val flBridge = KeyframeAnim(
        id = "fl_bridge", durationMs = 2800, prop = Prop.MAT, muscle = MuscleGroup.GLUTES,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, Pose( // supine, knees bent, hips down
                pelvisY = 0.40f, torso = 95f, neck = -20f,
                uArmL = -35f, uArmR = -35f,
                thighL = -55f, kneeL = 95f, thighR = -55f, kneeR = 95f,
            )),
            Keyframe(0.5f, Pose( // hips lifted: shoulders-to-knees line
                pelvisY = 0.30f, torso = 108f, neck = -35f,
                uArmL = -55f, uArmR = -55f,
                thighL = -85f, kneeL = 62f, thighR = -85f, kneeR = 62f,
            )),
        ),
    )

    private val flBurpee = KeyframeAnim(
        id = "fl_burpee", durationMs = 3600, prop = Prop.MAT, muscle = MuscleGroup.FULL_BODY,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, Pose(pelvisY = grounded(0f, 0f), torso = 2f, uArmL = 8f, uArmR = 8f)), // stand
            Keyframe(0.2f, Pose( // crouch, hands to floor
                pelvisY = grounded(85f, 115f), torso = 55f,
                thighL = 85f, kneeL = 115f, thighR = 85f, kneeR = 115f,
                uArmL = -100f, uArmR = -100f, elbowL = 5f, elbowR = 5f, neck = -25f,
            )),
            Keyframe(0.42f, Pose( // plank
                pelvisY = 0.27f, torso = 70f, neck = -55f,
                uArmL = -70f, uArmR = -70f, elbowL = 2f, elbowR = 2f,
                thighL = -70f, thighR = -70f, footL = -25f, footR = -25f,
            )),
            Keyframe(0.62f, Pose( // feet jump back in
                pelvisY = grounded(85f, 115f), torso = 55f,
                thighL = 85f, kneeL = 115f, thighR = 85f, kneeR = 115f,
                uArmL = -100f, uArmR = -100f, elbowL = 5f, elbowR = 5f, neck = -25f,
            )),
            Keyframe(0.82f, Pose( // jump, arms overhead
                pelvisY = grounded(0f, 0f) - 0.09f, torso = -4f,
                uArmL = 172f, uArmR = 172f, footL = -30f, footR = -30f,
            )),
        ),
    )

    private val flMountain = KeyframeAnim(
        id = "fl_mountain", durationMs = 1100, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = 0.24f, torso = 62f, neck = -48f,
                uArmL = -62f, uArmR = -62f, elbowL = 2f, elbowR = 2f,
                thighL = -62f, kneeL = 4f, thighR = 28f, kneeR = 118f, footL = -25f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = 0.24f, torso = 62f, neck = -48f,
                uArmL = -62f, uArmR = -62f, elbowL = 2f, elbowR = 2f,
                thighR = -62f, kneeR = 4f, thighL = 28f, kneeL = 118f, footR = -25f,
            )),
        ),
    )

    private val flJack = KeyframeAnim(
        id = "fl_jack", durationMs = 1000, facing = Facing.FRONT, muscle = MuscleGroup.FULL_BODY,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, Pose(pelvisY = grounded(0f, 0f), uArmL = 8f, uArmR = 8f)),
            Keyframe(0.5f, Pose(
                pelvisY = grounded(22f, 8f) , uArmL = 165f, uArmR = 165f,
                thighL = 22f, thighR = 22f, kneeL = 8f, kneeR = 8f,
            )),
        ),
    )

    private val flHighKnees = KeyframeAnim(
        id = "fl_highknees", durationMs = 900, muscle = MuscleGroup.LEGS, pathJoint = PathJoint.ANKLE,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = grounded(-8f, 12f) - 0.02f, torso = 4f,
                thighL = -8f, kneeL = 12f, thighR = 95f, kneeR = 110f,
                uArmL = 35f, elbowL = 70f, uArmR = -30f, elbowR = 70f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = grounded(-8f, 12f) - 0.02f, torso = 4f,
                thighR = -8f, kneeR = 12f, thighL = 95f, kneeL = 110f,
                uArmR = 35f, elbowR = 70f, uArmL = -30f, elbowL = 70f,
            )),
        ),
    )

    private val flSkater = KeyframeAnim(
        id = "fl_skater", durationMs = 1600, facing = Facing.FRONT, muscle = MuscleGroup.LEGS,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisX = -0.16f, pelvisY = grounded(30f, 45f),
                thighL = 30f, kneeL = 45f, thighR = -18f, kneeR = 35f,
                uArmL = 40f, uArmR = 15f, elbowL = 40f, elbowR = 45f,
            )),
            Keyframe(0.5f, Pose(
                pelvisX = 0.16f, pelvisY = grounded(30f, 45f),
                thighR = 30f, kneeR = 45f, thighL = -18f, kneeL = 35f,
                uArmR = 40f, uArmL = 15f, elbowR = 40f, elbowL = 45f,
            )),
        ),
    )

    private val flBirdDog = KeyframeAnim(
        id = "fl_birddog", durationMs = 3200, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, Pose( // quadruped
                pelvisY = 0.20f, torso = 88f, neck = -70f,
                uArmL = -88f, uArmR = -88f, elbowL = 2f, elbowR = 2f,
                thighL = -20f, kneeL = 92f, thighR = -20f, kneeR = 92f,
            )),
            Keyframe(0.5f, Pose( // right arm + left leg extended
                pelvisY = 0.20f, torso = 88f, neck = -70f,
                uArmR = 88f, elbowR = 2f, uArmL = -88f, elbowL = 2f,
                thighL = -95f, kneeL = 2f, thighR = -20f, kneeR = 92f,
            )),
        ),
    )

    private val flDeadBug = KeyframeAnim(
        id = "fl_deadbug", durationMs = 3000, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, Pose( // supine, arms up, knees at 90
                pelvisY = 0.40f, torso = 95f, neck = -15f,
                uArmL = 95f, uArmR = 95f,
                thighL = -85f, kneeL = 90f, thighR = -85f, kneeR = 90f,
            )),
            Keyframe(0.5f, Pose( // opposite arm/leg lowered
                pelvisY = 0.40f, torso = 95f, neck = -15f,
                uArmL = 170f, uArmR = 95f,
                thighR = -100f, kneeR = 12f, thighL = -85f, kneeL = 90f,
            )),
        ),
    )

    private val flSuperman = KeyframeAnim(
        id = "fl_superman", durationMs = 3000, prop = Prop.MAT, muscle = MuscleGroup.BACK,
        keyframes = listOf(
            Keyframe(0f, Pose( // prone flat
                pelvisY = 0.42f, torso = -92f, neck = 10f,
                uArmL = 165f, uArmR = 165f, thighL = 88f, kneeL = 2f, thighR = 88f, kneeR = 2f,
            )),
            Keyframe(0.5f, Pose( // chest + legs lifted
                pelvisY = 0.42f, torso = -72f, neck = 18f,
                uArmL = 175f, uArmR = 175f, thighL = 70f, kneeL = 2f, thighR = 70f, kneeR = 2f,
            )),
        ),
    )

    private val flSquatJump = KeyframeAnim(
        id = "fl_squatjump", durationMs = 1500, muscle = MuscleGroup.LEGS, pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = grounded(75f, 95f), pelvisX = -0.04f, torso = 30f,
                thighL = 75f, kneeL = 95f, thighR = 75f, kneeR = 95f,
                uArmL = 50f, uArmR = 50f, elbowL = 20f, elbowR = 20f,
            )),
            Keyframe(0.45f, Pose( // airborne, extended
                pelvisY = grounded(0f, 0f) - 0.13f, torso = -3f,
                uArmL = -35f, uArmR = -35f, footL = -30f, footR = -30f,
            )),
            Keyframe(0.75f, Pose( // land soft
                pelvisY = grounded(45f, 60f), torso = 18f,
                thighL = 45f, kneeL = 60f, thighR = 45f, kneeR = 60f,
                uArmL = 25f, uArmR = 25f, elbowL = 15f, elbowR = 15f,
            )),
        ),
    )

    private val flWallSit = KeyframeAnim(
        id = "fl_wallsit", durationMs = 3600, prop = Prop.WALL, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisX = 0.10f, pelvisY = grounded(88f, 88f), torso = -2f,
                thighL = 88f, kneeL = 88f, thighR = 88f, kneeR = 88f,
                uArmL = 10f, uArmR = 10f,
            )),
            Keyframe(0.5f, Pose(
                pelvisX = 0.10f, pelvisY = grounded(88f, 88f), torso = -1f,
                thighL = 88f, kneeL = 88f, thighR = 88f, kneeR = 88f,
                uArmL = 12f, uArmR = 12f,
            )),
        ),
    )

    private val flInchworm = KeyframeAnim(
        id = "fl_inchworm", durationMs = 4200, prop = Prop.MAT, muscle = MuscleGroup.FULL_BODY,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, Pose(pelvisY = grounded(0f, 0f), torso = 3f, uArmL = 5f, uArmR = 5f)),
            Keyframe(0.25f, Pose( // fold forward, hands near floor
                pelvisY = grounded(28f, 6f), torso = 95f, neck = -30f,
                thighL = 28f, kneeL = 6f, thighR = 28f, kneeR = 6f,
                uArmL = -30f, uArmR = -30f,
            )),
            Keyframe(0.55f, Pose( // walked out to plank
                pelvisY = 0.26f, torso = 69f, neck = -55f,
                uArmL = -69f, uArmR = -69f, elbowL = 2f, elbowR = 2f,
                thighL = -69f, thighR = -69f, footL = -25f, footR = -25f,
            )),
            Keyframe(0.8f, Pose( // walk feet back to fold
                pelvisY = grounded(28f, 6f), torso = 95f, neck = -30f,
                thighL = 28f, kneeL = 6f, thighR = 28f, kneeR = 6f,
                uArmL = -30f, uArmR = -30f,
            )),
        ),
    )

    private val flBearCrawl = KeyframeAnim(
        id = "fl_bearcrawl", durationMs = 1400, prop = Prop.MAT, muscle = MuscleGroup.FULL_BODY,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = 0.22f, torso = 80f, neck = -62f,
                uArmL = -60f, uArmR = -95f, elbowL = 4f, elbowR = 4f,
                thighL = 5f, kneeL = 95f, thighR = -35f, kneeR = 80f, footR = -20f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = 0.22f, torso = 80f, neck = -62f,
                uArmR = -60f, uArmL = -95f, elbowR = 4f, elbowL = 4f,
                thighR = 5f, kneeR = 95f, thighL = -35f, kneeL = 80f, footL = -20f,
            )),
        ),
    )

    private val flCrunch = KeyframeAnim(
        id = "fl_crunch", durationMs = 2200, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.HEAD,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = 0.40f, torso = 95f, neck = -20f,
                uArmL = 120f, elbowL = 110f, uArmR = 120f, elbowR = 110f,
                thighL = -55f, kneeL = 95f, thighR = -55f, kneeR = 95f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = 0.40f, torso = 68f, neck = -35f,
                uArmL = 118f, elbowL = 112f, uArmR = 118f, elbowR = 112f,
                thighL = -55f, kneeL = 95f, thighR = -55f, kneeR = 95f,
            )),
        ),
    )

    private val flRussian = KeyframeAnim(
        id = "fl_russian", durationMs = 1800, prop = Prop.MAT, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, Pose( // V-sit, arms swung to one side
                pelvisY = 0.40f, torso = 45f, neck = -12f,
                uArmL = 60f, elbowL = 25f, uArmR = 40f, elbowR = 25f,
                thighL = -60f, kneeL = 70f, thighR = -60f, kneeR = 70f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = 0.40f, torso = 42f, neck = -12f,
                uArmL = 105f, elbowL = 25f, uArmR = 125f, elbowR = 25f,
                thighL = -60f, kneeL = 70f, thighR = -60f, kneeR = 70f,
            )),
        ),
    )

    private val flMarch = KeyframeAnim(
        id = "fl_march", durationMs = 1300, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = grounded(0f, 4f), torso = 2f,
                thighL = 0f, kneeL = 4f, thighR = 62f, kneeR = 85f,
                uArmL = 22f, elbowL = 45f, uArmR = -20f, elbowR = 45f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = grounded(0f, 4f), torso = 2f,
                thighR = 0f, kneeR = 4f, thighL = 62f, kneeL = 85f,
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

    private val flCatCow = KeyframeAnim(
        id = "fl_catcow", durationMs = 3600, prop = Prop.MAT, muscle = MuscleGroup.BACK,
        keyframes = listOf(
            Keyframe(0f, Pose( // cow: back extended, head up
                pelvisY = 0.205f, torso = 84f, neck = -95f,
                uArmL = -84f, uArmR = -84f, elbowL = 2f, elbowR = 2f,
                thighL = -20f, kneeL = 92f, thighR = -20f, kneeR = 92f,
            )),
            Keyframe(0.5f, Pose( // cat: back rounded, head tucked
                pelvisY = 0.185f, torso = 96f, neck = -30f,
                uArmL = -96f, uArmR = -96f, elbowL = 2f, elbowR = 2f,
                thighL = -14f, kneeL = 90f, thighR = -14f, kneeR = 90f,
            )),
        ),
    )

    private val flHamStretch = KeyframeAnim(
        id = "fl_hamstretch", durationMs = 4000, prop = Prop.MAT, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, Pose( // seated, reaching to straight leg
                pelvisY = 0.40f, torso = 42f, neck = -20f,
                uArmL = 55f, uArmR = 55f, elbowL = 8f, elbowR = 8f,
                thighL = -78f, kneeL = 4f, thighR = -50f, kneeR = 95f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = 0.40f, torso = 55f, neck = -25f,
                uArmL = 62f, uArmR = 62f, elbowL = 4f, elbowR = 4f,
                thighL = -78f, kneeL = 4f, thighR = -50f, kneeR = 95f,
            )),
        ),
    )

    private val flQuadStretch = KeyframeAnim(
        id = "fl_quadstretch", durationMs = 4000, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisY = grounded(0f, 2f), torso = 3f,
                thighL = 0f, kneeL = 2f, thighR = -18f, kneeR = 128f, footR = -15f,
                uArmR = -148f, elbowR = 45f, uArmL = 35f, elbowL = 5f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = grounded(0f, 2f), torso = 5f,
                thighL = 0f, kneeL = 2f, thighR = -22f, kneeR = 134f, footR = -15f,
                uArmR = -150f, elbowR = 42f, uArmL = 40f, elbowL = 5f,
            )),
        ),
    )

    private val flChild = KeyframeAnim(
        id = "fl_child", durationMs = 4600, prop = Prop.MAT, muscle = MuscleGroup.BACK,
        keyframes = listOf(
            Keyframe(0f, Pose( // kneeling fold, arms long
                pelvisY = 0.335f, torso = 78f, neck = -45f,
                uArmL = 55f, uArmR = 55f, elbowL = 4f, elbowR = 4f,
                thighL = 25f, kneeL = 138f, thighR = 25f, kneeR = 138f,
            )),
            Keyframe(0.5f, Pose(
                pelvisY = 0.34f, torso = 80f, neck = -48f,
                uArmL = 58f, uArmR = 58f, elbowL = 3f, elbowR = 3f,
                thighL = 25f, kneeL = 138f, thighR = 25f, kneeR = 138f,
            )),
        ),
    )

    // ------------------------------------------------------------------ REFORMER
    // Carriage rides along the rail with pose.prop (0 = home, 1 = fully out).

    private val rfFootwork = KeyframeAnim(
        id = "rf_footwork", durationMs = 2800, prop = Prop.REFORMER, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, Pose( // supine on carriage, knees bent, feet on bar
                pelvisX = 0.02f, pelvisY = 0.315f, torso = 92f, neck = -12f,
                uArmL = -8f, uArmR = -8f,
                thighL = -118f, kneeL = 72f, thighR = -118f, kneeR = 72f,
                prop = 0f,
            )),
            Keyframe(0.5f, Pose( // legs pressed long, carriage out
                pelvisX = 0.15f, pelvisY = 0.315f, torso = 92f, neck = -12f,
                uArmL = -8f, uArmR = -8f,
                thighL = -99f, kneeL = 6f, thighR = -99f, kneeR = 6f,
                prop = 1f,
            )),
        ),
    )

    private val rfHundred = KeyframeAnim(
        id = "rf_hundred", durationMs = 1400, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, Pose( // head/shoulders curled, legs tabletop, arms pumping
                pelvisX = 0.05f, pelvisY = 0.31f, torso = 78f, neck = -40f,
                uArmL = -35f, uArmR = -35f,
                thighL = -95f, kneeL = 25f, thighR = -95f, kneeR = 25f,
                prop = 0.35f,
            )),
            Keyframe(0.5f, Pose(
                pelvisX = 0.05f, pelvisY = 0.31f, torso = 78f, neck = -40f,
                uArmL = -55f, uArmR = -55f,
                thighL = -95f, kneeL = 25f, thighR = -95f, kneeR = 25f,
                prop = 0.35f,
            )),
        ),
    )

    private val rfLegCircles = KeyframeAnim(
        id = "rf_legcircles", durationMs = 3000, prop = Prop.REFORMER, muscle = MuscleGroup.LEGS,
        pathJoint = PathJoint.ANKLE,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisX = 0.05f, pelvisY = 0.315f, torso = 92f, neck = -12f, uArmL = -8f, uArmR = -8f,
                thighL = -125f, kneeL = 5f, thighR = -125f, kneeR = 5f, prop = 0.3f,
            )),
            Keyframe(0.25f, Pose(
                pelvisX = 0.05f, pelvisY = 0.315f, torso = 92f, neck = -12f, uArmL = -8f, uArmR = -8f,
                thighL = -105f, kneeL = 5f, thighR = -105f, kneeR = 5f, prop = 0.45f,
            )),
            Keyframe(0.5f, Pose(
                pelvisX = 0.05f, pelvisY = 0.315f, torso = 92f, neck = -12f, uArmL = -8f, uArmR = -8f,
                thighL = -88f, kneeL = 8f, thighR = -88f, kneeR = 8f, prop = 0.3f,
            )),
            Keyframe(0.75f, Pose(
                pelvisX = 0.05f, pelvisY = 0.315f, torso = 92f, neck = -12f, uArmL = -8f, uArmR = -8f,
                thighL = -105f, kneeL = 5f, thighR = -105f, kneeR = 5f, prop = 0.2f,
            )),
        ),
    )

    private val rfFrog = KeyframeAnim(
        id = "rf_frog", durationMs = 2600, prop = Prop.REFORMER, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, Pose(
                pelvisX = 0.03f, pelvisY = 0.315f, torso = 92f, neck = -12f, uArmL = -8f, uArmR = -8f,
                thighL = -115f, kneeL = 85f, thighR = -115f, kneeR = 85f, prop = 0.1f,
            )),
            Keyframe(0.5f, Pose(
                pelvisX = 0.12f, pelvisY = 0.315f, torso = 92f, neck = -12f, uArmL = -8f, uArmR = -8f,
                thighL = -95f, kneeL = 6f, thighR = -95f, kneeR = 6f, prop = 0.8f,
            )),
        ),
    )

    private val rfElephant = KeyframeAnim(
        id = "rf_elephant", durationMs = 2800, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, Pose( // pike: hands on bar, feet on carriage
                pelvisX = 0.05f, pelvisY = 0.10f, torso = 118f, neck = -35f,
                uArmL = 8f, uArmR = 8f, elbowL = 2f, elbowR = 2f,
                thighL = -32f, kneeL = 4f, thighR = -32f, kneeR = 4f, footL = -25f, footR = -25f,
                prop = 0.15f,
            )),
            Keyframe(0.5f, Pose( // carriage pushed back with the legs
                pelvisX = 0.17f, pelvisY = 0.13f, torso = 108f, neck = -35f,
                uArmL = 16f, uArmR = 16f, elbowL = 2f, elbowR = 2f,
                thighL = -45f, kneeL = 4f, thighR = -45f, kneeR = 4f, footL = -25f, footR = -25f,
                prop = 0.7f,
            )),
        ),
    )

    private val rfKneeStretch = KeyframeAnim(
        id = "rf_kneestretch", durationMs = 1800, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, Pose( // kneeling on carriage, hands on bar
                pelvisX = 0.12f, pelvisY = 0.22f, torso = 62f, neck = -45f,
                uArmL = -25f, uArmR = -25f, elbowL = 4f, elbowR = 4f,
                thighL = -30f, kneeL = 115f, thighR = -30f, kneeR = 115f,
                prop = 0.1f,
            )),
            Keyframe(0.5f, Pose( // knees drive the carriage back
                pelvisX = 0.24f, pelvisY = 0.225f, torso = 70f, neck = -48f,
                uArmL = -8f, uArmR = -8f, elbowL = 3f, elbowR = 3f,
                thighL = -55f, kneeL = 110f, thighR = -55f, kneeR = 110f,
                prop = 0.65f,
            )),
        ),
    )

    private val rfLongStretch = KeyframeAnim(
        id = "rf_longstretch", durationMs = 2600, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        keyframes = listOf(
            Keyframe(0f, Pose( // plank: hands on bar, feet against shoulder rests
                pelvisX = 0.10f, pelvisY = 0.20f, torso = 78f, neck = -55f,
                uArmL = -55f, uArmR = -55f, elbowL = 3f, elbowR = 3f,
                thighL = -78f, thighR = -78f, footL = -25f, footR = -25f,
                prop = 0.15f,
            )),
            Keyframe(0.5f, Pose( // whole plank slides back
                pelvisX = 0.22f, pelvisY = 0.205f, torso = 82f, neck = -55f,
                uArmL = -30f, uArmR = -30f, elbowL = 3f, elbowR = 3f,
                thighL = -82f, thighR = -82f, footL = -25f, footR = -25f,
                prop = 0.75f,
            )),
        ),
    )

    private val rfPike = KeyframeAnim(
        id = "rf_pike", durationMs = 3000, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, Pose( // up-stretch: high pike
                pelvisX = 0.02f, pelvisY = 0.06f, torso = 128f, neck = -40f,
                uArmL = 15f, uArmR = 15f, elbowL = 2f, elbowR = 2f,
                thighL = -28f, kneeL = 3f, thighR = -28f, kneeR = 3f, footL = -25f, footR = -25f,
                prop = 0.1f,
            )),
            Keyframe(0.5f, Pose( // lengthen out toward plank
                pelvisX = 0.14f, pelvisY = 0.15f, torso = 95f, neck = -50f,
                uArmL = -20f, uArmR = -20f, elbowL = 2f, elbowR = 2f,
                thighL = -60f, kneeL = 3f, thighR = -60f, kneeR = 3f, footL = -25f, footR = -25f,
                prop = 0.6f,
            )),
        ),
    )

    private val rfChestExp = KeyframeAnim(
        id = "rf_chestexp", durationMs = 2600, prop = Prop.REFORMER, muscle = MuscleGroup.BACK,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, Pose( // kneeling tall, arms forward holding straps
                pelvisX = 0.10f, pelvisY = 0.245f, torso = 2f, neck = 0f,
                uArmL = 55f, uArmR = 55f, elbowL = 4f, elbowR = 4f,
                thighL = 5f, kneeL = 100f, thighR = 5f, kneeR = 100f,
                prop = 0.1f,
            )),
            Keyframe(0.5f, Pose( // arms pulled to hips
                pelvisX = 0.10f, pelvisY = 0.245f, torso = -2f, neck = 2f,
                uArmL = -18f, uArmR = -18f, elbowL = 4f, elbowR = 4f,
                thighL = 5f, kneeL = 100f, thighR = 5f, kneeR = 100f,
                prop = 0.45f,
            )),
        ),
    )

    private val rfRowing = KeyframeAnim(
        id = "rf_rowing", durationMs = 3000, prop = Prop.REFORMER, muscle = MuscleGroup.BACK,
        keyframes = listOf(
            Keyframe(0f, Pose( // seated, arms long forward
                pelvisX = 0.06f, pelvisY = 0.27f, torso = 12f, neck = -8f,
                uArmL = 78f, uArmR = 78f, elbowL = 6f, elbowR = 6f,
                thighL = -78f, kneeL = 25f, thighR = -78f, kneeR = 25f,
                prop = 0.15f,
            )),
            Keyframe(0.5f, Pose( // row back: elbows bend, slight lean
                pelvisX = 0.06f, pelvisY = 0.27f, torso = -6f, neck = 0f,
                uArmL = 15f, uArmR = 15f, elbowL = 95f, elbowR = 95f,
                thighL = -78f, kneeL = 25f, thighR = -78f, kneeR = 25f,
                prop = 0.55f,
            )),
        ),
    )

    private val rfLunge = KeyframeAnim(
        id = "rf_lunge", durationMs = 3200, prop = Prop.REFORMER, muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, Pose( // front foot down, rear foot on carriage
                pelvisX = 0.0f, pelvisY = 0.16f, torso = 12f,
                thighL = 58f, kneeL = 80f, thighR = -42f, kneeR = 55f, footR = -30f,
                uArmL = 20f, uArmR = 20f, elbowL = 10f, elbowR = 10f,
                prop = 0.15f,
            )),
            Keyframe(0.5f, Pose( // carriage presses back, hip opens
                pelvisX = 0.06f, pelvisY = 0.185f, torso = 8f,
                thighL = 48f, kneeL = 62f, thighR = -68f, kneeR = 40f, footR = -30f,
                uArmL = 25f, uArmR = 25f, elbowL = 8f, elbowR = 8f,
                prop = 0.7f,
            )),
        ),
    )

    private val rfMermaid = KeyframeAnim(
        id = "rf_mermaid", durationMs = 3800, prop = Prop.REFORMER, muscle = MuscleGroup.CORE,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, Pose( // side-seated, arm reaching over
                pelvisX = 0.06f, pelvisY = 0.28f, torso = -8f, neck = -5f,
                uArmL = 30f, elbowL = 20f, uArmR = 150f, elbowR = 8f,
                thighL = -45f, kneeL = 110f, thighR = -30f, kneeR = 120f,
                prop = 0.1f,
            )),
            Keyframe(0.5f, Pose( // side bend pushes carriage out
                pelvisX = 0.06f, pelvisY = 0.28f, torso = -28f, neck = -8f,
                uArmL = 15f, elbowL = 30f, uArmR = 172f, elbowR = 4f,
                thighL = -45f, kneeL = 110f, thighR = -30f, kneeR = 120f,
                prop = 0.5f,
            )),
        ),
    )

    private val rfSideSplit = KeyframeAnim(
        id = "rf_sidesplit", durationMs = 3200, facing = Facing.FRONT, prop = Prop.REFORMER,
        muscle = MuscleGroup.LEGS,
        keyframes = listOf(
            Keyframe(0f, Pose( // standing on carriage + platform, feet apart
                pelvisY = grounded(14f, 4f) - 0.06f, thighL = 14f, thighR = 14f, kneeL = 4f, kneeR = 4f,
                uArmL = 88f, uArmR = 88f, prop = 0.15f,
            )),
            Keyframe(0.5f, Pose( // carriage slides out: wider stance
                pelvisY = grounded(32f, 4f) - 0.06f, thighL = 32f, thighR = 32f, kneeL = 4f, kneeR = 4f,
                uArmL = 88f, uArmR = 88f, prop = 0.7f,
            )),
        ),
    )

    private val rfStraps = KeyframeAnim(
        id = "rf_straps", durationMs = 3000, prop = Prop.REFORMER, muscle = MuscleGroup.GLUTES,
        keyframes = listOf(
            Keyframe(0f, Pose( // supine, legs up in straps
                pelvisX = 0.04f, pelvisY = 0.315f, torso = 92f, neck = -12f, uArmL = -8f, uArmR = -8f,
                thighL = -115f, kneeL = 8f, thighR = -115f, kneeR = 8f, prop = 0.2f,
            )),
            Keyframe(0.5f, Pose( // legs lower toward the bar, carriage out
                pelvisX = 0.10f, pelvisY = 0.315f, torso = 92f, neck = -12f, uArmL = -8f, uArmR = -8f,
                thighL = -78f, kneeL = 6f, thighR = -78f, kneeR = 6f, prop = 0.65f,
            )),
        ),
    )

    private val rfPullStraps = KeyframeAnim(
        id = "rf_pullstraps", durationMs = 2800, prop = Prop.REFORMER, muscle = MuscleGroup.ARMS,
        pathJoint = PathJoint.WRIST,
        keyframes = listOf(
            Keyframe(0f, Pose( // supine, arms to ceiling holding straps
                pelvisX = 0.05f, pelvisY = 0.315f, torso = 92f, neck = -12f,
                uArmL = -95f, uArmR = -95f, elbowL = 4f, elbowR = 4f,
                thighL = -95f, kneeL = 30f, thighR = -95f, kneeR = 30f, prop = 0.15f,
            )),
            Keyframe(0.5f, Pose( // arms pull down to sides, carriage glides
                pelvisX = 0.10f, pelvisY = 0.315f, torso = 92f, neck = -12f,
                uArmL = -18f, uArmR = -18f, elbowL = 4f, elbowR = 4f,
                thighL = -95f, kneeL = 30f, thighR = -95f, kneeR = 30f, prop = 0.55f,
            )),
        ),
    )

    private val rfPelvicCurl = KeyframeAnim(
        id = "rf_pelviccurl", durationMs = 3200, prop = Prop.REFORMER, muscle = MuscleGroup.GLUTES,
        pathJoint = PathJoint.PELVIS,
        keyframes = listOf(
            Keyframe(0f, Pose( // supine on carriage, feet on bar, hips down
                pelvisX = 0.03f, pelvisY = 0.32f, torso = 94f, neck = -15f, uArmL = -10f, uArmR = -10f,
                thighL = -112f, kneeL = 78f, thighR = -112f, kneeR = 78f, prop = 0.1f,
            )),
            Keyframe(0.5f, Pose( // hips rolled up
                pelvisX = 0.03f, pelvisY = 0.27f, torso = 106f, neck = -28f, uArmL = -25f, uArmR = -25f,
                thighL = -128f, kneeL = 66f, thighR = -128f, kneeR = 66f, prop = 0.15f,
            )),
        ),
    )

    // ------------------------------------------------------------------ SPIN (procedural)

    private val spSeatedFlat = SpinAnim(id = "sp_seated_flat", cadenceRpm = 90, standing = 0f)
    private val spSeatedClimb = SpinAnim(id = "sp_seated_climb", cadenceRpm = 68, standing = 0f, extraLean = 6f)
    private val spStandingClimb = SpinAnim(id = "sp_standing_climb", cadenceRpm = 64, standing = 1f, extraLean = 10f, muscle = MuscleGroup.GLUTES)
    private val spStandingRun = SpinAnim(id = "sp_standing_run", cadenceRpm = 86, standing = 1f, extraLean = 4f)
    private val spJumps = SpinAnim(id = "sp_jumps", cadenceRpm = 84, standing = 0f, jumpPeriodMs = 4200, muscle = MuscleGroup.LEGS)
    private val spSprint = SpinAnim(id = "sp_sprint", cadenceRpm = 106, standing = 0f, extraLean = 8f, muscle = MuscleGroup.FULL_BODY)

    // ------------------------------------------------------------------ ELLIPTICAL (procedural)

    private val elForward = EllipticalAnim(id = "el_forward", strideRpm = 55, armsDrive = true)
    private val elForwardFast = EllipticalAnim(id = "el_forward_fast", strideRpm = 72, armsDrive = true, muscle = MuscleGroup.FULL_BODY)
    private val elReverse = EllipticalAnim(id = "el_reverse", strideRpm = 52, reverse = true, muscle = MuscleGroup.LEGS)
    private val elLegsOnly = EllipticalAnim(id = "el_legsonly", strideRpm = 55, armsDrive = false, muscle = MuscleGroup.LEGS)
    private val elArmsDrive = EllipticalAnim(id = "el_armsdrive", strideRpm = 58, armsDrive = true, muscle = MuscleGroup.ARMS)
    private val elHill = EllipticalAnim(id = "el_hill", strideRpm = 45, armsDrive = true, muscle = MuscleGroup.GLUTES)

    // ------------------------------------------------------------------ registry

    val all: List<ExerciseAnim> = listOf(
        flSquat, flPushup, flLunge, flPlank, flSidePlank, flBridge, flBurpee, flMountain,
        flJack, flHighKnees, flSkater, flBirdDog, flDeadBug, flSuperman, flSquatJump,
        flWallSit, flInchworm, flBearCrawl, flCrunch, flRussian, flMarch, flArmCircles,
        flCatCow, flHamStretch, flQuadStretch, flChild,
        rfFootwork, rfHundred, rfLegCircles, rfFrog, rfElephant, rfKneeStretch, rfLongStretch,
        rfPike, rfChestExp, rfRowing, rfLunge, rfMermaid, rfSideSplit, rfStraps, rfPullStraps,
        rfPelvicCurl,
        spSeatedFlat, spSeatedClimb, spStandingClimb, spStandingRun, spJumps, spSprint,
        elForward, elForwardFast, elReverse, elLegsOnly, elArmsDrive, elHill,
    )

    val byId: Map<String, ExerciseAnim> = all.associateBy { it.id }
    val ids: Set<String> = byId.keys
}
