package io.nekohasekai.sagernet.ui

internal interface AppFilterEntry {
    val name: CharSequence
    val packageName: String
    val uid: Int
    val sys: Boolean
}

internal fun <T : AppFilterEntry> filterApps(apps: List<T>, query: CharSequence, includeSystem: Boolean) =
    apps.filter { app ->
        val matchesQuery = query.isEmpty() ||
            app.name.contains(query, ignoreCase = true) ||
            app.packageName.contains(query, ignoreCase = true) ||
            app.uid.toString().contains(query)
        matchesQuery && (includeSystem || !app.sys)
    }
