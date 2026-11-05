package io.github.szymonbonkowski.apexcompanion.appearance

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlin.math.max
import kotlin.math.min

/** Resolved presentation, deliberately independent of gameplay/domain models. */
data class ThemeTokens(
    val style: DesignStyle,
    val dark: Boolean,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val foreground: Color,
    val muted: Color,
    val border: Color,
    val secondary: Color,
    val gap: Dp,
    val cardPadding: Dp,
    val radius: Dp,
    val shape: CornerBasedShape,
    val borderWidth: Dp,
    val elevation: Dp,
    val cardTreatment: CardTreatment,
    val surfaceOpacity: Float,
    val gradientStrength: Float,
    val motionMillis: Int,
    val density: LayoutDensity,
    val numericEmphasis: Boolean,
)

private val LocalBaseFontScale = staticCompositionLocalOf<Float?> { null }
val LocalThemeTokens = staticCompositionLocalOf {
    resolveTheme(AppearancePreferences(), AppearanceEnvironment())
}

fun defaultAccent(style: DesignStyle) =
    when (style) {
        DesignStyle.CARBON -> "58DCC3"
        DesignStyle.LIQUID -> "AD9AFF"
        DesignStyle.EDITORIAL -> "937153"
        DesignStyle.TACTICAL -> "FF9C59"
    }

fun parseAccent(value: String?): Color? =
    value
        ?.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }
        ?.toLongOrNull(16)
        ?.let { Color((0xFF000000L or it).toInt()) }

fun contrastRatio(a: Color, b: Color): Float =
    (max(a.luminance(), b.luminance()) + 0.05f) / (min(a.luminance(), b.luminance()) + 0.05f)

/** Accent is a foreground role; choose a safe shade without making input text disappear. */
fun readableAccent(candidate: Color, background: Color): Color {
    if (contrastRatio(candidate, background) >= 4.5f) return candidate
    val target = if (background.luminance() < 0.45f) Color.White else Color.Black
    for (step in 1..20) {
        val t = step / 20f
        val shade =
            Color(
                candidate.red + (target.red - candidate.red) * t,
                candidate.green + (target.green - candidate.green) * t,
                candidate.blue + (target.blue - candidate.blue) * t,
            )
        if (contrastRatio(shade, background) >= 4.5f) return shade
    }
    return target
}

private fun gradientColors(
    style: DesignStyle,
    accent: Color,
    secondary: Color,
    strength: Float,
): List<Color> =
    when (style) {
        DesignStyle.LIQUID ->
            listOf(
                accent.copy(alpha = .16f * strength),
                Color.Transparent,
                secondary.copy(alpha = .08f * strength),
            )
        DesignStyle.TACTICAL -> listOf(accent.copy(alpha = .07f * strength), Color.Transparent)
        else -> listOf(Color.Transparent, Color.Transparent)
    }

private fun composedBackgrounds(
    style: DesignStyle,
    background: Color,
    surface: Color,
    secondary: Color,
    accent: Color,
    opacity: Float,
    strength: Float,
): List<Color> {
    val card =
        (if (style == DesignStyle.LIQUID) surface.copy(alpha = opacity) else surface).compositeOver(
            background
        )
    return listOf(background, surface, card) +
        gradientColors(style, accent, secondary, strength).map { it.compositeOver(card) } +
        if (style == DesignStyle.LIQUID)
            listOf(accent.copy(alpha = .12f * strength).compositeOver(background))
        else emptyList()
}

/** The same composited stops used by actual card/page drawing, including bounded surface alpha. */
fun renderedThemeBackgrounds(tokens: ThemeTokens): List<Color> =
    composedBackgrounds(
        tokens.style,
        tokens.background,
        tokens.surface,
        tokens.secondary,
        tokens.accent,
        tokens.surfaceOpacity,
        tokens.gradientStrength,
    )

