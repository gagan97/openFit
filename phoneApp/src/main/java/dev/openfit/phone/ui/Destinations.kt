package dev.openfit.phone.ui

/**
 * Every screen the app can show. Dreeve destinations are rendered from the user's own server, so
 * adding a new one is a one-line change here (path + title + group).
 */
enum class DreeveDest(val path: String, val title: String, val group: String) {
    Dashboard("/dashboard", "Dashboard", "Activity"),
    Activities("/activities", "Activities", "Activity"),
    Segments("/segments", "Segments", "Activity"),
    BestEfforts("/best-efforts", "Best efforts", "Activity"),
    Heatmap("/heatmap", "Heatmap", "Activity"),
    Eddington("/eddington", "Eddington", "Progress"),
    Milestones("/milestones", "Milestones", "Progress"),
    MonthlyStats("/monthly-stats", "Monthly stats", "Progress"),
    Rewind("/rewind", "Rewind", "Progress"),
    Badges("/badges", "Badges", "Progress"),
    Gear("/gear", "Gear", "Garage"),
}

/** App-local screens (not Dreeve pages). */
enum class AppDest(val title: String) {
    Sync("Sync"),
    Settings("Settings"),
}

/** One entry in the navigation drawer / bottom bar. */
sealed interface Destination {
    val title: String

    data class Dreeve(val dest: DreeveDest) : Destination {
        override val title get() = dest.title
    }

    data class App(val dest: AppDest) : Destination {
        override val title get() = dest.title
    }
}

/** Bottom-bar roots (kept small so the chrome stays clean; everything else lives in the drawer). */
val BOTTOM_ROOTS: List<Destination> = listOf(
    Destination.Dreeve(DreeveDest.Dashboard),
    Destination.Dreeve(DreeveDest.Activities),
    Destination.App(AppDest.Sync),
    Destination.App(AppDest.Settings),
)

/** Drawer contents, grouped the way Dreeve groups its own navigation. */
val DRAWER_ITEMS: List<Destination> = buildList {
    DreeveDest.entries.groupBy { it.group }.forEach { (_, dests) ->
        dests.forEach { add(Destination.Dreeve(it)) }
    }
    add(Destination.App(AppDest.Sync))
    add(Destination.App(AppDest.Settings))
}
