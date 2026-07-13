# RPGM Decrypter (Android)

> RPG Maker MV / MZ `.png_` 图像文件解密工具  
> 基于 [Petschko/RPG-Maker-MV-Decrypter](https://github.com/Petschko/RPG-Maker-MV-Decrypter) 的算法实现

## 功能

- 🔓 **无密钥还原** — 跳过 16 字节伪造头部，直接还原图片
- 🔑 **有密钥解密** — 支持输入 hex 格式密钥进行 XOR 解密
- 🔍 **自动检测密钥** — 从加密文件中自动提取 XOR 密钥
- 📂 **从 System.json 导入密钥** — 选择游戏项目的 System.json 自动提取
- ✅ **批量处理** — 同时选择多个 .png_ 文件一次性解密
- 📁 **SAF 文件访问** — 支持 Android 10+ 的 Storage Access Framework
- 📊 **实时日志** — 每条文件解密结果即时显示

## 解密原理

RPG Maker MV/MZ 的图片加密分两层：

### 1. 伪造头部（Fake Header）

在文件前加 16 字节伪造头部：

```
字节 0-3:   "RPGM" 签名
字节 4-5:   版本号（MV=0x0003, MZ=0x0001）
字节 6-7:   保留字段
字节 8-15:  GUID / 时间戳
```

### 2. XOR 数据加密（可选）

使用 `encryptionKey` 对跳过头部后的数据进行 XOR 加密：

```
for i in 0..data.length:
    data[i] ^= key[i % key.length]
```

密钥存储在游戏目录下的 `System.json` 的 `"encryptionKey"` 字段中（hex string）。

**不是所有游戏都用了密钥加密**，部分游戏只在文件头加了 16 字节伪造头部，直接用"无密钥模式"即可还原。

### 3. 密钥自动检测

利用 PNG 文件固定的 8 字节签名，从加密数据头部 XOR 反推密钥：

```
known_plaintext = 0x89504E470D0A1A0A  (PNG签名)
key[0..7] = encrypted[0..7] ^ known_plaintext
```

如果密钥正好是 8 或 16 字节，用此方法可自动恢复。

## 使用方法

1. 打开 App
2. （可选）输入密钥 → 或点击"从文件检测"/"从 System.json 导入"
3. 选择 .png_ 文件（支持多选）
4. 选择输出目录
5. 点击解密按钮

## 构建

```bash
git clone https://github.com/qiubi205/rpgm-decrypter.git
cd rpgm-decrypter
./gradlew assembleRelease
```

APK 输出：`app/build/outputs/apk/release/app-release.apk`

## 许可

MIT License

## 致谢

- [Petschko/RPG-Maker-MV-Decrypter](https://github.com/Petschko/RPG-Maker-MV-Decrypter) — 原始 Web 解密工具
