package com.hyundaiproject.messaging

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.PointerIcon
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hyundaiproject.messaging.MessageCatalog.Page
import com.hyundaiproject.messaging.MessageCatalog.Preset
import com.hyundaiproject.messaging.MessageGateway.Incoming
import com.hyundaiproject.messaging.MessagingController.Phase
import com.hyundaiproject.messaging.MessagingController.Screen
import kotlin.math.min
import kotlin.math.roundToInt

private const val REGULAR = 400
private const val MEDIUM = 500
private const val SEMI_BOLD = 600
private const val BOLD = 700
private const val EXTRA_BOLD = 800

internal class MessageBoard(context: Context) : FrameLayout(context) {
    var onSelect: (Preset) -> Unit = {}
    var onPage: (Page) -> Unit = {}
    var onConfirm: () -> Unit = {}
    var onCancel: () -> Unit = {}
    var onDismissAlert: () -> Unit = {}
    var onDemo: (View) -> Unit = {}

    private val font = resources.getFont(R.font.asta_sans)
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private var screen: Screen? = null
    private var spinner: ObjectAnimator? = null
    private var scale = 1f
    private val navy = Color.rgb(0, 44, 95)
    private val red = Color.rgb(230, 51, 18)
    private val ink = Color.rgb(18, 20, 23)
    private val paper = Color.rgb(247, 248, 250)
    private val muted = Color.rgb(217, 217, 217)
    private val hairline = 0x33131417
    private val white = Color.WHITE
    private val highlightStates = arrayOf(
        intArrayOf(android.R.attr.state_hovered),
        intArrayOf(android.R.attr.state_focused),
    )
    private val messageTextColors = ColorStateList(
        arrayOf(*highlightStates, intArrayOf()), intArrayOf(navy, navy, ink),
    )
    private val messagePressRipple = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(0x26002C5F, Color.TRANSPARENT),
    )

    fun render(next: Screen) {
        screen = next
        if (width > 0 && height > 0) drawScreen(next)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) post { screen?.let(::render) }
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        super.onDetachedFromWindow()
    }

    private fun drawScreen(state: Screen) {
        spinner?.cancel()
        spinner = null
        removeAllViews()
        val w = width.toFloat()
        val h = height.toFloat()
        val portrait = w < h * 0.95f
        scale = min(w / (if (portrait) 1100f else 1700f), h / 1194f)
        background = rounded(paper, u(40), hairline)
        clipToOutline = true

        val list = layer(this)
        if (state.page() == Page.HOME) {
            drawHome(list, portrait, w, h)
        } else {
            drawList(list, state, portrait, w, h)
        }
        val sendPanel = if (state.phase() != Phase.IDLE) {
            dim(list)
            val panel = layer(this)
            when (state.phase()) {
                Phase.CONFIRM, Phase.RETRY -> confirmation(panel, state, w, h)
                Phase.SENDING -> sending(panel, state, w, h)
                Phase.SENT, Phase.FAILED -> sendStatus(panel, state.phase() == Phase.SENT, w, h)
                Phase.IDLE -> Unit
            }
            panel
        } else null

        state.voiceText()?.let { text -> voiceIndicator(this, text, state.phase() == Phase.FAILED, w) }
        val incoming = state.alert()
        if (incoming != null) {
            dim(sendPanel ?: list)
            alert(layer(this), incoming, state.alternateGlow(), w, h)
        } else {
            val demo = label("시연 ⋮", u(32).coerceAtLeast(sp(12f)), navy, weight = BOLD)
            demo.background = rounded(0xEDF7F8FA.toInt(), u(36), hairline)
            action(demo, "시연 메뉴") { onDemo(demo) }
            val demoW = u(174).coerceAtLeast(dp(76f))
            val demoH = u(68).coerceAtLeast(dp(48f))
            val demoInset = if (state.page() == Page.HOME) {
                w * (49f / 1700f) + u(50) + u(48).coerceAtLeast(dp(22f)) + dp(8f)
            } else u(38)
            place(this, demo, w - demoW - demoInset, u(23), demoW, demoH)
        }
    }

    private fun drawHome(parent: FrameLayout, portrait: Boolean, w: Float, h: Float) {
        val left = w * (49f / 1700f)
        val top = h * (203f / 1194f)
        val headerInset = left + u(50)
        val logoWidth = u(300).coerceAtLeast(dp(152f)).coerceAtMost(w * 0.58f)
        val logoHeight = u(57).coerceAtLeast(dp(24f))
        val logoPlaceholder = label("로고 이미지 파일 들어갈 자리입니다", u(20).coerceAtLeast(sp(9f)),
            Color.BLACK, Gravity.START or Gravity.CENTER_VERTICAL, REGULAR).apply {
            background = ColorDrawable(muted)
            val textWidth = paint.measureText(text.toString())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * min(1f, logoWidth / textWidth))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        place(parent, logoPlaceholder, headerInset, h * (103.45f / 1194f), logoWidth, logoHeight)
        val exitIconSize = u(48).coerceAtLeast(dp(22f))
        place(parent, bitmap("exit-to-app"), w - headerInset - exitIconSize,
            h * (107.95f / 1194f), exitIconSize, exitIconSize)

        val cards = LinearLayout(context).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        val gap = u(25).roundToInt()
        val entries = listOf(
            "principal\nmessages" to Page.MAIN,
            // The second home card opens the turn-signal list in the prototype.
            "additional\nmessages" to Page.TURN,
            "설정" to null,
        )
        entries.forEachIndexed { index, (text, destination) ->
            val params = if (portrait) {
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            }
            if (index > 0) {
                if (portrait) params.topMargin = gap else params.leftMargin = gap
            }
            cards.addView(homeTile(text, destination, portrait), params)
        }
        place(parent, cards, left, top, w - 2 * left, h * (930f / 1194f))
    }

    private fun homeTile(text: String, destination: Page?, portrait: Boolean): View {
        val tile = FrameLayout(context)
        val active = destination != null
        val primary = destination == Page.MAIN
        val surface = rounded(if (active) navy else muted, u(32), hairline)
        tile.background = if (active) RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), surface, null) else surface
        val titleGravity = Gravity.START or (if (portrait) Gravity.CENTER_VERTICAL else Gravity.TOP)
        val title = label(text, u(48).coerceAtLeast(sp(14f)), if (active) white else Color.BLACK, titleGravity, BOLD)
        title.maxLines = 2
        val arrow = if (active) shadedLabel("→", u(128)) else label("→", u(128), Color.BLACK, weight = BOLD)
        arrow.gravity = Gravity.CENTER

        val titleHeight = if (portrait) LayoutParams.MATCH_PARENT else u(140).roundToInt()
        tile.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, titleHeight).apply {
            leftMargin = u(if (primary) 50 else 57).roundToInt()
            rightMargin = u(if (portrait) 210 else 36).roundToInt()
            if (!portrait) topMargin = u(if (primary) 72 else 61).roundToInt()
        })
        val arrowGravity = Gravity.END or (if (portrait) Gravity.CENTER_VERTICAL else Gravity.BOTTOM)
        tile.addView(arrow, LayoutParams(u(140).roundToInt(), u(160).roundToInt(), arrowGravity).apply {
            rightMargin = u(35).roundToInt()
            if (!portrait) bottomMargin = u(20).roundToInt()
        })
        if (destination != null) {
            action(tile, text.replace('\n', ' ')) { onPage(destination) }
        } else {
            title.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        return tile
    }

    private fun drawList(parent: FrameLayout, state: Screen, portrait: Boolean, w: Float, h: Float) {
        val page = state.page()
        val turn = page == Page.TURN
        val extra = page == Page.ADDITIONAL
        val presets = MessageCatalog.presets(page)
        val tileCount = if (turn) 3 else 6
        val columns = if (portrait) 2 else 3
        val left = w * if (portrait) 0.065f else 0.0642f
        val columnGap = w * if (portrait) 0.024f else 0.0211f
        val rowGap = h * if (portrait) 0.026f else 0.0422f
        val tileW = (w - 2 * left - (columns - 1) * columnGap) / columns
        val tileH = h * if (portrait && !turn) 0.234f else 0.325f
        val top = h * when {
            turn && portrait -> 0.215f
            turn -> 0.369f
            else -> 0.153f
        }
        val title = label(
            if (turn) "좌회전 방향지시등 작동 중" else "비상등 작동 중",
            u(40).coerceAtLeast(sp(14f)), ink, Gravity.START or Gravity.CENTER_VERTICAL,
            if (turn) EXTRA_BOLD else BOLD,
        )
        title.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        place(parent, title, left, top - u(81), w - 2 * left, u(66))

        for (index in 0 until tileCount) {
            val x = left + (index % columns) * (tileW + columnGap)
            val y = top + (index / columns) * (tileH + rowGap)
            val navigation = (!turn && !extra && index == tileCount - 1) || (extra && index == 0)
            val tile = if (navigation) navTile(extra, tileW, tileH) else {
                val preset = presets[index - if (extra) 1 else 0]
                val recognized = state.phase() == Phase.IDLE && state.voiceText() != null && state.selected() == preset
                messageTile(preset, tileW, tileH, recognized, page)
            }
            place(parent, tile, x, y, tileW, tileH)
        }
    }

    private fun messageTile(preset: Preset, w: Float, h: Float, recognized: Boolean, page: Page): View {
        val tile = FrameLayout(context)
        tile.clipChildren = false
        tile.pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
        tile.elevation = u(7)
        val showVoiceArtwork = recognized && page == Page.MAIN && preset.number() == 1 && w / h in 1.14f..1.35f
        if (showVoiceArtwork) {
            val cardScale = w / 470f
            val assetW = 550.28f * cardScale
            val assetH = 463.046f * cardScale
            // The Figma card expands leftward and the exported shadow has extra padding below it.
            place(tile, bitmap("selected-card"), (w - assetW) / 2f - 9f * cardScale,
                (h - assetH) / 2f + 5f * cardScale, assetW, assetH)
        } else {
            val surface = highlightedTileSurface(tile, if (recognized) navy else hairline)
            tile.background = RippleDrawable(messagePressRipple, surface, null)
            val number = if (recognized) label(preset.number().toString(), u(128), navy, Gravity.START, BOLD)
                else shadedLabel(preset.number().toString(), u(128), highlightColor = navy)
            place(tile, number, u(44), u(26), w - u(70), u(164))
            val text = label(preset.text(), u(48).coerceAtLeast(sp(14f)), ink, weight = BOLD)
            text.setTextColor(messageTextColors)
            text.isDuplicateParentStateEnabled = true
            placeCardCaption(tile, text, w, h)
        }
        action(tile, "${preset.number()}번 ${preset.text()}") { onSelect(preset) }
        return tile
    }

    private fun highlightedTileSurface(tile: View, border: Int): StateListDrawable {
        val surface = StateListDrawable()
        val shadow = StateListAnimator()
        fun animateHighlight(z: Float, scale: Float): ObjectAnimator = ObjectAnimator.ofPropertyValuesHolder(
            tile,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Z, z),
            PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, scale),
        ).setDuration(100)
        for (state in highlightStates) {
            surface.addState(state, rounded(white, u(32), navy))
            shadow.addState(state, animateHighlight(u(16), 500f / 470f))
        }
        surface.addState(intArrayOf(), cardSurface(border))
        shadow.addState(intArrayOf(), animateHighlight(0f, 1f))
        tile.outlineAmbientShadowColor = navy
        tile.outlineSpotShadowColor = navy
        tile.stateListAnimator = shadow
        return surface
    }

    private fun navTile(back: Boolean, w: Float, h: Float): View {
        val tile = FrameLayout(context)
        tile.background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), rounded(navy, u(32)), null)
        tile.elevation = u(7)
        if (back) {
            val text = label("이전", u(48).coerceAtLeast(sp(14f)), white, weight = BOLD)
            placeCardCaption(tile, text, w, h)
        } else {
            val text = label("additional\nmessages", u(48), white, Gravity.START or Gravity.TOP, BOLD)
            text.maxLines = 2
            place(tile, text, u(53), u(51), w - u(106), u(114))
        }
        val arrow = shadedLabel(if (back) "←" else "→", u(128))
        arrow.gravity = if (back) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.CENTER_VERTICAL
        place(tile, arrow, u(46), if (back) u(17) else h - u(167), w - u(92), u(150))
        action(tile, if (back) "이전 메시지 목록" else "추가 메시지") { onPage(if (back) Page.MAIN else Page.ADDITIONAL) }
        return tile
    }

    private fun placeCardCaption(tile: FrameLayout, text: TextView, w: Float, h: Float) {
        text.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        place(tile, text, u(30), h - u(116), w - u(70), u(86))
    }

    private fun confirmation(parent: FrameLayout, state: Screen, w: Float, h: Float) {
        val card = dialog(parent, w, h)
        val cardW = u(1256).coerceAtMost(w * 0.91f)
        val cardH = u(828)
        if (state.phase() == Phase.RETRY) {
            place(card, label("재전송", u(48), ink, weight = SEMI_BOLD), 0f, u(42), cardW, u(76))
        }
        val message = label(state.selected().text(), u(64), ink, weight = SEMI_BOLD)
        message.maxLines = 2
        message.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        place(card, message, u(48), u(270), cardW - u(96), u(198))
        val gap = u(30)
        val pad = u(40)
        val buttonW = (cardW - 2 * pad - gap) / 2f
        val buttonH = u(140).coerceAtLeast(dp(48f)).coerceAtMost(cardH * 0.33f)
        val buttonY = cardH - buttonH - u(40)
        place(card, dialogButton("확인", navy, white, onConfirm), pad, buttonY, buttonW, buttonH)
        place(card, dialogButton("취소", 0xFFD9D9D9.toInt(), ink, onCancel), pad + buttonW + gap, buttonY, buttonW, buttonH)
    }

    private fun sending(parent: FrameLayout, state: Screen, w: Float, h: Float) {
        val card = dialog(parent, w, h)
        card.contentDescription = "${state.selected().text()} 전송 중"
        card.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        val cardW = u(1256).coerceAtMost(w * 0.91f)
        val turn = state.page() == Page.TURN
        val size = u(if (turn) 262 else 154)
        val loader = bitmap(if (turn) "spinner-flow2" else "spinner-flow1")
        place(card, loader, (cardW - size) / 2f, u(if (turn) 250 else 233), size, size)
        spinner = ObjectAnimator.ofFloat(loader, View.ROTATION, 0f, 360f).apply {
            duration = 950L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        val message = label(state.selected().text(), u(64), ink, weight = if (turn) MEDIUM else SEMI_BOLD)
        message.maxLines = 2
        place(card, message, u(48), u(if (turn) 518 else 445), cardW - u(96), u(158))
    }

    private fun dialog(parent: FrameLayout, w: Float, h: Float): FrameLayout {
        val card = FrameLayout(context)
        card.background = rounded(white, u(32), hairline)
        card.elevation = u(24)
        val cardW = u(1256).coerceAtMost(w * 0.91f)
        val cardH = u(828)
        place(parent, card, (w - cardW) / 2f, (h - cardH) / 2f, cardW, cardH)
        return card
    }

    private fun sendStatus(parent: FrameLayout, success: Boolean, w: Float, h: Float) {
        val pillW = u(744).coerceAtMost(w * 0.88f)
        val pillH = u(188)
        glow(parent, (w - pillW) / 2, (h - pillH) / 2, pillW, pillH, u(100), u(52), white)
        val status = label(if (success) "전송 완료" else "전송 실패", u(64), white, weight = BOLD)
        status.background = rounded(if (success) navy else red, u(100))
        status.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        status.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        place(parent, status, (w - pillW) / 2, (h - pillH) / 2, pillW, pillH)
    }

    private fun voiceIndicator(parent: FrameLayout, text: String, retry: Boolean, w: Float) {
        val glowW = u(901)
        place(parent, bitmap(if (retry) "retry-voice-glow" else "voice-glow"),
            (w - glowW) / 2f, -u(168), glowW, u(347))
        val pillW = u(310).coerceAtLeast(dp(120f)).coerceAtMost(w * 0.68f)
        val pillH = u(71).coerceAtLeast(dp(28f))
        val pill = label(text, u(32).coerceAtLeast(sp(12f)), white, weight = SEMI_BOLD)
        pill.background = rounded(0xFF5CBCD9.toInt(), pillH / 2f)
        pill.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        pill.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        place(parent, pill, (w - pillW) / 2, u(35), pillW, pillH)
    }

    private fun alert(parent: FrameLayout, message: Incoming, alternateGlow: Boolean, w: Float, h: Float) {
        if (message.kind() == Incoming.Kind.CRITICAL) {
            val glowW = u(901)
            place(parent, bitmap(if (alternateGlow) "critical-glow-second" else "critical-glow"),
                (w - glowW) / 2f, -u(121), glowW, u(347))
            val bandH = u(247)
            glow(parent, 0f, (h - bandH) / 2, w, bandH, 0f, u(32), if (alternateGlow) red else white)
            val band = label(message.text(), u(64), white, weight = SEMI_BOLD)
            band.maxLines = 2
            band.background = rounded(red, 0f)
            band.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            action(band, "${message.text()}, 경고 닫기", onDismissAlert)
            place(parent, band, 0f, (h - bandH) / 2, w, bandH)
        } else {
            val card = FrameLayout(context)
            val cardW = u(1012).coerceAtMost(w * 0.92f)
            val cardH = u(377)
            card.background = rounded(white, u(32), hairline)
            card.elevation = u(18)
            card.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            val sender = label(message.maskedSender().orEmpty(), u(48), navy, weight = SEMI_BOLD)
            val text = label(message.text(), u(64), navy, weight = SEMI_BOLD)
            text.maxLines = 2
            text.background = rounded(white, u(32), hairline)
            place(card, sender, 0f, u(17), cardW, u(106))
            place(card, text, u(37), u(162), cardW - u(74), u(179))
            action(card, "${message.maskedSender().orEmpty()} ${message.text()}, 알림 닫기", onDismissAlert)
            place(parent, card, (w - cardW) / 2, (h - cardH) / 2 - u(15), cardW, cardH)
        }
    }

    private fun dialogButton(text: String, backgroundColor: Int, color: Int, callback: () -> Unit): TextView {
        val button = label(text, u(48).coerceAtLeast(sp(14f)), color, weight = BOLD)
        button.background = RippleDrawable(ColorStateList.valueOf(0x25000000), rounded(backgroundColor, u(22)), null)
        action(button, text, callback)
        return button
    }

    private fun dim(view: FrameLayout) {
        view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = u(12).coerceAtLeast(1f)
            view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        val scrim = View(context)
        scrim.setBackgroundColor(0x1A000000)
        scrim.isClickable = true
        scrim.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun glow(parent: FrameLayout, x: Float, y: Float, w: Float, h: Float, radius: Float, blur: Float, color: Int) {
        val effect = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                maskFilter = BlurMaskFilter(blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            }
            override fun onDraw(canvas: Canvas) {
                canvas.drawRoundRect(2 * blur, 2 * blur, width - 2 * blur, height - 2 * blur, radius, radius, paint)
            }
        }
        effect.setLayerType(LAYER_TYPE_SOFTWARE, null)
        place(parent, effect, x - 2 * blur, y - 2 * blur, w + 4 * blur, h + 4 * blur)
    }

    private fun cardSurface(stroke: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFF5F6F8.toInt(), 0xFFFAFAFB.toInt()),
    ).apply {
        cornerRadius = u(32)
        setStroke(1, stroke)
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        stroke?.let { setStroke(1, it) }
    }

    private fun label(text: String, size: Float, color: Int, gravity: Int = Gravity.CENTER, weight: Int) =
        TextView(context).apply { style(this, text, size, color, gravity, weight) }

    private fun shadedLabel(text: String, size: Float, highlightColor: Int? = null) = object : TextView(context) {
        private val shading = LinearGradient(0f, 0f, size * 0.85f, size * 0.9f,
            intArrayOf(0xFF8E9397.toInt(), 0xFFEEF0F1.toInt(), 0xFFAAAFB2.toInt()),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            if (highlightColor != null) invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val highlighted = highlightColor != null && highlightStates.any { it[0] in drawableState }
            paint.shader = if (highlighted) null else shading
            super.onDraw(canvas)
        }
    }.apply {
        style(this, text, size, highlightColor ?: white, Gravity.START or Gravity.CENTER_VERTICAL, BOLD)
        if (highlightColor != null) isDuplicateParentStateEnabled = true
    }

    private fun style(view: TextView, value: String, size: Float, color: Int, gravity: Int, weight: Int) {
        view.text = value
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size.coerceAtLeast(1f))
        view.setTextColor(color)
        view.gravity = gravity
        view.typeface = font
        view.setFontVariationSettings("'wght' $weight")
        view.letterSpacing = 0f
        view.includeFontPadding = false
        view.ellipsize = TextUtils.TruncateAt.END
        view.maxLines = 1
        view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun bitmap(name: String) = ImageView(context).apply {
        val image = bitmaps.getOrPut(name) {
            context.assets.open("$name.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
        }
        setImageBitmap(image)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun action(view: View, description: String, callback: () -> Unit) {
        view.isFocusable = true
        view.contentDescription = description
        view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        view.setOnClickListener { callback() }
        view.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
    }

    private fun layer(parent: FrameLayout) = FrameLayout(context).also {
        it.clipChildren = false
        parent.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun place(parent: FrameLayout, child: View, x: Float, y: Float, w: Float, h: Float) {
        parent.addView(child, LayoutParams(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)).apply {
            leftMargin = x.roundToInt()
            topMargin = y.roundToInt()
        })
    }

    private fun u(value: Int) = value * scale
    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
