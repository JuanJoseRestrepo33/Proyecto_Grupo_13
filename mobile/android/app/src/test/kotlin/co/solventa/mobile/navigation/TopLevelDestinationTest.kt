package co.solventa.mobile.navigation

import co.solventa.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Test

class TopLevelDestinationTest {
    @Test
    fun `inicio es el destino de arranque y el respaldo de rutas desconocidas`() {
        assertEquals(TopLevelDestination.HOME, TopLevelDestination.fromRoute(null))
        assertEquals(TopLevelDestination.HOME, TopLevelDestination.fromRoute("unknown"))
    }

    @Test
    fun `las rutas restauradas mantienen la seleccion correcta`() {
        TopLevelDestination.entries.forEach { destination ->
            assertEquals(destination, TopLevelDestination.fromRoute(destination.route))
        }
    }

    @Test
    fun `una ruta anidada conserva su destino superior`() {
        assertEquals(TopLevelDestination.PROFILE, TopLevelDestination.fromRoute("profile/consentimiento"))
        assertEquals(TopLevelDestination.HOME, TopLevelDestination.fromRoute("profileX"))
    }

    @Test
    fun `los cuatro destinos conservan orden etiquetas y rutas unicas`() {
        assertEquals(
            listOf(R.string.nav_home, R.string.nav_policies, R.string.nav_notices, R.string.nav_profile),
            TopLevelDestination.entries.map { it.label },
        )
        assertEquals(4, TopLevelDestination.entries.map { it.route }.toSet().size)
    }
}
