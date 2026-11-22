package dev.roc.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Applies the engine's tree to native Views, one View per `Ui` element
 * (the table in platform/Ui.roc). Plain text in a layout is a TextView;
 * elements inside a text element or a button are part of its text. Only the
 * elements a batch marked dirty are refreshed; views are kept by node, so a
 * turn never rebuilds the screen.
 *
 * Sizes cross in dp, Android's CSS pixel: `pad`, `row-height`, `extent`,
 * `offset` and the scroll and size reports a `ui-scroll` sends.
 *
 * The page scrolls as a whole (a root ScrollView) until the screen holds a
 * `ui-scroll`; then that view owns scrolling, the screen fills the window
 * and a `ui-scroll` takes its column's spare height.
 *
 * To add an element: a branch in `create`, its attributes in `refresh`, and
 * a row in Ui.roc's table.
 */
class ViewRenderer(private val context: Context, private val host: () -> Host) : Renderer {
    private val views = HashMap<Node, View>()
    /** Set while the renderer writes a control, so its listener stays quiet. */
    private var writing = false
    private val density = context.resources.displayMetrics.density
    private val textTags = setOf("ui-title", "ui-text", "ui-button", "ui-link") // ui-link: navigation
    /** Elements that span a column's width. */
    private val blockTags = setOf("ui-column", "ui-row", "ui-list", "ui-card", "ui-scroll", "ui-divider", "input")

    private val screen = FrameLayout(context).apply {
        // Blur (REF 2) moves focus here rather than to the first field.
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }
    private var root: View? = null
    private var page: ScrollView? = null
    /** Live `ui-scroll` views: while there is one, the page itself does not scroll. */
    private var scrollers = 0

    /** The whole screen for `tree`, to set as the activity's content. */
    fun screen(tree: Tree): View {
        root = viewFor(tree.root)
        arrange()
        return screen
    }

    fun rootView(tree: Tree): View = viewFor(tree.root)!!

    private fun dp(v: Int) = (v * density).roundToInt()
    private fun toDp(px: Int) = (px / density).roundToInt()

    // ---- views ---------------------------------------------------------------

    /** Elements inside a text element (or a button) are part of its text. */
    private fun ownsView(n: Node): Boolean {
        if (n is Comment) return false
        var p = n.parent
        while (p != null) {
            if (p.tag in textTags) return false
            p = p.parent
        }
        return true
    }

    private fun viewFor(n: Node): View? {
        if (!ownsView(n)) return null
        views[n]?.let { return it }
        val v = create(n)
        views[n] = v
        refresh(n)
        return v
    }

