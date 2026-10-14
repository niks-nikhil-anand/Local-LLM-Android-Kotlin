package com.example.localai.data.model

/** Demo entries for the Library, Skills and Scheduled Tasks screens. */
data class LibraryItem(val id: String, val title: String, val subtitle: String, val kind: String)

data class Skill(val id: String, val name: String, val description: String)

data class ScheduledTask(val id: String, val title: String, val schedule: String, val description: String)