fun themeCardContainerColor(tokens: ThemeTokens): Color =
    if (tokens.style == DesignStyle.LIQUID) tokens.surface.copy(alpha = tokens.surfaceOpacity)
    else tokens.surface

private fun readableAcross(
    candidate: Color,
    dark: Boolean,
    backgrounds: (Color) -> List<Color>,
): Color {
    fun safe(value: Color) = backgrounds(value).all { contrastRatio(value, it) >= 4.5f }
    if (safe(candidate)) return candidate
    val target = if (dark) Color.White else Color.Black
    for (step in 1..40) {
        val amount = step / 40f
        val shade =
            Color(
                candidate.red + (target.red - candidate.red) * amount,
                candidate.green + (target.green - candidate.green) * amount,
                candidate.blue + (target.blue - candidate.blue) * amount,
            )
        if (safe(shade)) return shade
    }
    return target
}

fun resolveTheme(
    preferences: AppearancePreferences,
    environment: AppearanceEnvironment = AppearanceEnvironment(),
): ThemeTokens {
    val p =
        try {
            preferences.validated()
        } catch (_: Exception) {
            AppearancePreferences(style = preferences.style)
        }
    val dark =
        when (p.mode) {
            AppearanceMode.SYSTEM -> environment.systemDark
            AppearanceMode.LIGHT -> false
            AppearanceMode.DARK -> true
        }
    val contrast = p.highContrast || environment.highContrast
    val effectsOff = p.reducedEffects || environment.lowPower || contrast
    val transparencyOff = p.reducedTransparency || environment.reduceTransparency || effectsOff
    val bg =
        if (dark)
            when {
                p.trueBlack -> Color.Black
                p.style == DesignStyle.LIQUID -> Color(0xFF11142A)
                p.style == DesignStyle.EDITORIAL -> Color(0xFF211E1B)
                p.style == DesignStyle.TACTICAL -> Color(0xFF131A20)
                else -> Color(0xFF0D1214)
            }
        else
            when (p.style) {
                DesignStyle.EDITORIAL -> Color(0xFFF7F2E9)
                DesignStyle.LIQUID -> Color(0xFFF0EEFA)
                DesignStyle.TACTICAL -> Color(0xFFE9EEF0)
                else -> Color(0xFFF1F5F3)
            }
    val surface =
        if (dark)
            when (p.surfaceVariant) {
                SurfaceVariant.WARM -> Color(0xFF2B2422)
                SurfaceVariant.COOL -> Color(0xFF1D2936)
                else ->
                    when (p.style) {
                        DesignStyle.LIQUID -> Color(0xFF292944)
                        DesignStyle.EDITORIAL -> Color(0xFF2B2723)
                        DesignStyle.TACTICAL -> Color(0xFF1D2830)
                        else -> Color(0xFF172024)
                    }
            }
        else
            when (p.surfaceVariant) {
                SurfaceVariant.WARM -> Color(0xFFF2E5D6)
                SurfaceVariant.COOL -> Color(0xFFE5EEF7)
                else -> if (p.style == DesignStyle.EDITORIAL) Color(0xFFFFFCF6) else Color.White
            }
    val candidate =
        parseAccent(
            if (p.dynamicColor) environment.dynamicAccentHex ?: p.accentHex else p.accentHex
        ) ?: parseAccent(defaultAccent(p.style))!!
    val opacity = if (transparencyOff) 1f else p.surfaceOpacity / 100f
    val gradient =
        if (effectsOff || p.reducedTransparency || environment.reduceTransparency) 0f
        else p.gradientIntensity / 100f
    val secondary =
        when (p.style) {
            DesignStyle.CARBON -> if (dark) Color(0xFFA5C7BE) else Color(0xFF346558)
            DesignStyle.LIQUID -> if (dark) Color(0xFFB2B7DB) else Color(0xFF505675)
            DesignStyle.EDITORIAL -> if (dark) Color(0xFFD6C1A6) else Color(0xFF67553E)
            DesignStyle.TACTICAL -> if (dark) Color(0xFFBBCAD6) else Color(0xFF3C5869)
        }
    // Accent also contributes to the gradient: solve against the resulting rendered colors.
    val accent =
        readableAcross(candidate, dark) {
            composedBackgrounds(p.style, bg, surface, secondary, it, opacity, gradient)
        }
    val rendered = composedBackgrounds(p.style, bg, surface, secondary, accent, opacity, gradient)
    val foreground =
        readableAcross(if (dark) Color(0xFFF4F3F0) else Color(0xFF1B2224), dark) { rendered }
    val muted =
        readableAcross(if (dark) Color(0xFFB9C3C7) else Color(0xFF505B60), dark) { rendered }
    val density =
        p.density.takeUnless { it == LayoutDensity.DEFAULT }
            ?: when (p.style) {
                DesignStyle.TACTICAL -> LayoutDensity.COMPACT
                DesignStyle.EDITORIAL,
                DesignStyle.LIQUID -> LayoutDensity.SPACIOUS
                else -> LayoutDensity.COMFORTABLE
            }
    val gap =
        when (density) {
            LayoutDensity.COMPACT -> 10.dp
            LayoutDensity.SPACIOUS -> 22.dp
            else -> 16.dp
        }
    val radius =
        when (p.corners) {
            CornerScale.SMALL -> 6.dp
            CornerScale.MEDIUM -> 16.dp
            CornerScale.LARGE -> 30.dp
            CornerScale.DEFAULT ->
                when (p.style) {
                    DesignStyle.CARBON -> 16.dp
                    DesignStyle.LIQUID -> 30.dp
                    DesignStyle.EDITORIAL -> 3.dp
                    DesignStyle.TACTICAL -> 5.dp
                }
        }
    val shape =
        if (p.style == DesignStyle.TACTICAL) CutCornerShape(topStart = radius, bottomEnd = radius)
        else RoundedCornerShape(radius)
    val treatment =
        p.cards.takeUnless { it == CardTreatment.DEFAULT }
            ?: when (p.style) {
                DesignStyle.LIQUID -> CardTreatment.ELEVATED
                DesignStyle.EDITORIAL -> CardTreatment.FLAT
                else -> CardTreatment.OUTLINED
            }
    val motion =
        if (environment.reduceMotion || environment.lowPower || p.motion == MotionPreference.OFF) 0
        else if (p.motion == MotionPreference.REDUCED) 80
        else
            when (p.style) {
                DesignStyle.CARBON -> 150
                DesignStyle.LIQUID -> 300
                DesignStyle.EDITORIAL -> 220
                DesignStyle.TACTICAL -> 100
            }
    return ThemeTokens(
        p.style,
        dark,
        accent,
        bg,
        surface,
        foreground,
        muted,
        if (dark) Color(0xFF62737C) else Color(0xFF89969A),
        secondary,
        gap,
        gap + 4.dp,
        radius,
        shape,
        if (contrast) 2.dp else (p.borderStrength * 0.6f).dp,
        if (effectsOff || treatment != CardTreatment.ELEVATED) 0.dp
        else if (p.style == DesignStyle.LIQUID) 6.dp else 3.dp,
        treatment,
        opacity,
        gradient,
        motion,
        density,
        p.numericEmphasis,
    )
}