    private fun create(n: Node): View {
        if (n is TextNode) return TextView(context).apply { textSize = 16f }
        val e = n as Element
        val v = when {
            e.tag == "ui-button" -> Button(context).apply {
                isAllCaps = false
                setOnClickListener { host().dispatch(e, "click") }
            }
            e.tag == "input" && e.attr("type") == "checkbox" ->
                (if (e.attr("variant") == "switch") Switch(context) else CheckBox(context)).apply {
                    setOnCheckedChangeListener { _, on -> if (!writing) host().dispatch(e, "change", on.toString()) }
                }
            e.tag == "input" && e.attr("type") == "range" -> SeekBar(context).apply {
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && !writing) host().dispatch(e, "input", progress.toString())
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) { host().dispatch(e, "change", bar.progress.toString()) }
                })
            }
            e.tag == "input" -> field(e)
            // Navigation (NavigationFeature.kt): a link is link-coloured,
            // underlined text; a tap is a click whose payload carries its href.
            e.tag == "ui-link" -> TextView(context).apply {
                setTextColor(Color.rgb(0x1a, 0x5f, 0xb4))
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            }
            e.tag in textTags -> TextView(context)
            e.tag == "ui-scroll" -> Scroller(e).also { scrollers++ }
            e.tag == "ui-spacer" -> View(context)
            e.tag == "ui-divider" -> View(context).apply { setBackgroundColor(LINE) }
            else -> Rows(context).apply {
                orientation = if (e.tag == "ui-row") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = if (orientation == LinearLayout.HORIZONTAL) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
                lines = e.tag == "ui-list"
            }
        }
        if (v.isFocusable || v is EditText) {
            if (v !is EditText) v.setOnFocusChangeListener { _, has -> if (has) host().focused(e) else host().blurred(e) }
            v.setOnKeyListener { _, code, event -> key(e, code, event) }
        }
        return v
    }

    /** A text input: its text is the element's controlled value (`Host.shown`). */
    private fun field(e: Element) = EditText(context).apply {
        minWidth = dp(200)
        isSingleLine = true
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!writing) host().typed(e, s.toString())
            }
        })
        setOnFocusChangeListener { v, has ->
            if (has) host().focused(e)
            else {
                host().blurred(e)
                showText(this, e)
                keyboard(v, false)
            }
        }
        var handled = false
        setOnEditorActionListener { _, _, event ->
            when {
                // The IME's action key: Enter for keymaps, then `done`.
                event == null -> {
                    val enter = KeyInput("Enter", "Enter")
                    val heard = host().key(e, "keydown", enter)
                    host().key(e, "keyup", enter)
                    val done = host().dispatch(e, "done", host().shown(e))
                    heard?.prevent == true || done
                }
                // A hardware Enter the keymaps let through (down, then up).
                event.action == KeyEvent.ACTION_DOWN -> { handled = host().dispatch(e, "done", host().shown(e)); handled }
                else -> handled
            }
        }
    }

    // ---- refresh ---------------------------------------------------------------

    override fun render(tree: Tree) {
        for (n in tree.removed) {
            val v = views.remove(n) ?: continue
            if (v is Scroller) scrollers--
        }
        for (e in tree.dirty) {
            // The nearest element that owns a view shows this change.
            var owner: Element? = e
            while (owner != null && !ownsView(owner)) owner = owner.parent
            if (owner != null && views.containsKey(owner)) refresh(owner)
        }
        arrange()
    }

    private fun refresh(n: Node) {
        val v = views[n] ?: return
        if (n is TextNode) {
            (v as TextView).text = n.text
            v.visibility = if (n.text.isEmpty()) View.GONE else View.VISIBLE
            return
        }
        val e = n as Element
        v.visibility = if (e.attrs.containsKey("hidden")) View.GONE else View.VISIBLE
        v.isEnabled = !e.attrs.containsKey("disabled")
        e.attr("description")?.let { v.contentDescription = it }
        writing = true
        try {
            when (v) {
                is CompoundButton -> {
                    if (v.isChecked != e.checked) v.isChecked = e.checked
                    v.text = e.attr("label") ?: ""
                }
                is EditText -> {
                    showText(v, e)
                    v.hint = e.attr("hint") ?: ""
                    val type = inputType(e.attr("type"))
                    if (v.inputType != type) v.inputType = type
                    v.imeOptions = imeAction(e.attr("action"))
                }
                is SeekBar -> {
                    val lo = e.attr("min")?.toIntOrNull() ?: 0
                    v.max = e.attr("max")?.toIntOrNull() ?: 100
                    v.min = lo
                    e.value.toIntOrNull()?.let { if (v.progress != it) v.progress = it }
                }
                is Button -> v.text = e.textContent()
                is TextView -> { v.text = e.textContent(); style(v, e) }
                is Scroller -> { sync(v.content, e); pad(v, e, 0) }
                is Rows -> {
                    sync(v, e)
                    surface(v, e)
                    val rowHeight = e.attr("row-height")?.toIntOrNull()
                    if (rowHeight != null) {
                        // A window of fixed-height rows: drawn from `offset`, in a box as tall as all rows.
                        v.minimumHeight = dp(e.attr("extent")?.toIntOrNull() ?: 0)
                        v.setPadding(0, dp(e.attr("offset")?.toIntOrNull() ?: 0), 0, 0)
                    } else pad(v, e, if (e.parent == null) 16 else if (e.tag == "ui-card") 12 else 0)
                }
            }
        } finally {
            writing = false
        }
        clickable(v, e)
        // A changed `fill` changes how the parent lays this view out.
        val parent = e.parent
        val layout = v.parent as? LinearLayout
        if (parent != null && layout != null) params(e, parent, layout.orientation == LinearLayout.HORIZONTAL).let {
            if (!sameParams(v.layoutParams, it)) v.layoutParams = it
        }
    }

    /** Show the controlled value: written only when it differs, so caret and selection stay. */
    private fun showText(v: EditText, e: Element) {
        val text = host().shown(e)
        if (v.text.toString() == text) return
        val was = writing
        writing = true
        try {
            v.setText(text)
            v.setSelection(text.length)
        } finally {
            writing = was
        }
    }

    private fun inputType(type: String?) = when (type) {
        "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
        "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        "tel" -> InputType.TYPE_CLASS_PHONE
        "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else -> InputType.TYPE_CLASS_TEXT
    }

    private fun imeAction(action: String?) = when (action) {
        "search" -> EditorInfo.IME_ACTION_SEARCH
        "go" -> EditorInfo.IME_ACTION_GO
        "next" -> EditorInfo.IME_ACTION_NEXT
        "send" -> EditorInfo.IME_ACTION_SEND
        else -> EditorInfo.IME_ACTION_DONE
    }

    /** Text attributes: `size` (small, body, large, headline), `tone`, `bold`. */
    private fun style(v: TextView, e: Element) {
        val title = e.tag == "ui-title"
        v.textSize = when (e.attr("size")) {
            "small" -> 14f
            "body" -> 18f
            "large" -> 22f
            "headline" -> 28f
            else -> if (title) 28f else 18f
        }
        v.setTypeface(Typeface.DEFAULT, if (title || e.attrs.containsKey("bold")) Typeface.BOLD else Typeface.NORMAL)
        v.setTextColor(
            when (e.attr("tone")) {
                "muted" -> Color.rgb(0x6b, 0x6b, 0x75)
                "accent" -> ACCENT
                "warning" -> Color.rgb(0xc0, 0x30, 0x30)
                "danger" -> Color.rgb(0xb0, 0x00, 0x20)
                else -> Color.rgb(0x1c, 0x1b, 0x1f)
            },
        )
    }

    private fun pad(v: View, e: Element, default: Int) {
        val p = dp(e.attr("pad")?.toIntOrNull() ?: default)
        if (v.paddingLeft != p || v.paddingTop != p) v.setPadding(p, p, p, p)
    }

    /** A card's surface, or the highlight of a `selected` element. */
    private fun surface(v: View, e: Element) {
        val selected = e.attrs.containsKey("selected")
        val card = e.tag == "ui-card"
        val key = (if (card) 1 else 0) or (if (selected) 2 else 0)
        if (v.getTag(SURFACE) == key) return
        v.setTag(SURFACE, key)
        v.background = when {
            card -> GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (selected) HIGHLIGHT else Color.rgb(0xf6, 0xf5, 0xfa))
                setStroke(dp(1), LINE)
            }
            selected -> GradientDrawable().apply { setColor(HIGHLIGHT) }
            else -> null
        }
    }

    /** A layout or text with a click handler is tappable, with the platform's ripple. */
    private fun clickable(v: View, e: Element) {
        if (v is Button || v is EditText || v is SeekBar) return
        val wants = e.handlers.containsKey("click")
        if (wants == v.hasOnClickListeners()) return
        if (wants) {
            v.setOnClickListener { host().dispatch(e, "click") }
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            v.foreground = context.getDrawable(ripple.resourceId)
        } else {
            v.setOnClickListener(null)
            v.isClickable = false
            v.foreground = null
        }
    }

    // ---- layout ----------------------------------------------------------------

    /** Make `layout`'s children the views of `e`'s children, in order. */
    private fun sync(layout: LinearLayout, e: Element) {
        val horizontal = layout.orientation == LinearLayout.HORIZONTAL
        var i = 0
        for (child in e.children) {
            val cv = viewFor(child) ?: continue
            if (child is TextNode) refresh(child)
            val lp = params(child, e, horizontal)
            if (layout.getChildAt(i) !== cv) {
                (cv.parent as? ViewGroup)?.removeView(cv)
                layout.addView(cv, i, lp)
            } else if (!sameParams(cv.layoutParams, lp)) cv.layoutParams = lp
            i++
        }
        while (layout.childCount > i) layout.removeViewAt(layout.childCount - 1)
    }

    /**
     * How a child sits in its layout. In a row, text and a `fill` child
     * share the spare width; in a column, a `fill` child or a `ui-scroll`
     * takes the spare height, and so does the app's top element, but only
     * where the column has a height to share: not on a scrolling page or
     * inside a `ui-scroll`, where it wraps its content. A fixed-height list
     * gives each row exactly `row-height`.
     */
    private fun params(child: Node, parent: Element, horizontal: Boolean): LinearLayout.LayoutParams {
        val el = child as? Element
        val tag = el?.tag
        val fills = el != null && (el.attrs.containsKey("fill") || tag == "ui-spacer" || (tag == "ui-scroll" && !horizontal) || parent.parent == null)
        val rowHeight = if (parent.tag == "ui-list") parent.attr("row-height")?.toIntOrNull() else null
        if (rowHeight != null) return LinearLayout.LayoutParams(MATCH_PARENT, dp(rowHeight))
        val lp = when {
            tag == "ui-divider" -> if (horizontal) LinearLayout.LayoutParams(dp(1), MATCH_PARENT) else LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
            horizontal -> if (child is TextNode || fills) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            else -> {
                val width = if (tag in blockTags) MATCH_PARENT else WRAP_CONTENT
                if (fills && stretches(parent)) LinearLayout.LayoutParams(width, 0, 1f) else LinearLayout.LayoutParams(width, WRAP_CONTENT)
            }
        }
        val m = dp(4)
        lp.setMargins(m, m, m, m)
        return lp
    }

    /** Whether a column has a fixed height to share: not inside a scrolling view. */
    private fun stretches(column: Element): Boolean {
        if (scrollers == 0) return false
        var p: Element? = column
        while (p != null) {
            if (p.tag == "ui-scroll") return false
            p = p.parent
        }
        return true
    }

    private fun sameParams(a: ViewGroup.LayoutParams?, b: LinearLayout.LayoutParams): Boolean =
        a is LinearLayout.LayoutParams && a.width == b.width && a.height == b.height && a.weight == b.weight &&
            a.leftMargin == b.leftMargin && a.topMargin == b.topMargin && a.rightMargin == b.rightMargin && a.bottomMargin == b.bottomMargin

    /** Put the root in a page ScrollView, or straight on the screen while a `ui-scroll` owns scrolling. */
    private fun arrange() {
        val r = root ?: return
        val pageScrolls = scrollers == 0
        if (pageScrolls == (page != null) && r.parent != null) return
        (r.parent as? ViewGroup)?.removeView(r)
        page?.let { screen.removeView(it) }
        page = null
        if (pageScrolls) {
            val p = ScrollView(context).apply { isFillViewport = true; addView(r, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
            page = p
            screen.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else screen.addView(r, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Columns stretch their children only on a screen that does not scroll.
        for ((n, v) in views) {
            val parent = n.parent ?: continue
            val layout = v.parent as? LinearLayout ?: continue
            val lp = params(n, parent, layout.orientation == LinearLayout.HORIZONTAL)
            if (!sameParams(v.layoutParams, lp)) v.layoutParams = lp
        }
    }

    // ---- keys --------------------------------------------------------------------

    /** A hardware key on a view: its keymaps decide; `prevent` keeps the view's default from running. */
    private fun key(e: Element, code: Int, event: KeyEvent): Boolean {
        val type = when (event.action) {
            KeyEvent.ACTION_DOWN -> "keydown"
            KeyEvent.ACTION_UP -> "keyup"
            else -> return false
        }
        val k = keyInput(code, event) ?: return false
        return host().key(e, type, k)?.prevent == true
    }

    /** An Android key in DOM terms (`key`, `code`); null for keys apps cannot name (Back). */
    private fun keyInput(code: Int, ev: KeyEvent): KeyInput? {
        val named = when (code) {
            KeyEvent.KEYCODE_ENTER -> "Enter" to "Enter"
            KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter" to "NumpadEnter"
            KeyEvent.KEYCODE_ESCAPE -> "Escape" to "Escape"
            KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp" to "ArrowUp"
            KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown" to "ArrowDown"
            KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft" to "ArrowLeft"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight" to "ArrowRight"
            KeyEvent.KEYCODE_TAB -> "Tab" to "Tab"
            KeyEvent.KEYCODE_DEL -> "Backspace" to "Backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "Delete" to "Delete"
            KeyEvent.KEYCODE_PAGE_UP -> "PageUp" to "PageUp"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown" to "PageDown"
            KeyEvent.KEYCODE_MOVE_HOME -> "Home" to "Home"
            KeyEvent.KEYCODE_MOVE_END -> "End" to "End"
            KeyEvent.KEYCODE_SPACE -> " " to "Space"
            else -> null
        }
        val (key, dom) = named ?: run {
            val u = ev.getUnicodeChar(ev.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv())
            if (u <= 0) return null
            val c = when (code) {
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> "Key${'A' + (code - KeyEvent.KEYCODE_A)}"
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> "Digit${code - KeyEvent.KEYCODE_0}"
                else -> ""
            }
            String(Character.toChars(u)) to c
        }
        return KeyInput(key, dom, ctrl = ev.isCtrlPressed, alt = ev.isAltPressed, shift = ev.isShiftPressed, meta = ev.isMetaPressed, repeat = ev.repeatCount > 0)
    }

    // ---- references ------------------------------------------------------------------

    override fun ref(ref: Ref) {
        val v = views[ref.node] ?: return
        when (ref.op) {
            1 -> focus(v, scroll = true)
            2 -> { v.clearFocus(); keyboard(v, false) }
            3 -> { focus(v, scroll = true); (v as? EditText)?.selectAll() }
            4 -> (v as? EditText)?.let { showText(it, ref.node as Element) }
            5 -> focus(v, scroll = false)
            // Scrolls wait for layout, so rows drawn in this batch have their place.
            6 -> v.post { scrollIntoView(v, ref.block, ref.behavior == 2) }
            7 -> v.post { scrollTo(v, ref.top, ref.behavior == 2) }
        }
    }

    private fun scrollers(v: View): List<ScrollView> {
        val out = mutableListOf<ScrollView>()
        var p = v.parent
        while (p is View) { if (p is ScrollView) out += p; p = p.parent }
        return out
    }

    private fun focus(v: View, scroll: Boolean) {
        val kept = if (scroll) emptyList() else scrollers(v).map { it to it.scrollY }
        if (v is EditText || v.isFocusable) {
            if (!v.isFocusableInTouchMode) v.isFocusableInTouchMode = true
            v.requestFocus()
        }
        for ((s, y) in kept) s.scrollTo(s.scrollX, y)
        if (v is EditText) keyboard(v, true)
    }

    private fun keyboard(v: View, show: Boolean) {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        if (show) v.post { imm.showSoftInput(v, 0) }
        // Keep the keyboard when focus moved on to another field.
        else v.post { if (v.rootView.findFocus() !is EditText) imm.hideSoftInputFromWindow(v.windowToken, 0) }
    }

    /** REF 6 in the nearest scroller: block alignment 0 Start, 1 Center, 2 End, 3 Nearest. */
    private fun scrollIntoView(v: View, block: Int, smooth: Boolean) {
        val s = scrollers(v).firstOrNull() ?: return
        var y = 0
        var x: View = v
        while (x.parent !== s) { y += x.top; x = x.parent as? View ?: return }
        val port = s.height - s.paddingTop - s.paddingBottom
        val now = s.scrollY
        val target = when (block) {
            0 -> y
            1 -> y - (port - v.height) / 2
            2 -> y + v.height - port
            else -> if (y < now) y else if (y + v.height > now + port) y + v.height - port else now
        }
        if (smooth) s.smoothScrollTo(0, target) else s.scrollTo(0, target)
    }

    /** REF 7 on a `ui-scroll`: its top edge (Keep, Start, End, or dp); left does not scroll here. */
    private fun scrollTo(v: View, top: Edge, smooth: Boolean) {
        val s = v as? ScrollView ?: return
        val end = ((s.getChildAt(0)?.height ?: 0) - (s.height - s.paddingTop - s.paddingBottom)).coerceAtLeast(0)
        val y = when (top.tag) {
            1 -> 0
            2 -> end
            3 -> dp(top.px).coerceAtMost(end)
            else -> return
        }
        if (smooth) s.smoothScrollTo(0, y) else s.scrollTo(0, y)
    }

    // ---- views of their own ------------------------------------------------------------

    /** A vertical or horizontal layout; a list draws a hairline between its rows. */
    private class Rows(context: Context) : LinearLayout(context) {
        var lines = false
        private val paint = Paint().apply { color = LINE }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (!lines) return
            for (i in 0 until childCount - 1) {
                val c = getChildAt(i)
                if (c.visibility != View.VISIBLE) continue
                canvas.drawRect(c.left.toFloat(), (c.bottom - 1).toFloat(), c.right.toFloat(), c.bottom.toFloat(), paint)
            }
        }
    }

    /**
     * `ui-scroll`: a vertical ScrollView over a column. It reports its scroll
     * position and its content box as layout reports, in dp; the host paces
     * them to one per frame.
     */
    private inner class Scroller(e: Element) : ScrollView(context) {
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        init {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            setOnScrollChangeListener { _, x, y, _, _ -> host().report(e, "scroll", Layout.scroll(toDp(y), toDp(x))) }
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, oldR, ob ->
                if (r - l == oldR - ol && b - t == ob - ot) return@addOnLayoutChangeListener
                host().report(e, "size", Layout.size(toDp(r - l - paddingLeft - paddingRight), toDp(b - t - paddingTop - paddingBottom)))
                // A recreated view starts at the top: say so, so the window follows.
                if (ol == oldR) host().report(e, "scroll", Layout.scroll(toDp(scrollY), toDp(scrollX)))
            }
        }
    }

    companion object {
        private val LINE = Color.rgb(0xdd, 0xdc, 0xe4)
        private val ACCENT = Color.rgb(0x3f, 0x51, 0xb5)
        private val HIGHLIGHT = Color.rgb(0xe6, 0xe8, 0xf7)
        private const val SURFACE = 0x7f0f0001
    }
}
