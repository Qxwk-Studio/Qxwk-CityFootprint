package top.qxwkstudio.travel.logic

/**
 * 地图 style 的生成（纯函数，可单测）。
 *
 * 为什么单独抽出来：style 写错**不会崩**，只会安静地什么都不画 ——
 * 第一版就是把 PMTiles 归档写进了 `tiles` 数组（那里要的是 `{z}/{x}/{y}` 模板），
 * 结果底图一片空白，而日志里没有任何错误。抽出来之后至少有单测钉住字段名。
 *
 * 关键约定：**PMTiles 源用 `url` 而不是 `tiles`**（对齐 MapLibre 官方 PMTiles 示例：
 * `VectorSource(id, "pmtiles://…")`）。写成 tiles 的话 MapLibre 会把它当瓦片模板，
 * 每个请求都是同一个不带占位符的地址，自然什么都取不到。
 *
 * 颜色由调用方传入（Android 侧从主题色板取），这里不碰任何 android.* 的东西。
 */
object MapStyle {

    /** 底图配色。深色模式由调用方换一套值，样式本身不变。 */
    data class Colors(
        val background: String,
        val land: String,
        val water: String,
        val road: String,
    )

    /**
     * 六层：底色 → 陆地 → 用地 → 水系 → 道路 → 边界。
     * 图层名（`source-layer`）对应 Protomaps 的矢量图层；某层不存在时它只是不画，不会报错。
     */
    fun json(pmtilesUrl: String, colors: Colors): String = """
        {
          "version": 8,
          "sources": {
            "basemap": { "type": "vector", "url": "$pmtilesUrl" }
          },
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "${colors.background}" } },
            { "id": "earth", "type": "fill", "source": "basemap", "source-layer": "earth",
              "paint": { "fill-color": "${colors.land}", "fill-opacity": 0.45 } },
            { "id": "landuse", "type": "fill", "source": "basemap", "source-layer": "landuse",
              "paint": { "fill-color": "${colors.land}", "fill-opacity": 0.5 } },
            { "id": "water", "type": "fill", "source": "basemap", "source-layer": "water",
              "paint": { "fill-color": "${colors.water}" } },
            { "id": "roads", "type": "line", "source": "basemap", "source-layer": "roads",
              "paint": { "line-color": "${colors.road}", "line-width": 0.6 } },
            { "id": "boundaries", "type": "line", "source": "basemap", "source-layer": "boundaries",
              "paint": { "line-color": "${colors.road}", "line-width": 0.5 } }
          ]
        }
    """.trimIndent()
}
