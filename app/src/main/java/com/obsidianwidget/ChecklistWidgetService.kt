package com.obsidianwidget

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.text.Html
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class ChecklistWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1
        )
        return ChecklistRemoteViewsFactory(applicationContext, widgetId)
    }
}

class ChecklistRemoteViewsFactory(
    private val context: Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private var items = listOf<VaultManager.ChecklistItem>()
    private var tapCheckboxOnly = false
    private var themeColors = VaultManager.ThemeColors(
        bg = 0xFF1A1A1E.toInt(),
        text = 0xFFE8E6E3.toInt(),
        textSecondary = 0xFF8B8B8F.toInt(),
        accent = 0xFFD97757.toInt(),
        buttonText = 0xFFFFFFFF.toInt()
    )

    companion object {
        private val BOLD_ITALIC = Regex("""\*\*\*(.+?)\*\*\*""")
        private val BOLD = Regex("""\*\*(.+?)\*\*""")
        private val ITALIC_STAR = Regex("""\*(.+?)\*""")
        private val ITALIC_UNDER = Regex("""_(.+?)_""")
        private val WIKI_LINK = Regex("""!?\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]""")
        private val MD_LINK = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
        private val BARE_URL = Regex("""(?<!["'=(])((?:https?://)?[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z]{2,})+(?:/[^\s<>"']*)?)""")

        fun extractFirstUrl(text: String): String? {
            val mdMatch = MD_LINK.find(text)
            if (mdMatch != null) {
                val url = mdMatch.groupValues[2]
                return if (url.startsWith("http")) url else "https://$url"
            }
            val bareMatch = BARE_URL.find(text)
            if (bareMatch != null) {
                val url = bareMatch.groupValues[1]
                return if (url.startsWith("http")) url else "https://$url"
            }
            return null
        }
        fun markdownToHtml(text: String, accentColor: Int): CharSequence {
            var html = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            val accentHex = String.format("#%06X", accentColor and 0xFFFFFF)
            html = WIKI_LINK.replace(html) {
                val target = it.groupValues[1].trim()
                val alias = it.groupValues[2].trim()
                val label = alias.ifEmpty { target }
                "<u><font color=\"$accentHex\">$label</font></u>"
            }
            html = MD_LINK.replace(html) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
            html = BARE_URL.replace(html) {
                val url = it.groupValues[1]
                val href = if (url.startsWith("http")) url else "https://$url"
                "<a href=\"$href\">$url</a>"
            }
            html = BOLD_ITALIC.replace(html) { "<b><i>${it.groupValues[1]}</i></b>" }
            html = BOLD.replace(html) { "<b>${it.groupValues[1]}</b>" }
            html = ITALIC_STAR.replace(html) { "<i>${it.groupValues[1]}</i>" }
            html = ITALIC_UNDER.replace(html) { "<i>${it.groupValues[1]}</i>" }
            return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        }
    }

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val vaultManager = VaultManager(context, widgetId)
        items = vaultManager.parseChecklist()
        tapCheckboxOnly = vaultManager.tapCheckboxOnly
        themeColors = vaultManager.getThemeColors()
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val item = items[position]

        if (item.propertyName != null) {
            val views = RemoteViews(context.packageName, R.layout.widget_property_item)
            views.setTextViewText(R.id.property_item_name, item.propertyName)
            views.setTextViewText(
                R.id.property_item_value,
                markdownToHtml(item.text, themeColors.accent)
            )
            views.setTextColor(R.id.property_item_name, themeColors.textSecondary)
            views.setTextColor(R.id.property_item_value, themeColors.text)
            views.setOnClickFillInIntent(R.id.property_item_root, Intent())
            return views
        }

        if (item.isHeading) {
            val views = RemoteViews(context.packageName, R.layout.widget_heading_item)
            views.setTextViewText(R.id.heading_item_content, markdownToHtml(item.text, themeColors.accent))
            views.setTextColor(R.id.heading_item_content, themeColors.text)
            val url = extractFirstUrl(item.text)
            if (url != null) {
                views.setOnClickFillInIntent(R.id.heading_item_root, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_URL, url)
                })
            } else {
                views.setOnClickFillInIntent(R.id.heading_item_root, Intent())
            }
            return views
        }

        if (item.isHorizontalRule) {
            val views = RemoteViews(context.packageName, R.layout.widget_horizontal_rule_item)
            views.setInt(R.id.horizontal_rule_line, "setColorFilter", themeColors.textSecondary)
            views.setOnClickFillInIntent(R.id.horizontal_rule_root, Intent())
            return views
        }

        if (item.isPlainText) {
            val views = RemoteViews(context.packageName, R.layout.widget_text_item)
            val displayText = if (item.isBullet) "•  ${item.text}" else item.text
            views.setTextViewText(R.id.text_item_content, markdownToHtml(displayText, themeColors.accent))
            views.setTextColor(R.id.text_item_content, themeColors.text)
            val density = context.resources.displayMetrics.density
            val indentPx = (item.indentLevel * 16 * density).toInt()
            views.setViewPadding(R.id.text_item_root, indentPx + (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            val url = extractFirstUrl(item.text)
            if (url != null) {
                views.setOnClickFillInIntent(R.id.text_item_root, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_URL, url)
                })
            } else {
                views.setOnClickFillInIntent(R.id.text_item_root, Intent())
            }
            return views
        }

        val views = RemoteViews(context.packageName, R.layout.widget_checklist_item)

        // Always set padding (reset for non-indented, indent for nested)
        val density = context.resources.displayMetrics.density
        val indentPx = (item.indentLevel * 16 * density).toInt()
        views.setViewPadding(R.id.checklist_item_root, indentPx + (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())

        // Set checkbox: circle bg tinted to accent/secondary, white checkmark overlay
        if (item.isChecked) {
            views.setImageViewResource(R.id.checklist_checkbox_bg, R.drawable.ic_checkbox_checked)
            views.setInt(R.id.checklist_checkbox_bg, "setColorFilter", themeColors.accent)
            views.setViewVisibility(R.id.checklist_checkbox_mark, android.view.View.VISIBLE)
        } else {
            views.setImageViewResource(R.id.checklist_checkbox_bg, R.drawable.ic_checkbox_unchecked)
            views.setInt(R.id.checklist_checkbox_bg, "setColorFilter", themeColors.textSecondary)
            views.setViewVisibility(R.id.checklist_checkbox_mark, android.view.View.GONE)
        }

        // Set text with strikethrough if checked
        views.setTextViewText(R.id.checklist_text, markdownToHtml(item.text, themeColors.accent))
        if (item.isChecked) {
            views.setInt(R.id.checklist_text, "setPaintFlags",
                Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
            views.setTextColor(R.id.checklist_text, themeColors.textSecondary)
        } else {
            views.setInt(R.id.checklist_text, "setPaintFlags", Paint.ANTI_ALIAS_FLAG)
            views.setTextColor(R.id.checklist_text, themeColors.text)
        }

        // Fill-in intent for toggling this item
        val fillIntent = Intent().apply {
            putExtra(ObsidianWidgetProvider.EXTRA_LINE_INDEX, item.lineIndex)
            putExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, widgetId)
        }
        if (tapCheckboxOnly) {
            views.setOnClickFillInIntent(R.id.checklist_checkbox, fillIntent)
            val url = extractFirstUrl(item.text)
            if (url != null) {
                views.setOnClickFillInIntent(R.id.checklist_item_root, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_URL, url)
                })
            } else {
                views.setOnClickFillInIntent(R.id.checklist_item_root, Intent())
            }
        } else {
            views.setOnClickFillInIntent(R.id.checklist_item_root, fillIntent)
        }

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 5
    override fun getItemId(position: Int): Long = items[position].lineIndex.toLong()
    override fun hasStableIds(): Boolean = true
}
