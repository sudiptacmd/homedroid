package dev.homedroid

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build

/**
 * Design tokens for the phone screens: colour roles, type scale, shapes and spacing.
 *
 * Colour roles follow Material 3. On Android 12+ they come from the wallpaper-based system
 * palette (unless the user turned that off); otherwise from a curated Homedroid teal scheme.
 * Success and warning have no system palette, so they always use the curated values.
 */
data class Palette(
    val dark: Boolean,
    val background: Int,
    val surfaceLowest: Int,
    val surfaceLow: Int,
    val surfaceContainer: Int,
    val surfaceHigh: Int,
    val surfaceHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val success: Int,
    val successContainer: Int,
    val onSuccessContainer: Int,
    val warning: Int,
    val warningContainer: Int,
    val onWarningContainer: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val inversePrimary: Int,
) {
    /** [color] at [alpha] (0–1), for state layers and scrims. */
    fun alpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    companion object {
        fun c(hex: String) = Color.parseColor(hex)

        /** Linear mix of two colours; used to reach tones between the system palette's steps. */
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
            return Color.rgb(ch(16), ch(8), ch(0))
        }

        fun dynamicAvailable() = Build.VERSION.SDK_INT >= 31

        fun create(ctx: Context, dark: Boolean, dynamic: Boolean, oled: Boolean = false): Palette {
            val base = if (dark || oled) curatedDark() else curatedLight()
            val p = if (dynamic && dynamicAvailable()) system(ctx, dark || oled, base) else base
            return if (oled) oledBlack(p) else p
        }

        /**
         * OLED black: a true black background, surfaces only a little above it and containers
         * dimmed, so no large bright block stays lit while a server phone sits on a shelf.
         */
        fun oledBlack(d: Palette): Palette {
            fun dim(c: Int, t: Float) = mix(c, Color.BLACK, t)
            return d.copy(
                background = Color.BLACK, surfaceLowest = Color.BLACK, surfaceLow = c("#0B0B0B"),
                surfaceContainer = c("#111111"), surfaceHigh = c("#181818"), surfaceHighest = c("#202020"),
                onSurface = dim(d.onSurface, 0.12f), onSurfaceVariant = dim(d.onSurfaceVariant, 0.2f),
                outlineVariant = c("#2A2A2A"),
                primaryContainer = dim(d.primaryContainer, 0.55f), secondaryContainer = dim(d.secondaryContainer, 0.5f),
                tertiaryContainer = dim(d.tertiaryContainer, 0.5f), errorContainer = dim(d.errorContainer, 0.45f),
                successContainer = dim(d.successContainer, 0.45f), warningContainer = dim(d.warningContainer, 0.45f),
                primary = dim(d.primary, 0.1f), inverseSurface = dim(d.inverseSurface, 0.35f),
            )
        }

        private fun curatedDark() = Palette(
            dark = true,
            background = c("#0E1415"), surfaceLowest = c("#090F10"), surfaceLow = c("#161D1E"),
            surfaceContainer = c("#1A2122"), surfaceHigh = c("#242B2C"), surfaceHighest = c("#2F3637"),
            onSurface = c("#DEE4E3"), onSurfaceVariant = c("#BEC9C6"), outline = c("#889390"), outlineVariant = c("#3F4947"),
            primary = c("#7DD8C0"), onPrimary = c("#00382D"), primaryContainer = c("#005142"), onPrimaryContainer = c("#99F5DB"),
            secondary = c("#B1CCC3"), secondaryContainer = c("#334B44"), onSecondaryContainer = c("#CDE8DF"),
            tertiary = c("#A9CBE5"), tertiaryContainer = c("#284B61"), onTertiaryContainer = c("#C8E6FF"),
            error = c("#FFB4AB"), onError = c("#690005"), errorContainer = c("#93000A"), onErrorContainer = c("#FFDAD6"),
            success = c("#86D993"), successContainer = c("#0F5223"), onSuccessContainer = c("#A2F5AD"),
            warning = c("#F5BF6B"), warningContainer = c("#5C4300"), onWarningContainer = c("#FFDEA8"),
            inverseSurface = c("#DEE4E3"), inverseOnSurface = c("#2B3231"), inversePrimary = c("#006B58"),
        )

        private fun curatedLight() = Palette(
            dark = false,
            background = c("#F5FAF8"), surfaceLowest = c("#FFFFFF"), surfaceLow = c("#EFF5F2"),
            surfaceContainer = c("#E9EFEC"), surfaceHigh = c("#E3EAE7"), surfaceHighest = c("#DEE4E1"),
            onSurface = c("#171D1C"), onSurfaceVariant = c("#3F4947"), outline = c("#6F7977"), outlineVariant = c("#BEC9C6"),
            primary = c("#006B58"), onPrimary = c("#FFFFFF"), primaryContainer = c("#9EF2DA"), onPrimaryContainer = c("#002019"),
            secondary = c("#4A635C"), secondaryContainer = c("#CDE8DF"), onSecondaryContainer = c("#06201A"),
            tertiary = c("#42637A"), tertiaryContainer = c("#C8E6FF"), onTertiaryContainer = c("#001E2F"),
            error = c("#BA1A1A"), onError = c("#FFFFFF"), errorContainer = c("#FFDAD6"), onErrorContainer = c("#410002"),
            success = c("#1B6D2F"), successContainer = c("#A4F4AE"), onSuccessContainer = c("#002109"),
            warning = c("#7C5800"), warningContainer = c("#FFDEA8"), onWarningContainer = c("#271900"),
            inverseSurface = c("#2B3231"), inverseOnSurface = c("#ECF2EF"), inversePrimary = c("#7DD8C0"),
        )

        /**
         * Material 3 role mapping onto the Android 12 system palette. Its steps are tones
         * (0 = 99/100, 10 = 99, 50 = 95, 100 = 90, …, 900 = 10, 1000 = 0); tones between
         * steps (surface containers) are mixed.
         */
        @android.annotation.SuppressLint("UseRequiresApi")
        @android.annotation.TargetApi(31)
        private fun system(ctx: Context, dark: Boolean, base: Palette): Palette {
            fun r(id: Int) = ctx.getColor(id)
            val a1 = listOf(android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50,
                android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300,
                android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600,
                android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900,
                android.R.color.system_accent1_1000).map(::r)
            val a2 = listOf(android.R.color.system_accent2_0, android.R.color.system_accent2_10, android.R.color.system_accent2_50,
                android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300,
                android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600,
                android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900,
                android.R.color.system_accent2_1000).map(::r)
            val a3 = listOf(android.R.color.system_accent3_0, android.R.color.system_accent3_10, android.R.color.system_accent3_50,
                android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_300,
                android.R.color.system_accent3_400, android.R.color.system_accent3_500, android.R.color.system_accent3_600,
                android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900,
                android.R.color.system_accent3_1000).map(::r)
            val n1 = listOf(android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50,
                android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
                android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600,
                android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
                android.R.color.system_neutral1_1000).map(::r)
            val n2 = listOf(android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50,
                android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300,
                android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600,
                android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
                android.R.color.system_neutral2_1000).map(::r)
            // Index of a step: 0→0, 10→1, 50→2, 100→3 … 900→11, 1000→12.
            fun List<Int>.s(step: Int) = this[if (step == 0) 0 else if (step == 10) 1 else if (step == 50) 2 else step / 100 + 2]
            return if (dark) Palette(
                dark = true,
                background = mix(n1.s(900), n1.s(1000), 0.4f), surfaceLowest = mix(n1.s(900), n1.s(1000), 0.6f),
                surfaceLow = n1.s(900), surfaceContainer = mix(n1.s(900), n1.s(800), 0.2f),
                surfaceHigh = mix(n1.s(900), n1.s(800), 0.7f), surfaceHighest = mix(n1.s(800), n1.s(700), 0.2f),
                onSurface = n1.s(100), onSurfaceVariant = n2.s(200), outline = n2.s(400), outlineVariant = n2.s(700),
                primary = a1.s(200), onPrimary = a1.s(800), primaryContainer = a1.s(700), onPrimaryContainer = a1.s(100),
                secondary = a2.s(200), secondaryContainer = a2.s(700), onSecondaryContainer = a2.s(100),
                tertiary = a3.s(200), tertiaryContainer = a3.s(700), onTertiaryContainer = a3.s(100),
                error = base.error, onError = base.onError, errorContainer = base.errorContainer, onErrorContainer = base.onErrorContainer,
                success = base.success, successContainer = base.successContainer, onSuccessContainer = base.onSuccessContainer,
                warning = base.warning, warningContainer = base.warningContainer, onWarningContainer = base.onWarningContainer,
                inverseSurface = n1.s(100), inverseOnSurface = mix(n1.s(800), n1.s(900), 0.5f), inversePrimary = a1.s(600),
            ) else Palette(
                dark = false,
                background = mix(n1.s(10), n1.s(50), 0.25f), surfaceLowest = n1.s(0),
                surfaceLow = mix(n1.s(10), n1.s(50), 0.6f), surfaceContainer = mix(n1.s(50), n1.s(100), 0.2f),
                surfaceHigh = mix(n1.s(50), n1.s(100), 0.6f), surfaceHighest = n1.s(100),
                onSurface = n1.s(900), onSurfaceVariant = n2.s(700), outline = n2.s(500), outlineVariant = n2.s(200),
                primary = a1.s(600), onPrimary = a1.s(0), primaryContainer = a1.s(100), onPrimaryContainer = a1.s(900),
                secondary = a2.s(600), secondaryContainer = a2.s(100), onSecondaryContainer = a2.s(900),
                tertiary = a3.s(600), tertiaryContainer = a3.s(100), onTertiaryContainer = a3.s(900),
                error = base.error, onError = base.onError, errorContainer = base.errorContainer, onErrorContainer = base.onErrorContainer,
                success = base.success, successContainer = base.successContainer, onSuccessContainer = base.onSuccessContainer,
                warning = base.warning, warningContainer = base.warningContainer, onWarningContainer = base.onWarningContainer,
                inverseSurface = mix(n1.s(800), n1.s(900), 0.5f), inverseOnSurface = n1.s(50), inversePrimary = a1.s(200),
            )
        }
    }
}

