# RYSM JS 全特性测试台

`source/all-features/` 是唯一测试模型源码；旧的 01–04 示例与双包已删除。几何、普通贴图及自定义星空按 CC0-1.0 提供。

将 `export/rysmjs-all-features.ysm` 与 `export/rysmjs-all-features.rysm` 一起放入游戏 `config/yes_steve_model/custom/`，或复制完整源码目录进去。不要同时安装源码和双包，以免显示两个条目。原版 YSM 2.6.5 使用基础包中的静态 fallback；RYSM 加载同名扩展包恢复算法。

| 部件 | 检查内容 |
| --- | --- |
| cape / cape_end、collider | 披风物理链、末端、身体碰撞；披风同时描边 |
| tail / tail_end | 尾骨物理默认参数与碰撞 |
| 头顶 ornament | 面向镜头、发光和时间驱动旋转 |
| 上方三块 sky | 默认星空、蓝色混色自定义 crosses + 描边、pulses 动态星空 |
| 左侧 frame_panel | 两帧 UV，每 6 tick 切换；黄/紫交替 |
| 右侧 macro_panel | JS 宏驱动两帧 UV；宏编号 1/2/3/4，关闭循环后超过 2 保持紫色 |
| 最左 paper_item | 只有 north 有效面的单面厚度与发光 |
| 第一排五个小方块 | 从 x=-12 到 +12：ESC 切换、物品栏、精妙背包、鼠标左键按住、鼠标左键释放次数奇偶；激活时上移 |
| 第二排小方块 | 从 x=-12 到 +6：血量缩放、YSM 纵向速度位移、相机相对角度、带参数 sum(15,30) 动画旋转 |

ESC 使用物理键 pressed；鼠标使用 down/released；物品栏与背包使用已注册快捷键，跟随重绑定。背包缺失时保持未按下，实际标识可用 `/reimaginedysm keys` 查询。输入不消费原事件，ESC 仍会打开菜单。五个指示灯通过 sync:true 输出供远端姿态读取，不直接读取远端键盘或私有状态。

手写算法位于 `rysm/scripts/algorithms.js`；表单属性独立保存在 `rysm/scripts/blockbench.js`。在 BB 导入 `models/main.json`，使用插件「导入模型脚本属性」选择模型根目录，再打开脚本预览。物理只显示骨链和碰撞辅助，不模拟运动。模拟输入示例：

```json
{"entity":{"local":true,"health":10,"maxHealth":20,"yaw":0},"camera":{"yaw":30,"pitch":10},"molang":{"rysm.longitudinal_speed":1},"keys":["key.keyboard.escape","key.mouse.left"],"bindings":["key.inventory"]}
```

先运行再改变 keys/bindings 可检查边沿；远端可设 `entity.local:false`，以 `remote` 提供五个同步表达式值。BB 模拟字段的完整格式以插件默认预览数据为准。

重新生成与检查：

```powershell
./gradlew.bat :common:scriptChecks -PscriptExamples -PscriptGpu :forge:build :fabric:build --offline
```

自动检查覆盖组属性与骨骼存在性、同步声明、基础包表达式降级/扩展恢复、摘要绑定、发布回滚及自定义星空 GPU 上传。日志中的预算超限、非法输入、升级失败和 missing 星空告警来自故意构造的负例。

仍需游戏实测：移动时物理与碰撞、多实体隔离、失焦/菜单/修饰符/重绑定、缺失背包模组、两客户端同步开关/重连/重新跟踪/切换模型、资源重载、原版 YSM 2.6.5 加载。BB 还需实测保存重开、撤销、属性导入、脚本错误定位与停止预览。
