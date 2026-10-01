package com.bukovinafilip.gradey.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hugeicons Stroke Rounded, the same ligature icon font the iOS app bundles. Glyphs are
 * selected by their kebab-case icon name (for example `"sun-03"`), see [HugeIcons].
 */
val HugeiconsFontFamily: FontFamily = FontFamily(Font(R.font.hgi_stroke_rounded))

/**
 * Gradey's app-wide icon face. Mirrors `GradelyIcon` on iOS: a fixed [size] square whose glyph is
 * rendered by Hugeicons. The glyph scales with [size] only, never with the user's font scale, so
 * icons stay aligned with their 24/34/38 dp tiles at every accessibility setting.
 */
@Composable
fun GradeyIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    val semantics = if (contentDescription == null) Modifier.clearAndSetSemantics { }
    else Modifier.semantics { this.contentDescription = contentDescription; role = Role.Image }
    Box(modifier.size(size).then(semantics), contentAlignment = Alignment.Center) {
        BasicText(
            text = name,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                fontFamily = HugeiconsFontFamily,
                fontSize = fontSize,
                lineHeight = fontSize,
                color = tint,
                fontFeatureSettings = "liga",
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}

/**
 * Drop-in replacement for Material `Icon(imageVector, contentDescription, modifier, tint)`: the glyph
 * fills a fixed-size [modifier] (for example `Modifier.size(20.dp)`) and otherwise defaults to 24 dp.
 */
@Composable
fun HugeIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    BoxWithConstraints(modifier.defaultMinSize(24.dp, 24.dp), contentAlignment = Alignment.Center) {
        val size = if (constraints.hasFixedWidth && constraints.hasFixedHeight) minOf(maxWidth, maxHeight) else 24.dp
        GradeyIcon(name, size = size, tint = tint, contentDescription = contentDescription)
    }
}

/**
 * Hugeicons names used across the app. Kept in one place, like the iOS `GradelyIconCatalog`, so
 * screens cannot drift back to Material glyphs. Names must exist in the bundled font's ligature table.
 */
object HugeIcons {
    // Navigation and chrome
    const val Sun03 = "sun-03"
    const val Sun01 = "sun-01"
    const val Sunrise = "sunrise"
    const val Moon02 = "moon-02"
    const val CheckmarkBadge02 = "checkmark-badge-02"
    const val CalendarRemove02 = "calendar-remove-02"
    const val Calendar03 = "calendar-03"
    const val Calendar01 = "calendar-01"
    const val CalendarCheckIn02 = "calendar-check-in-02"
    const val Restaurant02 = "restaurant-02"
    const val Settings01 = "settings-01"
    const val Settings02 = "settings-02"
    const val Gears = "gears"
    const val Refresh04 = "refresh-04"
    const val Reload = "reload"
    const val Sparkles = "sparkles"
    const val AiMagic = "ai-magic"
    const val Cancel01 = "cancel-01"
    const val ArrowRight01 = "arrow-right-01"
    const val ArrowLeft01 = "arrow-left-01"
    const val ArrowUp01 = "arrow-up-01"
    const val ArrowDown01 = "arrow-down-01"
    const val ArrowUpRight01 = "arrow-up-right-01"
    const val ArrowDownRight01 = "arrow-down-right-01"
    const val ArrowDataTransferVertical = "arrow-data-transfer-vertical"
    const val Search01 = "search-01"
    const val MoreHorizontalCircle01 = "more-horizontal-circle-01"
    const val Add01 = "add-01"
    const val MinusSign = "minus-sign"
    const val Tick02 = "tick-02"
    const val TickDouble01 = "tick-double-01"
    const val Copy01 = "copy-01"
    const val Share01 = "share-01"
    const val Download04 = "download-04"
    const val FileExport = "file-export"
    const val Sent = "sent"
    const val Stop = "stop"
    const val PlayCircle = "play-circle"
    const val PauseCircle = "pause-circle"
    const val Forward01 = "forward-01"

