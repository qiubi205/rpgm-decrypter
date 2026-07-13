# RPGM Decrypter (Android)

> RPG Maker MV / MZ `.png_` 图像文件一键解密工具

基于 [Petschko/RPG-Maker-MV-Decrypter](https://github.com/Petschko/RPG-Maker-MV-Decrypter) 的算法实现，专为 Android 平台打造。

## 功能

- ✅ **一键解密** `.png_` → `.png` — 无密钥即可还原 RPG Maker MV/MZ 加密的图片
- ✅ **批量处理** — 支持同时选择多个 `.png_` 文件批量解密
- ✅ **文件管理** — 支持 SAF（Storage Access Framework）和直接文件访问
- ✅ **进度显示** — 实时显示解密进度和详细日志
- ✅ **自定义输出** — 可选择任意目录保存解密结果

## 解密原理

RPG Maker MV/MZ 在输出加密游戏时，会在标准 PNG 文件头部插入 **16 字节的伪造 header**（Fake Header）：

| 偏移 | 大小 | 内容 |
|------|------|------|
| 0 | 4 | `RPGM` 签名 |
| 4 | 2 | 版本号 |
| 6 | 2 | 保留字段 |
| 8 | 8 | 时间戳 / GUID |

**无密钥模式**：直接跳过前 16 字节，剩余部分即为标准 PNG 文件。

> 注：音频文件（.rpgmvm / .rpgmvo）需要 XOR 密钥解密，本工具暂不支持。

## 截图

| 主界面 | 批量选择 | 解密结果 |
|--------|----------|----------|
| ![主界面](screenshots/main.png) | ![批量选择](screenshots/select.png) | ![结果](screenshots/result.png) |

## 下载

前往 [Releases](https://github.com/qiubi205/rpgm-decrypter/releases) 下载最新 APK。

## 构建

```bash
git clone https://github.com/qiubi205/rpgm-decrypter.git
cd rpgm-decrypter
./gradlew assembleRelease
```

APK 输出位置：`app/build/outputs/apk/release/app-release.apk`

## 许可

[MIT License](LICENSE)

## 致谢

- [Petschko/RPG-Maker-MV-Decrypter](https://github.com/Petschko/RPG-Maker-MV-Decrypter) — 原始 Web 解密工具
