package dev.roc.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Applies the engine's tree to native Views, one View per `Ui` element
 * (platform/Ui.roc): `ui-column`/`ui-list` a vertical LinearLayout, `ui-row`
 * a horizontal one, `ui-title`/`ui-text` a TextView holding their whole
 * text, `ui-button` a Button, `input` an EditText or CheckBox. Plain text in
 * a layout is a TextView. Only the elements a batch marked dirty are
 * refreshed; views are kept by node, so a turn never rebuilds the screen.
 */
class ViewRenderer(private val context: Context, private val host: () -> Host) : Renderer {
    private val views = HashMap<Node, View>()
    /** Set while the renderer writes a control, so its listener stays quiet. */
    private var writing = false

    private val textTags = setOf("ui-title", "ui-text", "ui-button", "ui-link") // ui-link: navigation

    fun rootView(tree: Tree): View = viewFor(tree.root)!!

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

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

    private fun create(n: Node): View = when {
        n is TextNode -> TextView(context).apply { textSize = 16f }
        n !is Element -> error("unreachable")
        n.tag == "ui-button" -> Button(context).apply {
            isAllCaps = false
            setOnClickListener { click(n) }
        }
        n.tag == "input" && n.attr("type") == "checkbox" -> CheckBox(context).apply {
            setOnCheckedChangeListener { _, on -> if (!writing) host().dispatch(n, "change", on.toString()) }
        }
        n.tag == "input" -> EditText(context).apply {
            minWidth = dp(200)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!writing) host().dispatch(n, "input", s.toString())
                }
            })
            setOnEditorActionListener { _, _, _ -> host().dispatch(n, "done", text.toString()) }
        }
        // Navigation (NavigationFeature.kt): a link is link-coloured, underlined
        // text; a tap is a click, whose payload carries the link's href.
        n.tag == "ui-link" -> TextView(context).apply {
            textSize = 18f
            setTextColor(Color.rgb(0x1a, 0x5f, 0xb4))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { click(n) }
        }
        n.tag in textTags -> TextView(context).apply {
            if (n.tag == "ui-title") { textSize = 28f; setTypeface(typeface, Typeface.BOLD) } else textSize = 18f
        }
        else -> LinearLayout(context).apply {
            orientation = if (n.tag == "ui-row") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (n.tag == "ui-list") {
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                dividerDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(Color.LTGRAY); setSize(dp(1), dp(1)) }
                dividerPadding = dp(4)
            }
            gravity = if (orientation == LinearLayout.HORIZONTAL) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
            if (n.parent == null) setPadding(dp(16), dp(16), dp(16), dp(16))
        }
    }

    private fun click(n: Element) {
        host().dispatch(n, "click")
    }

    override fun render(tree: Tree) {
        for (n in tree.removed) views.remove(n)
        for (e in tree.dirty) {
            // The nearest element that owns a view shows this change.
            var owner: Element? = e
            while (owner != null && !ownsView(owner)) owner = owner.parent
            if (owner != null && views.containsKey(owner)) refresh(owner)
        }
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
        if (e.attr("tone") == "warning" && v is TextView) v.setTextColor(Color.rgb(0xc0, 0x30, 0x30))
        writing = true
        try {
            when (v) {
                is CheckBox -> if (v.isChecked != e.checked) v.isChecked = e.checked
                is EditText -> if (v.text.toString() != e.value) { v.setText(e.value); v.setSelection(e.value.length) }
                is TextView -> v.text = e.textContent() // also Button
                is LinearLayout -> sync(v, e)
            }
            if (v is EditText) e.attr("hint")?.let { v.hint = it }
        } finally {
            writing = false
        }
    }

    /** Make `layout`'s children the views of `e`'s children, in order. */
    private fun sync(layout: LinearLayout, e: Element) {
        val horizontal = layout.orientation == LinearLayout.HORIZONTAL
        var i = 0
        for (child in e.children) {
            val cv = viewFor(child) ?: continue
            if (child is TextNode) refresh(child)
            if (layout.getChildAt(i) !== cv) {
                (cv.parent as? ViewGroup)?.removeView(cv)
                val lp = if (horizontal && child is TextNode)
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                layout.addView(cv, i, lp)
            }
            i++
        }
        while (layout.childCount > i) layout.removeViewAt(layout.childCount - 1)
    }
}
