package com.voicetoinvoice.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette for the app's dark-first visual language.
 *
 * The app is used one-handed, at arm's length, often in a dim shop or in harsh outdoor
 * daylight, by someone whose other hand is holding vegetables. That drives every choice
 * here: a near-black ground so the OLED panel stays readable and cheap on battery, one
 * saturated accent reserved for the single most important action on any given screen, and
 * semantic colors that survive being glanced at rather than read.
 */

// Ground layers. Not pure black -- a slight lift keeps elevated cards from vanishing into
// the background on OLED, which is what makes a flat dark UI look broken rather than sleek.
val Ink            = Color(0xFF0B0B0D)
val InkElevated    = Color(0xFF141417)
val InkCard        = Color(0xFF1C1C21)
val InkCardHigh    = Color(0xFF26262C)
val Hairline       = Color(0xFF2F2F36)

// Accent. A single warm red carries every primary action.
val Accent         = Color(0xFFFA243C)
val AccentPressed  = Color(0xFFD11B30)
val AccentSoft     = Color(0x1FFA243C)

// Semantic. Money earned is green, money owed is amber, destructive/failed is red.
val Money          = Color(0xFF32D74B)
val Owed           = Color(0xFFFF9F0A)
val Danger         = Color(0xFFFF453A)
val Info           = Color(0xFF0A84FF)

// Type. Full-strength white is reserved for headlines and amounts so the hierarchy is
// carried by weight and opacity rather than by size alone.
val TextPrimary    = Color(0xFFFFFFFF)
val TextSecondary  = Color(0xB3FFFFFF)
val TextTertiary   = Color(0x80FFFFFF)
val TextQuaternary = Color(0x4DFFFFFF)

// Light-scheme ground layers, for shops that run their phone in light mode outdoors.
val Paper          = Color(0xFFF7F7F9)
val PaperCard      = Color(0xFFFFFFFF)
val PaperHairline  = Color(0xFFE3E3E8)
val TextOnPaper    = Color(0xFF0B0B0D)
val TextOnPaperDim = Color(0x99000000)
