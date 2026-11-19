# release 的 R8 规则（开关见 build.gradle.kts 的 release buildType）。
#
# 当前**没有任何自定义规则**：
#  - 清单里注册的 Activity 由 AGP 按清单自动补规则；
#  - ViewBinding 生成的类、Material / MapLibre 自带的 consumer 规则由各自依赖兜住；
#  - 2.0 起地图是 MapLibre，早期为 osmdroid 写的 MapView / overlay / tileprovider 三条 keep
#    随依赖一起删掉了（对应类已经不在包里）；WebView 的 JS 桥与那个 Activity 也早已移除。
#
# 以后再加「按类名 inflate 的自定义 View」或「JS 桥」这类跨语言契约时，记得在这里补规则，
# 并写清漏了会以什么症状出现 —— 这类问题只在 release 包里暴露。
