package me.kavishdevar.librepods.presentation.design.typography

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import me.kavishdevar.librepods.R

/**
 * Roboto Flex with one [Font] per weight, so the weight of every M3 type style (and of any
 * `fontWeight` override) maps onto the variable `wght` axis instead of being synthesized.
 */
private fun robotoFlexFamily(width: Float, grade: Int) = FontFamily(
    (100..1000 step 100).map { weight ->
        Font(
            resId = R.font.roboto_flex,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight),
                FontVariation.width(width),
                FontVariation.grade(grade),
            )
        )
    }
)

// Regular styles: plain Roboto Flex, i.e. the M3 baseline type scale.
private val regular = robotoFlexFamily(width = 100f, grade = 0)

// Emphasized styles: a bit heavier and slightly wider, for the few places that should stand
// out (M3 Expressive uses emphasis sparingly, it is not meant for every list item).
private val emphasized = robotoFlexFamily(width = 106f, grade = 0)

private fun TextStyle.regular() = copy(fontFamily = regular)

private fun TextStyle.emphasized(extraWeight: Int = 0) = copy(
    fontFamily = emphasized,
    fontWeight = FontWeight(((fontWeight ?: FontWeight.Normal).weight + extraWeight).coerceAtMost(1000)),
)

val RobotoFlexExpressiveTypography = Typography().run {
    copy(
        displayLarge = displayLarge.regular(),
        displayMedium = displayMedium.regular(),
        displaySmall = displaySmall.regular(),

        headlineLarge = headlineLarge.regular(),
        headlineMedium = headlineMedium.regular(),
        headlineSmall = headlineSmall.regular(),

        titleLarge = titleLarge.regular(),
        titleMedium = titleMedium.regular(),
        titleSmall = titleSmall.regular(),

        bodyLarge = bodyLarge.regular(),
        bodyMedium = bodyMedium.regular(),
        bodySmall = bodySmall.regular(),

        labelLarge = labelLarge.regular(),
        labelMedium = labelMedium.regular(),
        labelSmall = labelSmall.regular(),

        // big expressive headlines get some extra weight, smaller text stays readable
        displayLargeEmphasized = displayLargeEmphasized.emphasized(extraWeight = 200),
        displayMediumEmphasized = displayMediumEmphasized.emphasized(extraWeight = 200),
        displaySmallEmphasized = displaySmallEmphasized.emphasized(extraWeight = 200),

        headlineLargeEmphasized = headlineLargeEmphasized.emphasized(extraWeight = 100),
        headlineMediumEmphasized = headlineMediumEmphasized.emphasized(extraWeight = 100),
        headlineSmallEmphasized = headlineSmallEmphasized.emphasized(extraWeight = 100),

        titleLargeEmphasized = titleLargeEmphasized.emphasized(extraWeight = 100),
        titleMediumEmphasized = titleMediumEmphasized.emphasized(),
        titleSmallEmphasized = titleSmallEmphasized.emphasized(),

        bodyLargeEmphasized = bodyLargeEmphasized.emphasized(),
        bodyMediumEmphasized = bodyMediumEmphasized.emphasized(),
        bodySmallEmphasized = bodySmallEmphasized.emphasized(),

        labelLargeEmphasized = labelLargeEmphasized.emphasized(),
        labelMediumEmphasized = labelMediumEmphasized.emphasized(),
        labelSmallEmphasized = labelSmallEmphasized.emphasized(),
    )
}
