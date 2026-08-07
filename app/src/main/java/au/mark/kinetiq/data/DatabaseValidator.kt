package au.mark.kinetiq.data

import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.EvidenceTier
import au.mark.kinetiq.data.model.Exercise
import au.mark.kinetiq.data.model.ExerciseDatabaseFile
import au.mark.kinetiq.data.model.ExerciseKind

/**
 * Validates the bundled exercise database against the content rules in the spec:
 *  - minimum content counts per category,
 *  - STRONG/MODERATE tiers need >= 1 real-looking reference,
 *  - LIMITED tier needs an honest [Exercise.popularityNote],
 *  - every field populated (no placeholder text),
 *  - machine cues present and matching the category,
 *  - animation ids resolve against the animation registry,
 *  - named routines reference existing segments of the same category.
 *
 * Used both by the runtime loader (fail fast on a bad build) and by unit tests.
 */
object DatabaseValidator {

    private val PLACEHOLDER_MARKERS = listOf("todo", "tbd", "lorem", "placeholder", "xxx", "fixme")

    data class Result(val problems: List<String>) {
        val isValid: Boolean get() = problems.isEmpty()
    }

    fun validate(db: ExerciseDatabaseFile, knownAnimationIds: Set<String>): Result {
        val problems = mutableListOf<String>()
        fun problem(msg: String) = problems.add(msg)

        if (db.schemaVersion < 1) problem("schemaVersion must be >= 1")

        // --- Minimum content counts ---
        val byCat = db.exercises.groupBy { it.category }
        fun countOf(cat: Category, kind: ExerciseKind? = null) =
            byCat[cat].orEmpty().count { kind == null || it.kind == kind }

        if (countOf(Category.FLOOR) < 18) problem("Need >= 18 FLOOR exercises, have ${countOf(Category.FLOOR)}")
        if (countOf(Category.REFORMER) < 14) problem("Need >= 14 REFORMER exercises, have ${countOf(Category.REFORMER)}")
        if (countOf(Category.SPIN, ExerciseKind.INTERVAL_SEGMENT) < 10)
            problem("Need >= 10 SPIN segments, have ${countOf(Category.SPIN, ExerciseKind.INTERVAL_SEGMENT)}")
        if (countOf(Category.ELLIPTICAL, ExerciseKind.INTERVAL_SEGMENT) < 8)
            problem("Need >= 8 ELLIPTICAL segments, have ${countOf(Category.ELLIPTICAL, ExerciseKind.INTERVAL_SEGMENT)}")

        val routinesByCat = db.routines.groupBy { it.category }
        if ((routinesByCat[Category.SPIN]?.size ?: 0) < 6)
            problem("Need >= 6 SPIN named routines, have ${routinesByCat[Category.SPIN]?.size ?: 0}")
        if ((routinesByCat[Category.ELLIPTICAL]?.size ?: 0) < 4)
            problem("Need >= 4 ELLIPTICAL named routines, have ${routinesByCat[Category.ELLIPTICAL]?.size ?: 0}")

        // --- Uniqueness ---
        db.exercises.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { problem("Duplicate exercise id: $it") }
        db.routines.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { problem("Duplicate routine id: $it") }

        // --- Per-exercise rules ---
        for (ex in db.exercises) {
            val where = "exercise '${ex.id}'"
            validateText(where, "name", ex.name, problems)
            validateText(where, "summary", ex.summary, problems)
            validateText(where, "voiceName", ex.voiceName, problems)
            validateText(where, "voiceHowTo", ex.voiceHowTo, problems)
            if (ex.voiceHowTo.split('.', '!', '?').count { it.isNotBlank() } < 2)
                problem("$where: voiceHowTo should be 2-4 spoken sentences")
            if (ex.voiceFormCues.isEmpty()) problem("$where: needs at least one voiceFormCue")
            ex.voiceFormCues.forEachIndexed { i, cue -> validateText(where, "voiceFormCues[$i]", cue, problems) }

            when (ex.evidenceTier) {
                EvidenceTier.STRONG, EvidenceTier.MODERATE -> {
                    if (ex.references.isEmpty()) problem("$where: ${ex.evidenceTier} tier requires >= 1 reference")
                }
                EvidenceTier.LIMITED -> {
                    if (ex.popularityNote.isNullOrBlank()) problem("$where: LIMITED tier requires popularityNote")
                }
            }
            for (ref in ex.references) {
                val rw = "$where reference '${ref.title.take(40)}'"
                validateText(rw, "title", ref.title, problems)
                validateText(rw, "authors", ref.authors, problems)
                validateText(rw, "journal", ref.journal, problems)
                validateText(rw, "doiOrPmid", ref.doiOrPmid, problems)
                validateText(rw, "finding", ref.finding, problems)
                if (ref.year !in 1950..2026) problem("$rw: implausible year ${ref.year}")
            }

            if (ex.defaultWorkSec <= 0) problem("$where: defaultWorkSec must be > 0")
            if (ex.defaultRestSec < 0) problem("$where: defaultRestSec must be >= 0")
            if (ex.minSec > ex.defaultWorkSec || ex.defaultWorkSec > ex.maxSec)
                problem("$where: need minSec <= defaultWorkSec <= maxSec (${ex.minSec}/${ex.defaultWorkSec}/${ex.maxSec})")
            if (ex.met <= 0f || ex.met > 20f) problem("$where: implausible MET ${ex.met}")
            if (ex.targets.isEmpty()) problem("$where: needs at least one target")

            when (ex.category) {
                Category.SPIN -> {
                    val cue = ex.machine?.spin
                    if (cue == null) problem("$where: SPIN exercise needs machine.spin cue")
                    else {
                        if (cue.resistanceLow !in 0f..1f || cue.resistanceHigh !in 0f..1f || cue.resistanceLow > cue.resistanceHigh)
                            problem("$where: spin resistance fractions must be 0..1 and low <= high")
                        if (cue.cadenceRpmLow !in 40..130 || cue.cadenceRpmHigh !in 40..140 || cue.cadenceRpmLow > cue.cadenceRpmHigh)
                            problem("$where: implausible cadence ${cue.cadenceRpmLow}-${cue.cadenceRpmHigh}")
                        validateText(where, "spin.position", cue.position, problems)
                    }
                    if (ex.kind != ExerciseKind.INTERVAL_SEGMENT) problem("$where: SPIN entries must be INTERVAL_SEGMENT")
                }
                Category.ELLIPTICAL -> {
                    val cue = ex.machine?.elliptical
                    if (cue == null) problem("$where: ELLIPTICAL exercise needs machine.elliptical cue")
                    else {
                        if (cue.resistanceLow !in 0f..1f || cue.resistanceHigh !in 0f..1f || cue.resistanceLow > cue.resistanceHigh)
                            problem("$where: elliptical resistance fractions must be 0..1 and low <= high")
                        if (cue.direction !in listOf("FORWARD", "REVERSE")) problem("$where: elliptical direction must be FORWARD or REVERSE")
                        validateText(where, "elliptical.arms", cue.arms, problems)
                    }
                    if (ex.kind != ExerciseKind.INTERVAL_SEGMENT) problem("$where: ELLIPTICAL entries must be INTERVAL_SEGMENT")
                }
                Category.REFORMER -> {
                    val cue = ex.machine?.reformer
                    if (cue == null) problem("$where: REFORMER exercise needs machine.reformer cue")
                    else {
                        if (!SPRING_PATTERN.matches(cue.springs)) problem("$where: springs '${cue.springs}' not in LIGHT|MEDIUM|HEAVY[_count] form")
                        validateText(where, "reformer.bodyPosition", cue.bodyPosition, problems)
                    }
                    if (ex.kind != ExerciseKind.DISCRETE) problem("$where: REFORMER entries must be DISCRETE")
                }
                Category.FLOOR -> {
                    if (ex.kind != ExerciseKind.DISCRETE) problem("$where: FLOOR entries must be DISCRETE")
                }
            }

            if (ex.animationId.isBlank()) problem("$where: blank animationId")
            else if (ex.animationId !in knownAnimationIds) problem("$where: animationId '${ex.animationId}' does not resolve")
        }

        // --- Named routines ---
        val exerciseById = db.exercises.associateBy { it.id }
        for (r in db.routines) {
            val where = "routine '${r.id}'"
            validateText(where, "name", r.name, problems)
            validateText(where, "summary", r.summary, problems)
            if (r.category !in listOf(Category.SPIN, Category.ELLIPTICAL))
                problem("$where: named routines are only for SPIN/ELLIPTICAL")
            if (r.steps.isEmpty()) problem("$where: has no steps")
            for ((i, step) in r.steps.withIndex()) {
                val ex = exerciseById[step.exerciseId]
                when {
                    ex == null -> problem("$where step $i: unknown exercise '${step.exerciseId}'")
                    ex.category != r.category -> problem("$where step $i: category mismatch (${ex.category})")
                    ex.kind != ExerciseKind.INTERVAL_SEGMENT -> problem("$where step $i: not an interval segment")
                    step.durationSec < 10 -> problem("$where step $i: durationSec too short (${step.durationSec})")
                }
            }
            if (r.totalSec < 4 * 60) problem("$where: total ${r.totalSec}s is too short for a named routine")
        }

        return Result(problems)
    }

    private val SPRING_PATTERN = Regex("^(LIGHT|MEDIUM|HEAVY)(_[1-4])?$")

    private fun validateText(where: String, field: String, value: String, problems: MutableList<String>) {
        if (value.isBlank()) {
            problems.add("$where: blank $field")
            return
        }
        val lower = value.lowercase()
        if (PLACEHOLDER_MARKERS.any { marker -> Regex("\\b$marker\\b").containsMatchIn(lower) }) {
            problems.add("$where: $field contains placeholder text ('$value')")
        }
    }
}
