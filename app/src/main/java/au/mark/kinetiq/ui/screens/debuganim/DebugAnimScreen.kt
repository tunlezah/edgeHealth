package au.mark.kinetiq.ui.screens.debuganim

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.mark.kinetiq.anim.AnimationRegistry
import kotlinx.coroutines.delay

/**
 * Hidden QA screen (long-press the version row in Settings): cycles through every animation
 * in the registry so each can be eyeballed against its exercise's how-to text.
 */
@Composable
fun DebugAnimScreen() {
    val ids = remember { AnimationRegistry.all.map { it.id } }
    var index by remember { mutableIntStateOf(0) }
    var autoCycle by remember { mutableStateOf(true) }

    LaunchedEffect(autoCycle) {
        while (autoCycle) {
            delay(4000)
            index = (index + 1) % ids.size
        }
    }

    val id = ids[index]
    Column(
        Modifier.fillMaxSize().padding(16.dp).clickable { index = (index + 1) % ids.size },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Animation QA", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${index + 1} / ${ids.size} — $id",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        au.mark.kinetiq.anim.ExerciseAnimationView(
            animationId = id,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentDesc = "QA animation $id",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { index = (index - 1 + ids.size) % ids.size }) { Text("Prev") }
            OutlinedButton(onClick = { autoCycle = !autoCycle }) { Text(if (autoCycle) "Pause cycle" else "Auto cycle") }
            OutlinedButton(onClick = { index = (index + 1) % ids.size }) { Text("Next") }
        }
        Text("Tap the animation to advance.", style = MaterialTheme.typography.bodySmall)
    }
}
