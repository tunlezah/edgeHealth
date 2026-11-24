package au.mark.kinetiq

import au.mark.kinetiq.data.model.BodyArea
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.Equipment
import au.mark.kinetiq.data.model.EvidenceTier
import au.mark.kinetiq.data.model.Exercise
import au.mark.kinetiq.data.model.ExerciseKind
import au.mark.kinetiq.data.model.Impact
import au.mark.kinetiq.data.model.Intensity
import au.mark.kinetiq.data.model.MachineCue
import au.mark.kinetiq.data.model.MovementSlot
import au.mark.kinetiq.data.model.Muscle
import au.mark.kinetiq.data.model.Reference
import au.mark.kinetiq.data.model.ReformerCue
import au.mark.kinetiq.data.model.ResistanceProfile
import au.mark.kinetiq.data.model.Target

/** Synthetic exercises for the muscle-retention tests, so they do not depend on bundled content. */
object ResistanceTestSupport {

    val ref = Reference("A real title", "Someone A", 2020, "A Journal", "10.1000/x", "A finding.")

    /** A plain circuit exercise with no retention profile. */
    fun general(
        id: String,
        cat: Category = Category.FLOOR,
        tier: EvidenceTier = EvidenceTier.STRONG,
        equipment: List<Equipment> = emptyList(),
    ) = Exercise(
        id = id, name = "General $id", category = cat, kind = ExerciseKind.DISCRETE,
        evidenceTier = tier, references = listOf(ref),
        popularityNote = if (tier == EvidenceTier.LIMITED) "Popular but thinly supported." else null,
        summary = "A summary.", voiceName = "General $id",
        voiceHowTo = "Do the thing. Then do it again.", voiceFormCues = listOf("Stay strong."),
        defaultWorkSec = 40, defaultRestSec = 15, minSec = 20, maxSec = 90,
        met = 3.8f, intensity = Intensity.MODERATE, impact = Impact.LOW,
        targets = listOf(Target.STRENGTH), animationId = "fl_squat",
        machine = if (cat == Category.REFORMER) MachineCue(reformer = ReformerCue("MEDIUM", "supine")) else null,
        equipment = equipment,
    )

    /** An exercise usable as a resistance set. */
    fun set(
        id: String,
        slot: MovementSlot,
        cat: Category = Category.FLOOR,
        tier: EvidenceTier = EvidenceTier.MODERATE,
        equipment: List<Equipment> = emptyList(),
        unilateral: Boolean = false,
        isometric: Boolean = false,
        ladder: String? = null,
        rung: Int = 1,
        minUsefulKg: Float? = if (Equipment.HAND_WEIGHTS in equipment) 2f else null,
        springs: String = "HEAVY",
        contraindications: List<BodyArea> = emptyList(),
        primary: List<Muscle> = listOf(primaryFor(slot)),
        secondary: List<Muscle> = emptyList(),
    ) = general(id, cat, equipment = equipment).copy(
        name = "Set $id",
        contraindications = contraindications,
        machine = if (cat == Category.REFORMER) MachineCue(reformer = ReformerCue(springs, "seated")) else null,
        resistance = ResistanceProfile(
            slot = slot, primary = primary, secondary = secondary, tier = tier,
            note = if (tier == EvidenceTier.LIMITED) "Plausible but essentially unstudied." else null,
            references = if (tier == EvidenceTier.LIMITED) emptyList() else listOf(ref),
            unilateral = unilateral, isometric = isometric, ladder = ladder, rung = rung,
            minUsefulKg = minUsefulKg,
        ),
    )

    fun primaryFor(slot: MovementSlot): Muscle = when (slot) {
        MovementSlot.LOWER_PUSH -> Muscle.QUADS
        MovementSlot.UPPER_PUSH -> Muscle.CHEST
        MovementSlot.LOWER_PULL -> Muscle.HAMSTRINGS
        MovementSlot.UPPER_PULL -> Muscle.BACK
        MovementSlot.SHOULDERS -> Muscle.SHOULDERS
        MovementSlot.BICEPS -> Muscle.BICEPS
        MovementSlot.TRICEPS -> Muscle.TRICEPS
        MovementSlot.CALVES -> Muscle.CALVES
        MovementSlot.TRUNK -> Muscle.TRUNK
    }

    /** A mat-only library: three of the big four at MODERATE+, pulling only with weights or as LIMITED. */
    fun matLibrary(): List<Exercise> = listOf(
        set("squat", MovementSlot.LOWER_PUSH, ladder = "leg_push", rung = 1),
        set("split_squat", MovementSlot.LOWER_PUSH, unilateral = true, ladder = "leg_push", rung = 2),
        set("knee_pushup", MovementSlot.UPPER_PUSH, tier = EvidenceTier.STRONG, ladder = "pushup", rung = 2),
        set("pushup", MovementSlot.UPPER_PUSH, tier = EvidenceTier.STRONG, ladder = "pushup", rung = 3),
        set("single_bridge", MovementSlot.LOWER_PULL, unilateral = true),
        set("slide_curl", MovementSlot.LOWER_PULL),
        set("prone_ytw", MovementSlot.UPPER_PULL, tier = EvidenceTier.LIMITED, ladder = "prone_raise", rung = 1),
        set(
            "prone_ytw_weighted", MovementSlot.UPPER_PULL, equipment = listOf(Equipment.HAND_WEIGHTS),
            ladder = "prone_raise", rung = 2, minUsefulKg = 1f,
        ),
        set(
            "lateral_raise", MovementSlot.SHOULDERS, tier = EvidenceTier.STRONG,
            equipment = listOf(Equipment.HAND_WEIGHTS), minUsefulKg = 2f,
        ),
        set("calf_raise", MovementSlot.CALVES, tier = EvidenceTier.STRONG, unilateral = true),
        set("wall_sit", MovementSlot.LOWER_PUSH, isometric = true),
        general("burpee"), general("jack"), general("plank"), general("climber"), general("march"),
    )

    /** Reformer additions: a LIMITED row, MODERATE arm work, and a box-only pull. */
    fun reformerLibrary(): List<Exercise> = listOf(
        set("ref_row", MovementSlot.UPPER_PULL, cat = Category.REFORMER, tier = EvidenceTier.LIMITED, springs = "HEAVY_2"),
        set("ref_curl", MovementSlot.BICEPS, cat = Category.REFORMER, springs = "HEAVY_2"),
        set("ref_triceps", MovementSlot.TRICEPS, cat = Category.REFORMER, springs = "HEAVY"),
        set(
            "ref_box_pull", MovementSlot.UPPER_PULL, cat = Category.REFORMER,
            equipment = listOf(Equipment.REFORMER_BOX), springs = "MEDIUM",
        ),
        general("ref_footwork", Category.REFORMER), general("ref_hundred", Category.REFORMER),
        general("ref_frog", Category.REFORMER), general("ref_elephant", Category.REFORMER),
    )
}
