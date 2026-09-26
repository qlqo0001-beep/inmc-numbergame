package com.inmc.numbergame

/**
 * Permission nodes, in one place.
 *
 * There is deliberately only one plugin-wide node. Anything finer than "may administer" is a
 * per-game decision, and that lives on the game itself as
 * [com.inmc.numbergame.game.GameDefinition.permission] so an admin can point it at a rank they
 * already have rather than at a node this plugin invented.
 */
object Permissions {
    const val ADMIN = "ng.admin"
}
