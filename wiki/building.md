[返回 Wiki 目录](README.md)

# 从源码构建

此页供需要自行编译的使用者参考。安装和使用已发布模组时无需执行这些步骤。

构建环境使用 Java 21，编译产物以 Java 17 为目标。在仓库根目录执行：

```powershell
./gradlew.bat :forge:build :fabric:build
```

Forge 产物位于 `forge/build/libs/`，Fabric 产物位于 `fabric/build/libs/`。选择与游戏加载器一致的文件，并按[安装说明](getting-started.md)安装依赖。
