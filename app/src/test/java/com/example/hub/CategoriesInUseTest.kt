package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoriesInUseTest {
    @Test
    fun onlyCategoriesWithAppsInEnumOrder() {
        val apps = listOf(
            HubApp("A", "a", listOf(Category.SPORTS)),
            HubApp("B", "b", listOf(Category.LIVE_TV, Category.MOVIES))
        )
        assertEquals(listOf(Category.MOVIES, Category.SPORTS, Category.LIVE_TV), categoriesInUse(apps))
    }

    @Test
    fun noAppsMeansNoCategories() {
        assertEquals(emptyList<Category>(), categoriesInUse(emptyList()))
    }
}
