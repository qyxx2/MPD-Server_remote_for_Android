# Task 5 单曲菜单呈现改进验证记录

## 实现内容
- 普通单曲菜单改为以触发条目的完整 bounds 为锚点居中定位，并在窗口边缘进行 8dp 安全夹紧。
- 菜单每个选项均采用左对齐布局，共用图标列和文字起点；Surface/Column 使用 intrinsic max width，使最长标签决定菜单内容宽度。
- 菜单背景为 94% 白色，深色文字；圆角 18dp、阴影 7dp。
- Play Now、Play Next、Add to Queue、Favorite 四类操作均有对应图标。
- 保留现有 SongActions 调用、availability/freshness gating、收藏回调、外部点击关闭和返回键关闭。

## 影响范围
SongActionMenu 当前由 Home 单曲卡片、Library 歌曲行和专辑成员复用；因此这些入口统一获得新呈现。全屏播放器与 Queue occurrence 菜单未修改。

## 验证状态
- [x] :app:compileDebugKotlin 通过。
- [x] :app:compileDebugAndroidTestKotlin 通过。
- [x] :app:testDebugUnitTest 通过：419 tests，0 failures、0 errors、0 skipped。
- [x] :app:assembleDebug 通过；生成 app/build/outputs/apk/debug/app-debug.apk（约 13MiB）。
- [x] APK package 为 io.github.qyxx2.mpdserver.remote；签名为现有统一 internal 证书，SHA-256：36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f。
- [ ] SongActionMenuUiTest 设备执行：ADB 返回 List of devices attached 空列表；Gradle 报 DeviceException: No connected devices，本次环境无法执行 connected instrumentation。
- [ ] lintDebug：两次执行均停在 lint analysis 阶段，没有得到完成结果；已停止该任务，lint 状态记为未验证。
- [ ] 覆盖安装保留数据：因 ADB 无连接设备，未执行。

## 限制
Instrumentation 断言已经更新并成功编译，但设备级菜单视觉、定位、触摸关闭行为尚未在真实设备上复验。不能将编译通过等同于设备验收通过。