private fun typography(tokens: ThemeTokens): Typography {
    val base = Typography()
    val heading =
        when (tokens.style) {
            DesignStyle.EDITORIAL -> FontFamily.Serif
            DesignStyle.TACTICAL -> FontFamily.Monospace
            else -> FontFamily.SansSerif
        }
    val number =
        if (tokens.style == DesignStyle.TACTICAL || tokens.numericEmphasis) FontFamily.Monospace
        else heading
    val spacing =
        if (tokens.style == DesignStyle.TACTICAL) 0.5.sp
        else if (tokens.style == DesignStyle.EDITORIAL) (-0.6).sp else (-0.3).sp
    fun TextStyle.head(size: Int) =
        copy(
            fontFamily = heading,
            fontSize = size.sp,
            lineHeight = (size * 1.18f).sp,
            letterSpacing = spacing,
            fontWeight =
                if (tokens.style == DesignStyle.EDITORIAL) FontWeight.Normal
                else FontWeight.SemiBold,
        )
    return base.copy(
        displayLarge =
            base.displayLarge
                .head(if (tokens.style == DesignStyle.EDITORIAL) 60 else 50)
                .copy(fontFamily = number),
        displayMedium = base.displayMedium.head(42).copy(fontFamily = number),
        displaySmall =
            base.displaySmall
                .head(if (tokens.style == DesignStyle.TACTICAL) 30 else 36)
                .copy(fontFamily = number),
        headlineLarge =
            base.headlineLarge.head(if (tokens.style == DesignStyle.EDITORIAL) 40 else 32),
        headlineMedium = base.headlineMedium.head(28),
        headlineSmall = base.headlineSmall.head(24),
        titleLarge = base.titleLarge.head(22),
        titleMedium = base.titleMedium.copy(fontFamily = heading, fontWeight = FontWeight.SemiBold),
        labelLarge =
            base.labelLarge.copy(
                fontFamily =
                    if (tokens.style == DesignStyle.TACTICAL) FontFamily.Monospace
                    else FontFamily.SansSerif,
                letterSpacing = if (tokens.style == DesignStyle.TACTICAL) 1.sp else 0.3.sp,
            ),
        bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    )
}

