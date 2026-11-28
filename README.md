# Reimagined YSM

# 项目介绍与许可

Reimagined YSM 是一款 Yes Steve Model 的附属模组，它重构了YSM的渲染逻辑与模型加载逻辑，提升了其性能并增加了相当多的新功能。

本项目的性能优化逻辑基于OpenYSM。

本项目的目标是使得YSM在多人模式下优化网络传输方式，并使其可以在纯客户端模式运行。

本人不支持，不认可OpenYSM开发组反编译闭源项目并以MIT协议开源的行为。拿着反编译的产物作为把柄要挟别人，这并非开源精神，而是绝对可耻的，对开源精神的亵渎。

本人始终尊重YSM模组开发团队及其社区的模型作者的劳动成果。没有他们的劳动就没有YSM如今的辉煌。

此项目诞生的原因仅仅是我受不了YSM没有官方支持的纯客户端模式与模型下载传输优化。

Reimagined YSM 仅维护简体中文（zh_cn）和英语（en_us）两种语言。

## 来源与许可

本项目源码使用 [MIT 许可证](../LICENSE.txt)。依赖项目具体条款见对应资源说明。

模型体系来源于 [Yes Steve Model](https://modrinth.com/mod/yes-steve-model)，模型与动画处理使用 [GeckoLib](https://github.com/bernie-g/geckolib)。可选原生渲染库见 [openysm.cpp](https://github.com/OpenYSMDev/openysm.cpp)。

默认星空的着色器和纹理来源于 [Avaritia](https://github.com/Morpheus1101/Avaritia/tree/07ce275b46e8afe4d932a0178777591794d7f612)。


## Wiki 目录

- [Wiki 首页](wiki/README.md)
- [安装与开始使用](wiki/getting-started.md)
- [示例模型与下载文件](wiki/examples.md)
- [Blockbench 编辑器](wiki/blockbench.md)
- [模型效果](wiki/effects.md)
- [披风、衣摆与尾巴物理](wiki/physics.md)
- [按键与动画脚本](wiki/scripting.md)
- [联机与服务器管理](wiki/multiplayer.md)
- [兼容性、旧模型升级与饰品联动](wiki/compatibility.md)
- [从源码构建](wiki/building.md)

## 仓库目录

- [示例模型源码与脚本](examples/rysmjs/README.md)
- [Blockbench 插件与详细操作](blockbench/README.md)
- [旧模型升级工具](.legacy-physic-upgrade-tool/README.md)
- [许可证](LICENSE.txt)
