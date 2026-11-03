package dev.phonepal.ios

import dev.phonepal.api.Bounds
import dev.phonepal.api.UiNode
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil
import kotlin.math.floor

/** Flattens WDA XML in document order. Bounds use iOS screen points. */
object WdaSource {
    internal data class Entry(val ui: UiNode, val type: String, val value: String?, val placeholder: String?)
    internal data class Snapshot(val entries: List<Entry>, val navigationBars: List<Bounds>)

    fun parse(xml: String, screenWidth: Int, screenHeight: Int): List<UiNode> =
        parseDetails(xml, screenWidth, screenHeight).entries.map { it.ui }

    internal fun parseDetails(xml: String, screenWidth: Int, screenHeight: Int): Snapshot {
        if (screenWidth <= 0 || screenHeight <= 0) return Snapshot(emptyList(), emptyList())
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val entries = mutableListOf<Entry>()
        val bars = mutableListOf<Bounds>()
        val elements = document.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i) as Element
            val type = (element.attr("type") ?: element.tagName).removePrefix("XCUIElementType")
            if (element.attr("visible") != "true") continue
            val bounds = bounds(element, screenWidth, screenHeight) ?: continue
            if (type == "NavigationBar") bars += bounds
            if (type == "Application" || type == "Window") continue
            val label = element.attr("label")
            val value = element.attr("value")
            val placeholder = element.attr("placeholderValue")
            val name = element.attr("name")
            val text = label ?: value?.takeUnless { it == placeholder }
            val description = name?.takeUnless { it == text } ?: placeholder?.takeUnless { it == text }
            val editable = type in editableTypes
            val scrollable = type in scrollableTypes
            val interactive = type in clickableTypes || editable || (type == "Image" && label != null)
            if (label == null && value == null && name == null && !interactive && !scrollable) continue
            if (type == "Other" && label == null && value == null && name == null) continue
            entries += Entry(UiNode(
                id = entries.size,
                text = text,
                description = description,
                className = type,
                bounds = bounds,
                clickable = interactive && element.attr("enabled") == "true",
                editable = editable,
                scrollable = scrollable,
                focused = element.attr("focused") == "true",
                password = type == "SecureTextField",
            ), type, value, placeholder)
        }
        return Snapshot(entries, bars)
    }

    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotBlank() }

    private fun bounds(element: Element, width: Int, height: Int): Bounds? {
        fun number(name: String) = element.getAttribute(name).toDoubleOrNull()?.takeIf { it.isFinite() }
        val x = number("x") ?: return null
        val y = number("y") ?: return null
        val w = number("width") ?: return null
        val h = number("height") ?: return null
        if (w <= 0 || h <= 0) return null
        val left = x.coerceIn(0.0, width.toDouble())
        val top = y.coerceIn(0.0, height.toDouble())
        val right = (x + w).coerceIn(0.0, width.toDouble())
        val bottom = (y + h).coerceIn(0.0, height.toDouble())
        if (right <= left || bottom <= top) return null
        return Bounds(floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt())
    }

    private val editableTypes = setOf("TextField", "SecureTextField", "SearchField", "TextView")
    private val scrollableTypes = setOf("ScrollView", "Table", "CollectionView", "WebView")
    private val clickableTypes = setOf("Button", "Cell", "Link", "Switch", "Key", "Icon")
}