/** Complete app-facing Material roles, including selected chips and dialogs. */
fun designColorScheme(t: ThemeTokens): ColorScheme {
    val onAccent =
        if (contrastRatio(Color.Black, t.accent) > contrastRatio(Color.White, t.accent)) Color.Black
        else Color.White
    return (if (t.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = t.accent,
        onPrimary = onAccent,
        primaryContainer =
            t.accent.copy(alpha = if (t.dark) 0.18f else 0.12f).compositeOver(t.surface),
        onPrimaryContainer = t.foreground,
        secondary = t.secondary,
        onSecondary =
            if (contrastRatio(Color.Black, t.secondary) > contrastRatio(Color.White, t.secondary))
                Color.Black
            else Color.White,
        secondaryContainer = t.secondary.copy(alpha = .14f).compositeOver(t.surface),
        onSecondaryContainer = t.foreground,
        tertiary = t.secondary,
        onTertiary =
            if (contrastRatio(Color.Black, t.secondary) > contrastRatio(Color.White, t.secondary))
                Color.Black
            else Color.White,
        tertiaryContainer = t.secondary.copy(alpha = .12f).compositeOver(t.surface),
        onTertiaryContainer = t.foreground,
        inverseSurface = t.foreground,
        inverseOnSurface = t.background,
        inversePrimary = readableAccent(t.accent, t.foreground),
        background = t.background,
        onBackground = t.foreground,
        surface = t.surface,
        onSurface = t.foreground,
        surfaceVariant = t.surface,
        onSurfaceVariant = t.muted,
        surfaceContainer = t.surface,
        surfaceContainerLowest = t.background,
        surfaceBright = t.surface,
        surfaceDim = t.background,
        surfaceContainerLow = t.surface,
        surfaceContainerHigh = t.surface,
        surfaceContainerHighest = t.surface,
        outline = t.border,
        outlineVariant = t.border.copy(alpha = 0.5f),
        surfaceTint = t.accent,
    )
}

@Composable
fun DesignTheme(
    preferences: AppearancePreferences,
    environment: AppearanceEnvironment = AppearanceEnvironment(systemDark = isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    val t = remember(preferences, environment) { resolveTheme(preferences, environment) }
    val colors = designColorScheme(t)
    val density = LocalDensity.current
    val baseScale = LocalBaseFontScale.current ?: density.fontScale
    CompositionLocalProvider(
        LocalThemeTokens provides t,
        LocalBaseFontScale provides baseScale,
        LocalDensity provides
            Density(
                density.density,
                baseScale * (preferences.textScalePercent.coerceIn(85, 130) / 100f),
            ),
    ) {
        MaterialTheme(
            colorScheme = colors,
            motionScheme =
                remember(t.style, t.motionMillis) { DesignMotionScheme(t.style, t.motionMillis) },
            typography = typography(t),
            shapes =
                Shapes(
                    extraSmall = t.shape,
                    small = t.shape,
                    medium = t.shape,
                    large = t.shape,
                    extraLarge = t.shape,
                ),
            content = content,
        )
    }
}

@Composable
fun ThemedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalThemeTokens.current
    val outline =
        if (
            t.cardTreatment == CardTreatment.OUTLINED ||
                t.style == DesignStyle.TACTICAL ||
                t.style == DesignStyle.LIQUID
        )
            BorderStroke(
                t.borderWidth,
                t.border.copy(alpha = if (t.style == DesignStyle.LIQUID) .65f else .5f),
            )
        else null
    val colors =
        CardDefaults.cardColors(
            containerColor = themeCardContainerColor(t),
            contentColor = t.foreground,
        )
    val body: @Composable ColumnScope.() -> Unit = {
        val stops = gradientColors(t.style, t.accent, t.secondary, t.gradientStrength)
        val brush =
            if (t.style == DesignStyle.TACTICAL) Brush.horizontalGradient(stops)
            else Brush.linearGradient(stops)
        Column(Modifier.fillMaxWidth().background(brush)) {
            if (t.style == DesignStyle.TACTICAL)
                Box(Modifier.width(36.dp).height(2.dp).background(t.accent))
            content()
        }
    }
    // A local opaque backdrop bounds compositing even inside another style's Studio preview.
    // This is layered Compose translucency, not a backdrop blur/native glass material.
    Box(modifier.background(t.background, t.shape)) {
        if (onClick == null)
            Card(
                Modifier.fillMaxWidth(),
                shape = t.shape,
                colors = colors,
                border = outline,
                elevation = CardDefaults.cardElevation(defaultElevation = t.elevation),
                content = body,
            )
        else
            Card(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                shape = t.shape,
                colors = colors,
                border = outline,
                elevation = CardDefaults.cardElevation(defaultElevation = t.elevation),
                content = body,
            )
    }
}

@Composable
fun DesignCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ThemedCard(modifier, onClick) {
        Column(
            Modifier.padding(LocalThemeTokens.current.cardPadding),
            verticalArrangement = Arrangement.spacedBy(LocalThemeTokens.current.gap / 2),
            content = content,
        )
    }
}

@Composable
fun designBackground(): Brush {
    val t = LocalThemeTokens.current
    return if (t.style == DesignStyle.LIQUID)
        Brush.linearGradient(
            listOf(
                t.background,
                t.accent.copy(alpha = .12f * t.gradientStrength).compositeOver(t.background),
                t.background,
            )
        )
    else Brush.linearGradient(listOf(t.background, t.background))
}

/** Material controls follow the same Full/Reduced/Off and OS overrides as progress charts. */
private class DesignMotionScheme(private val style: DesignStyle, private val millis: Int) :
    MotionScheme {
    private fun <T> spatial(factor: Float): FiniteAnimationSpec<T> =
        if (style == DesignStyle.LIQUID && millis > 80)
            spring(dampingRatio = 0.92f, stiffness = 600f / factor)
        else tween((millis * factor).toInt())

    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spatial(1f)

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spatial(0.65f)

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spatial(1.2f)

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(millis)

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween((millis * 0.65f).toInt())

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween((millis * 1.2f).toInt())
}