    // Status and feedback
    const val CheckmarkCircle02 = "checkmark-circle-02"
    const val AlertCircle = "alert-circle"
    const val Alert02 = "alert-02"
    const val InformationCircle = "information-circle"
    const val HelpCircle = "help-circle"
    const val Circle = "circle"
    const val Clock01 = "clock-01"
    const val TimeSchedule = "time-schedule"
    const val Time04 = "time-04"
    const val Coffee02 = "coffee-02"
    const val Location01 = "location-01"
    const val Teacher = "teacher"
    const val Students = "students"
    const val UserGroup = "user-group"
    const val User = "user"
    const val UserCircle = "user-circle"
    const val UserCheck02 = "user-check-02"
    const val Building02 = "building-02"
    const val University = "university"
    const val GraduationScroll = "graduation-scroll"
    const val Book02 = "book-02"
    const val Target02 = "target-02"
    const val CheckList = "check-list"
    const val TaskDaily02 = "task-daily-02"
    const val Edit02 = "edit-02"
    const val Edit01 = "edit-01"
    const val Delete02 = "delete-02"
    const val Delete03 = "delete-03"
    const val ChartLineData01 = "chart-line-data-01"
    const val ChartDown = "chart-down"
    const val ChartUp = "chart-up"
    const val ChartAverage = "chart-average"
    const val Analytics01 = "analytics-01"
    const val Calculator = "calculator"
    const val LeftToRightListBullet = "left-to-right-list-bullet"
    const val View = "view"
    const val ViewOff = "view-off"
    const val Favourite = "favourite"
    const val Message01 = "message-01"
    const val MessageMultiple01 = "message-multiple-01"
    const val Mail01 = "mail-01"
    const val Github = "github"
    const val Apple = "apple"
    const val Cloud = "cloud"
    const val Wifi01 = "wifi-01"
    const val SmartPhone01 = "smart-phone-01"
    const val Watch01 = "watch-01"
    const val Camera01 = "camera-01"
    const val CreditCard = "credit-card"
    const val DoorOpen = "door-open"
    const val TouchInteraction01 = "touch-interaction-01"

    // Account, security and settings
    const val Link04 = "link-04"
    const val LinkCircle02 = "link-circle-02"
    const val Notification01 = "notification-01"
    const val Notification02 = "notification-02"
    const val NotificationBubble = "notification-bubble"
    const val SecurityLock = "security-lock"
    const val SecurityCheck = "security-check"
    const val SecurityBlock = "security-block"
    const val LockKey = "lock-key"
    const val Key01 = "key-01"
    const val Logout01 = "logout-01"
    const val LanguageSkill = "language-skill"
    const val Translate = "translate"

    const val ArrowRight02 = "arrow-right-02"
    const val MoreVertical = "more-vertical"
    const val MoreVerticalCircle01 = "more-vertical-circle-01"
    const val Lock = "lock"
    const val SquareLock02 = "square-lock-02"
    const val SquareUnlock02 = "square-unlock-02"
    const val CloudDownload = "cloud-download"
    const val CloudOff = "cloud-off"
    const val WifiOff01 = "wifi-off-01"
    const val Clock04 = "clock-04"
    const val WorkHistory = "work-history"
    const val Search02 = "search-02"
    const val Filter = "filter"
    const val CalendarAdd01 = "calendar-add-01"
    const val Calendar02 = "calendar-02"
    const val Calendar04 = "calendar-04"
    const val ChartIncrease = "chart-increase"
    const val ChartDecrease = "chart-decrease"
    const val EqualSign = "equal-sign"
    const val MinusSignCircle = "minus-sign-circle"
    const val AddCircle = "add-circle"
    const val CancelCircle = "cancel-circle"
    const val CheckmarkSquare02 = "checkmark-square-02"
    const val Note01 = "note-01"
    const val Task01 = "task-01"
    const val Presentation01 = "presentation-01"
    const val BookOpen01 = "book-open-01"
    const val PencilEdit01 = "pencil-edit-01"
    const val Star = "star"
    const val Coins01 = "coins-01"
    const val Gift = "gift"
    const val Shield01 = "shield-01"
    const val UserAdd01 = "user-add-01"
    const val Login01 = "login-01"
    const val ChatBot = "chat-bot"
    const val AiChat02 = "ai-chat-02"
    const val MagicWand01 = "magic-wand-01"
    const val Brain02 = "brain-02"
    const val School = "school"
    const val School01 = "school-01"
    const val SchoolBell01 = "school-bell-01"
    const val Office = "office"
    const val Book01 = "book-01"
    const val Google = "google"
    const val Globe02 = "globe-02"
    const val LanguageCircle = "language-circle"
    const val Time01 = "time-01"
    const val Location04 = "location-04"
    const val Door01 = "door-01"
    const val SpoonAndFork = "spoon-and-fork"
    const val Restaurant01 = "restaurant-01"
    const val ArrowExpand01 = "arrow-expand-01"
    const val ArrowReloadHorizontal = "arrow-reload-horizontal"
    const val Sleeping = "sleeping"
    const val Happy = "happy"

    /** Digit-in-circle glyphs used for step indicators (`1-circle` … `9-circle`). */
    fun numberCircle(step: Int): String = "${step.coerceIn(0, 9)}-circle"
}
