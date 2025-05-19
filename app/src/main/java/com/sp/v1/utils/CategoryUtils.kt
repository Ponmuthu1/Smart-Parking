package com.sp.v1.utils

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import com.sp.v1.R

/**
 * Utility class for handling parking spot categories
 */
object CategoryUtils {
    
    /**
     * Get the color resource ID for a category
     */
    fun getCategoryColorId(category: String): Int {
        return when {
            category.contains("EV", ignoreCase = true) -> R.color.category_ev_charging
            category.contains("Open", ignoreCase = true) -> R.color.category_open_roof
            category.contains("Covered", ignoreCase = true) -> R.color.category_covered_roof
            category.contains("Valet", ignoreCase = true) -> R.color.category_valet
            category.contains("Handicap", ignoreCase = true) -> R.color.category_handicap
            category.contains("Secure", ignoreCase = true) -> R.color.category_secure
            else -> R.color.category_standard
        }
    }
    
    /**
     * Create and return a background drawable for a category with appropriate color
     */
    fun getCategoryBackground(context: Context, category: String): GradientDrawable {
        val colorId = getCategoryColorId(category)
        val color = ContextCompat.getColor(context, colorId)
        
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.resources.getDimension(R.dimen.category_corner_radius)
            setColor(color)
        }
    }
    
    /**
     * Get an array of all available categories
     */
    fun getAllCategories(context: Context): Array<String> {
        return context.resources.getStringArray(R.array.parking_categories)
    }
    
    /**
     * Format multiple categories into a display string
     */
    fun formatCategories(categories: List<String>): String {
        return when {
            categories.isEmpty() -> "Standard"
            categories.size == 1 -> categories[0]
            else -> "${categories[0]} +${categories.size - 1}"
        }
    }
    
    /**
     * Check if a list of categories contains a specific category
     * Case-insensitive search
     */
    fun containsCategory(categories: List<String>, categoryToFind: String): Boolean {
        return categories.any { it.equals(categoryToFind, ignoreCase = true) }
    }
    
    /**
     * Add a category to a list if it doesn't already exist
     * Returns true if added, false if already exists
     */
    fun addCategoryIfNotExists(categories: MutableList<String>, categoryToAdd: String): Boolean {
        if (!containsCategory(categories, categoryToAdd)) {
            categories.add(categoryToAdd)
            return true
        }
        return false
    }
} 