package com.example.localai.data.mock

import com.example.localai.data.model.LibraryItem
import com.example.localai.data.model.ScheduledTask
import com.example.localai.data.model.Skill

/** Sample content for secondary chat screens. Nothing here is executed or scheduled. */
object MockWorkspaceRepository {

    val libraryItems = listOf(
        LibraryItem("lib-1", "Meeting summary prompt", "Saved prompt · 2 days ago", "Prompt"),
        LibraryItem("lib-2", "Kotlin style notes", "Note · last week", "Note"),
        LibraryItem("lib-3", "Trip itinerary draft", "Document · last week", "Document"),
        LibraryItem("lib-4", "Email reply templates", "Saved prompt · 3 weeks ago", "Prompt")
    )

    val skills = listOf(
        Skill("skill-summarize", "Summarise", "Condense long text into key points."),
        Skill("skill-rewrite", "Rewrite", "Change tone or length of a passage."),
        Skill("skill-code-review", "Code review", "Point out issues in a code snippet."),
        Skill("skill-translate", "Translate", "Translate short phrases between languages.")
    )

    val scheduledTasks = listOf(
        ScheduledTask("task-1", "Morning briefing", "Every day, 8:00", "Summarise notes saved the day before."),
        ScheduledTask("task-2", "Weekly digest", "Fridays, 17:00", "Collect the week's conversations into a list."),
        ScheduledTask("task-3", "Model update check", "Mondays, 9:00", "Look for newer versions of installed models.")
    )
}
