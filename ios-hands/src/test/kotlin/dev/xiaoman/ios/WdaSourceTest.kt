package dev.xiaoman.ios

import dev.xiaoman.api.Bounds
import kotlin.test.*

class WdaSourceTest {
    @Test fun parsesDocumentOrderPointBoundsAndInputMetadata() {
        val xml = """
            <AppiumAUT>
              <XCUIElementTypeApplication visible="true" label="应用" x="0" y="0" width="390" height="844">
                <XCUIElementTypeWindow visible="true" name="窗口" x="0" y="0" width="390" height="844">
                  <XCUIElementTypeNavigationBar visible="true" label="导航" x="0" y="40" width="390" height="50">
                    <XCUIElementTypeButton visible="true" enabled="true" label="返回" name="back" x="-10" y="40" width="70" height="40"/>
                  </XCUIElementTypeNavigationBar>
                  <XCUIElementTypeStaticText visible="true" label="你好，世界" name="title" x="20.5" y="110.2" width="100" height="20"/>
                  <XCUIElementTypeTextField visible="true" enabled="true" value="请输入姓名" placeholderValue="请输入姓名" x="20" y="160" width="200" height="40"/>
                  <XCUIElementTypeSecureTextField visible="true" enabled="true" label="密码" value="••••" x="20" y="210" width="200" height="40"/>
                  <XCUIElementTypeCell visible="true" enabled="true" x="0" y="260" width="400" height="50"/>
                  <XCUIElementTypeButton visible="true" enabled="false" label="禁用" x="0" y="310" width="100" height="40"/>
                  <XCUIElementTypeScrollView visible="true" x="0" y="360" width="390" height="500"/>
                  <XCUIElementTypeStaticText visible="false" label="隐藏" x="0" y="0" width="100" height="40"/>
                  <XCUIElementTypeButton visible="true" enabled="true" label="零尺寸" x="0" y="0" width="0" height="40"/>
                  <XCUIElementTypeOther visible="true" x="0" y="0" width="100" height="40"/>
                  <XCUIElementTypeStaticText visible="true" value="仅有值" x="0" y="800" width="100" height="100"/>
                  <XCUIElementTypeButton visible="true" enabled="true" label="屏幕外" x="500" y="0" width="10" height="10"/>
                </XCUIElementTypeWindow>
              </XCUIElementTypeApplication>
            </AppiumAUT>
        """.trimIndent()
        val snapshot = WdaSource.parseDetails(xml, 390, 844)
        val nodes = WdaSource.parse(xml, 390, 844)
        assertEquals((0..8).toList(), nodes.map { it.id })
        assertEquals(listOf("NavigationBar", "Button", "StaticText", "TextField", "SecureTextField",
            "Cell", "Button", "ScrollView", "StaticText"), nodes.map { it.className })
        assertEquals(Bounds(0, 40, 60, 80), nodes[1].bounds)
        assertEquals(Bounds(20, 110, 121, 131), nodes[2].bounds)
        assertEquals("你好，世界", nodes[2].text)
        assertEquals("title", nodes[2].description)
        assertEquals("back", nodes[1].description)
        assertNull(nodes[3].text)
        assertEquals("请输入姓名", nodes[3].description)
        assertTrue(nodes[3].editable && nodes[3].clickable)
        assertTrue(nodes[4].password && nodes[4].editable && nodes[4].clickable)
        assertTrue(nodes[5].clickable)
        assertFalse(nodes[6].clickable)
        assertTrue(nodes[7].scrollable)
        assertEquals(Bounds(0, 360, 390, 844), nodes[7].bounds)
        assertEquals("仅有值", nodes[8].text)
        assertEquals(844, nodes[8].bounds.bottom)
        assertTrue(nodes.all { it.resourceId == null })
        assertEquals("SecureTextField", snapshot.entries[4].type)
        assertEquals("••••", snapshot.entries[4].value)
        assertEquals("请输入姓名", snapshot.entries[3].placeholder)
        assertEquals(listOf(Bounds(0, 40, 390, 90)), snapshot.navigationBars)
    }

    @Test fun flagsCoverAllInteractiveAndScrollableTypes() {
        val types = listOf("Link", "Switch", "Key", "Icon", "Image", "SearchField", "TextView",
            "Table", "CollectionView", "WebView")
        val xml = "<root>" + types.joinToString("") {
            """<XCUIElementType$it visible="true" enabled="true" label="内容" x="0" y="0" width="20" height="20"/>"""
        } + "</root>"
        val nodes = WdaSource.parse(xml, 390, 844)
        assertTrue(nodes.take(7).all { it.clickable })
        assertTrue(nodes.slice(5..6).all { it.editable })
        assertTrue(nodes.takeLast(3).all { it.scrollable && !it.clickable })
    }

    @Test fun emptyScreenAndExternalEntitiesAreRejected() {
        assertEquals(emptyList(), WdaSource.parse("<root/>", 0, 844))
        assertFails {
            WdaSource.parse("""<!DOCTYPE root [<!ENTITY x SYSTEM "file:///not-authorized">]><root>&x;</root>""", 390, 844)
        }
    }
}