/** Material 3 type scale: size (sp), weight, line height (sp) and tracking. */
enum class TypeStyle(val size: Float, val weight: Int, val line: Float, val tracking: Float = 0f) {
    DisplaySmall(36f, 400, 44f),
    HeadlineMedium(28f, 400, 36f),
    HeadlineSmall(24f, 400, 32f),
    TitleLarge(22f, 400, 28f),
    TitleMedium(16f, 500, 24f, 0.009f),
    TitleSmall(14f, 500, 20f, 0.007f),
    BodyLarge(16f, 400, 24f, 0.03f),
    BodyMedium(14f, 400, 20f, 0.018f),
    BodySmall(12f, 400, 16f, 0.033f),
    LabelLarge(14f, 500, 20f, 0.007f),
    LabelMedium(12f, 500, 16f, 0.04f),
    LabelSmall(11f, 500, 16f, 0.045f);

    /** Variable-weight system sans (Roboto Flex / Google Sans where the phone has it). */
    val typeface: Typeface by lazy { Typeface.create(Typeface.SANS_SERIF, weight, false) }
}

/** Corner radii in dp. */
object Shape {
    const val XS = 4
    const val SM = 8
    const val MD = 12
    const val LG = 16
    const val XL = 28
    const val FULL = 999
}

/** Spacing in dp, on a 4 dp grid. */
object Space {
    const val XXS = 2
    const val XS = 4
    const val SM = 8
    const val MD = 12
    const val LG = 16
    const val XL = 20
    const val XXL = 24
    const val XXXL = 32
    /** Screen side margin. */
    const val GUTTER = 16
}

/** Semantic tone for pills, banners, tiles and buttons. */
enum class Tone { Neutral, Primary, Success, Warning, Error }
