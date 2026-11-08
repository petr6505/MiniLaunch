package dev.minilaunch

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Small view/dialog toolkit. Everything is monospace, everything is plain framework views. */
object Ui {
    const val FG = 0xFFEEEEEE.toInt()
    const val DIM = 0xFF888888.toInt()
    const val LINE = 0xFF333333.toInt()
    const val BG = 0xFF111111.toInt()

    fun dp(ctx: Context, v: Number): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun tv(ctx: Context, text: CharSequence = "", size: Float = 16f, color: Int = FG, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            includeFontPadding = false
        }

    fun ripple(color: Int = 0x33FFFFFF) = RippleDrawable(ColorStateList.valueOf(color), null,
        GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 14f })

    /** Subtly rounded outline box (used for previews). */
    fun box(ctx: Context, stroke: Int = LINE, fill: Int = 0): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(ctx, 6).toFloat(); setColor(fill); setStroke(dp(ctx, 1), stroke)
    }

    fun edit(ctx: Context, initial: String = "", hint: String = ""): EditText = EditText(ctx).apply {
        setText(initial)
        this.hint = hint
        typeface = Typeface.MONOSPACE
        setTextColor(FG)
        setHintTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isSingleLine = true
        backgroundTintList = ColorStateList.valueOf(DIM)
        setSelection(initial.length)
    }

    fun button(ctx: Context, label: String, onClick: () -> Unit): TextView =
        tv(ctx, "[ $label ]", 15f, FG).apply {
            val p = dp(ctx, 10)
            setPadding(p, p, p, p)
            background = ripple()
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------------ dialogs

    class Sheet(val activity: Activity, title: String?) {
        val dialog = Dialog(activity, R.style.Theme_Mini_Dialog)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        private val buttons = LinearLayout(activity).apply { gravity = Gravity.END }
        private val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(activity, 20)
            setPadding(p, p, p, dp(activity, 8))
        }

        init {
            if (title != null) root.addView(tv(activity, title, 18f, FG, bold = true).apply {
                setPadding(0, 0, 0, dp(activity, 14))
            })
        }

        fun scrollBody(): Sheet = also { scroll = true }
        private var scroll = false

        fun button(label: String, dismiss: Boolean = true, onClick: () -> Unit = {}): Sheet = also {
            buttons.addView(button(activity, label) { onClick(); if (dismiss) dialog.dismiss() })
        }

        fun show(): Dialog {
            if (scroll) root.addView(ScrollView(activity).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            else root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            if (buttons.childCount > 0) root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            dialog.setContentView(root)
            dialog.setCanceledOnTouchOutside(true)
            // Exact width (not MATCH_PARENT + inset) so the margin around the box counts as "outside".
            val w = activity.resources.displayMetrics.widthPixels - 2 * dp(activity, 18)
            dialog.window?.setLayout(w, WRAP_CONTENT)
            if (!activity.isFinishing) dialog.show()
            return dialog
        }
    }

    fun info(a: Activity, title: String, msg: String) {
        Sheet(a, title).apply { body.addView(tv(a, msg, 14f, FG).apply { setTextIsSelectable(true) }) }
            .scrollBody().button("ok").show()
    }

    fun confirm(a: Activity, title: String, msg: String, ok: String = "ok", onOk: () -> Unit) {
        Sheet(a, title).apply { body.addView(tv(a, msg, 14f, FG)) }
            .scrollBody().button("cancel").button(ok, onClick = onOk).show()
    }

    /** A list of tappable rows. */
    fun menu(a: Activity, title: String?, items: List<Pair<String, () -> Unit>>) {
        val s = Sheet(a, title)
        for ((label, fn) in items) {
            s.body.addView(row(a, label) { s.dialog.dismiss(); fn() })
        }
        s.scrollBody().show()
    }

    fun row(ctx: Context, label: String, sub: String? = null, onClick: (() -> Unit)?): View {
        val l = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 12)
            setPadding(0, p, 0, p)
            if (onClick != null) { background = ripple(); setOnClickListener { onClick() } }
        }
        l.addView(tv(ctx, label, 16f, FG))
        if (sub != null) l.addView(tv(ctx, sub, 13f, DIM).apply { setPadding(0, dp(ctx, 4), 0, 0) })
        return l
    }

    fun choice(a: Activity, title: String, items: List<String>, checked: Int, onPick: (Int) -> Unit) {
        val s = Sheet(a, title)
        items.forEachIndexed { i, label ->
            s.body.addView(row(a, (if (i == checked) "(•) " else "( ) ") + label) { s.dialog.dismiss(); onPick(i) })
        }
        s.scrollBody().show()
    }

    fun input(a: Activity, title: String, initial: String, hint: String = "", numeric: Boolean = false,
              multiline: Boolean = false, onOk: (String) -> Unit) {
        val e = edit(a, initial, hint)
        if (numeric) e.inputType = InputType.TYPE_CLASS_NUMBER
        if (multiline) { e.isSingleLine = false; e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; e.maxLines = 8 }
        val s = Sheet(a, title)
        s.body.addView(e)
        e.imeOptions = EditorInfo.IME_ACTION_DONE
        e.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE && !multiline) { onOk(e.text.toString()); s.dialog.dismiss(); true } else false
        }
        s.button("cancel").button("ok") { onOk(e.text.toString()) }
        val d = s.show()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        showKeyboard(e)
    }

    /** Focus a field and raise the keyboard – waits until its window really has focus, otherwise Android ignores it. */
    fun showKeyboard(e: EditText) {
        e.requestFocus()
        val raise = {
            e.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
            e.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(e, 0)
        }
        if (e.hasWindowFocus()) { e.post { raise() }; return }
        e.viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) return
                e.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                e.post { raise() }
            }
        })
    }

    fun slider(a: Activity, title: String, min: Int, max: Int, value: Int, suffix: String = "",
               onLive: ((Int) -> Unit)? = null, fmt: ((Int) -> String)? = null, onOk: (Int) -> Unit) {
        val s = Sheet(a, title)
        val fmtV: (Int) -> String = fmt ?: { "$it$suffix" }
        val label = tv(a, fmtV(value), 22f, FG, bold = true).apply { gravity = Gravity.CENTER }
        val bar = SeekBar(a).apply {
            this.max = max - min
            progress = value - min
            progressTintList = ColorStateList.valueOf(FG)
            thumbTintList = ColorStateList.valueOf(FG)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                    label.text = fmtV(p + min); onLive?.invoke(p + min)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val minus = button(a, "-") { bar.progress = (bar.progress - 1).coerceAtLeast(0) }
        val plus = button(a, "+") { bar.progress = (bar.progress + 1).coerceAtMost(bar.max) }
        val rowL = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(minus); addView(bar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(plus)
        }
        s.body.addView(label)
        s.body.addView(rowL)
        s.button("cancel") { onLive?.invoke(value) }.button("ok") { onOk(bar.progress + min) }
        s.show()
    }

    val colorPresets = listOf(
        "white" to 0xFFFFFFFF.toInt(), "snow" to 0xFFE8E8E8.toInt(), "grey" to 0xFFAAAAAA.toInt(),
        "dim" to 0xFF666666.toInt(), "black" to 0xFF000000.toInt(), "amber" to 0xFFFFB000.toInt(),
        "phosphor" to 0xFF33FF66.toInt(), "ice" to 0xFF9FD8FF.toInt(), "rose" to 0xFFFF8FA3.toInt(),
        "sand" to 0xFFE6D3A3.toInt(), "mint" to 0xFFA8F0C6.toInt(), "lilac" to 0xFFC9B6FF.toInt(),
    )

    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    fun color(a: Activity, title: String, current: Int, onPick: (Int) -> Unit) {
        val s = Sheet(a, title)
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        colorPresets.chunked(3).forEach { chunk ->
            val r = LinearLayout(a)
            chunk.forEach { (name, c) ->
                val sw = tv(a, (if (c == current) "■ " else "□ ") + name, 14f, if (c == 0xFF000000.toInt()) DIM else c).apply {
                    val p = dp(a, 10); setPadding(p, p, p, p); background = ripple()
                    setOnClickListener { s.dialog.dismiss(); onPick(c) }
                }
                r.addView(sw, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            grid.addView(r)
        }
        s.body.addView(grid)
        val e = edit(a, hex(current), "#RRGGBB")
        s.body.addView(tv(a, "custom hex", 13f, DIM).apply { setPadding(0, dp(a, 12), 0, 0) })
        s.body.addView(e)
        s.button("cancel").button("use hex") {
            parseColor(e.text.toString())?.let(onPick)
        }
        s.scrollBody().show()
    }

    fun parseColor(s: String): Int? = runCatching {
        var h = s.trim().removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length == 6) h = "FF$h"
        h.toLong(16).toInt()
    }.getOrNull()

    /** Searchable app picker. */
    fun pickApp(a: Activity, title: String, includeHidden: Boolean = true, onPick: (AppEntry) -> Unit) {
        val s = Sheet(a, title)
        val search = edit(a, "", "search…")
        val list = ListView(a).apply { divider = null; isVerticalScrollBarEnabled = false }
        var items = Apps.search("", includeHidden, true)
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
                val t = (v as? TextView) ?: tv(a, "", 16f).apply {
                    val pad = dp(a, 11); setPadding(0, pad, 0, pad)
                }
                val app = items[p]
                t.text = app.label + (if (app.work) "  [work]" else "") + (if (app.key in Prefs.hidden) "  [hidden]" else "")
                return t
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ -> s.dialog.dismiss(); onPick(items[p]) }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { items = Apps.search(e.toString(), includeHidden, true); adapter.notifyDataSetChanged() }
            override fun beforeTextChanged(c: CharSequence?, s: Int, b: Int, n: Int) {}
            override fun onTextChanged(c: CharSequence?, s: Int, b: Int, n: Int) {}
        })
        s.body.addView(search)
        s.body.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, a.resources.displayMetrics.heightPixels / 2))
        s.button("cancel")
        s.show()
    }

    /** Pick a gesture action (built-in or app). */
    fun pickAction(a: Activity, title: String, current: String, onPick: (String) -> Unit) {
        val opts = Actions.builtIn + ("app" to "open an app…")
        val labels = opts.map { it.second }
        val checked = if (current.startsWith("app:")) opts.size - 1 else opts.indexOfFirst { it.first == current }
        choice(a, title, labels, checked) { i ->
            if (opts[i].first == "app") pickApp(a, "open which app?") { onPick("app:" + it.key) }
            else onPick(opts[i].first)
        }
    }

    fun frame(ctx: Context) = FrameLayout(ctx)
}
