package co.solventa.mobile.navigation

import androidx.annotation.StringRes
import co.solventa.mobile.R

enum class TopLevelDestination(val route: String, @StringRes val label: Int) {
    HOME("home", R.string.nav_home),
    POLICIES("policies", R.string.nav_policies),
    NOTICES("notices", R.string.nav_notices),
    PROFILE("profile", R.string.nav_profile),
    ;

    companion object {
        fun fromRoute(route: String?): TopLevelDestination =
            entries.firstOrNull { it.route == route } ?: HOME
    }
}
