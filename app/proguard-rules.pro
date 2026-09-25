# release 的 R8 规则（开关见 build.gradle.kts 的 release buildType）。
#
# 这里只有地图那一条，其余不用管：清单里注册的四个 Activity 由 AGP 按清单自动补规则，
# ViewBinding 生成的类、Material/osmdroid 自带的 consumer 规则也都由各自的依赖兜住。

# ── 按类名 inflate 的自定义 View（跨语言契约：布局 XML ↔ 类名）──
# fragment_map.xml 里写的是 <org.osmdroid.views.MapView .../>，框架用的是
# LayoutInflater.createView 的反射路径 —— R8 看不见「XML 里有个字符串等于某个类名」，
# 只看见 LayoutInflater 的反射调用。名字被改成 a.b.c 之后，症状是**点开地图页直接崩**
# （ClassNotFoundException: org.osmdroid.views.MapView），而 debug 包里一切正常。
# 我们的 Kotlin 代码确实引用了 MapView 类型（binding.map 的强转），所以它大概率会被保留；
# 显式写一条是为了把这份契约摆到源码里，别依赖「恰好还被别处引用」。
-keep class org.osmdroid.views.MapView { *; }
-keep class org.osmdroid.views.overlay.** { *; }
-keep class org.osmdroid.tileprovider.tilesource.** { *; }