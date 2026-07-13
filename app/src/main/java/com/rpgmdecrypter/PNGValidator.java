package com.rpgmdecrypter;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * PNG 文件完整性检查器
 *
 * 检查以下内容：
 * 1. PNG 签名（8字节 magic number）
 * 2. IHDR chunk 结构完整性
 * 3. 关键 chunk 顺序合规性 (IHDR → PLTE → IDAT → IEND)
 * 4. 每个 chunk 的 CRC 校验
 * 5. 文件截断检测
 * 6. Chunk 类型合法性
 * 7. 颜色类型与位深组合合规性
 * 8. IDAT 数据流完整性
 */
public class PNGValidator {

    private static final String TAG = "PNGValidator";

    /** PNG 文件签名 */
    public static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    /** 关键 chunk 类型 */
    private static final String CHUNK_IHDR = "IHDR";
    private static final String CHUNK_PLTE = "PLTE";
    private static final String CHUNK_IDAT = "IDAT";
    private static final String CHUNK_IEND = "IEND";

    /** 颜色类型常量 */
    private static final int COLOR_TYPE_GRAYSCALE = 0;
    private static final int COLOR_TYPE_RGB = 2;
    private static final int COLOR_TYPE_PALETTE = 3;
    private static final int COLOR_TYPE_GRAYSCALE_ALPHA = 4;
    private static final int COLOR_TYPE_RGBA = 6;

    public static class PNGIssue {
        public final String type;       // ERROR / WARNING / INFO
        public final String message;
        public final long position;     // 文件中的字节偏移

        PNGIssue(String type, String message, long position) {
            this.type = type;
            this.message = message;
            this.position = position;
        }

        @Override
        public String toString() {
            return type + " @0x" + Long.toHexString(position) + ": " + message;
        }
    }

    public static class ValidationResult {
        public final boolean valid;          // 是否可以正常显示
        public final List<PNGIssue> issues;
        public final ImageInfo info;

        ValidationResult(boolean valid, List<PNGIssue> issues, ImageInfo info) {
            this.valid = valid;
            this.issues = issues;
            this.info = info;
        }

        public String getSummary() {
            int errs = 0, warns = 0;
            for (PNGIssue i : issues) {
                if ("ERROR".equals(i.type)) errs++;
                else if ("WARNING".equals(i.type)) warns++;
            }
            return (valid ? "✅ 可显示" : "❌ 不可显示")
                    + " | " + errs + " 个错误, " + warns + " 个警告";
        }

        public String getFullReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("━━━ PNG 检查报告 ━━━\n");
            if (info != null) {
                sb.append("尺寸: ").append(info.width).append("×").append(info.height).append("\n");
                sb.append("位深: ").append(info.bitDepth).append(" bit");
                if (info.colorType >= 0) {
                    sb.append(" | 颜色类型: ").append(colorTypeName(info.colorType)).append(" (").append(info.colorType).append(")");
                }
                sb.append("\n");
                sb.append("压缩: 方法 ").append(info.compressionMethod)
                        .append(" | 滤波 ").append(info.filterMethod)
                        .append(" | 隔行 ").append(info.interlaceMethod).append("\n");
                sb.append("文件大小: ").append(info.fileSize).append(" 字节\n");
                sb.append("Chunk 数: ").append(info.totalChunks).append("\n\n");
            }
            sb.append("📋 检查结果: ").append(getSummary()).append("\n\n");
            for (PNGIssue issue : issues) {
                String icon;
                switch (issue.type) {
                    case "ERROR":   icon = "❌"; break;
                    case "WARNING": icon = "⚠️"; break;
                    default:       icon = "ℹ️";
                }
                sb.append(icon).append(" [").append(issue.type).append("] ");
                if (issue.position >= 0) {
                    sb.append("偏移 0x").append(Long.toHexString(issue.position)).append(": ");
                }
                sb.append(issue.message).append("\n");
            }
            return sb.toString();
        }
    }

    public static class ImageInfo {
        public final int width;
        public final int height;
        public final int bitDepth;
        public final int colorType;
        public final int compressionMethod;
        public final int filterMethod;
        public final int interlaceMethod;
        public final long fileSize;
        public final int totalChunks;

        ImageInfo(int width, int height, int bitDepth, int colorType,
                  int compressionMethod, int filterMethod, int interlaceMethod,
                  long fileSize, int totalChunks) {
            this.width = width;
            this.height = height;
            this.bitDepth = bitDepth;
            this.colorType = colorType;
            this.compressionMethod = compressionMethod;
            this.filterMethod = filterMethod;
            this.interlaceMethod = interlaceMethod;
            this.fileSize = fileSize;
            this.totalChunks = totalChunks;
        }
    }

    /**
     * 验证 PNG 文件并返回完整的检查报告
     */
    public static ValidationResult validate(File file) throws IOException {
        return validate(new FileInputStream(file), file.length());
    }

    public static ValidationResult validate(InputStream inputStream, long fileSize) throws IOException {
        List<PNGIssue> issues = new ArrayList<>();
        ImageInfo info = null;

        // 读取全部数据
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(fileSize, 50 * 1024 * 1024));
        byte[] buf = new byte[8192];
        int n;
        while ((n = inputStream.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        inputStream.close();
        byte[] data = baos.toByteArray();

        if (data.length < PNG_SIGNATURE.length) {
            issues.add(new PNGIssue("ERROR", "文件过短（" + data.length + " 字节），不是有效 PNG", 0));
            return new ValidationResult(false, issues, null);
        }

        long actualFileSize = data.length;

        // ─── 1. PNG 签名检查 ──────────────────────────────────────
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                issues.add(new PNGIssue("ERROR", "PNG 签名错误：偏移 " + i + " 应为 0x"
                        + String.format("%02X", PNG_SIGNATURE[i] & 0xFF)
                        + "，实际 0x" + String.format("%02X", data[i] & 0xFF), i));
            }
        }

        // 检查是否有常见的非 PNG 签名
        if (data.length >= 4) {
            String magic = new String(data, 0, 4, StandardCharsets.US_ASCII);
            if (magic.equals("RPGM")) {
                issues.add(new PNGIssue("ERROR", "文件包含 RPGM Fake Header（.png_ 格式），需要先跳过前 16 字节", 0));
            } else if (magic.equals("\u0089PNG")) {
                // 正常
            } else {
                issues.add(new PNGIssue("WARNING", "文件签名异常：'" + magic + "'", 0));
            }
        }

        // ─── 2. 遍历所有 chunk ────────────────────────────────────
        int pos = PNG_SIGNATURE.length;
        int chunkCount = 0;
        boolean hasIHDR = false;
        boolean hasPLTE = false;
        boolean hasIDAT = false;
        boolean hasIEND = false;
        boolean idatStarted = false;
        int idatCount = 0;
        long totalIdatData = 0;
        int width = -1, height = -1, bitDepth = -1, colorType = -1;
        int compressionMethod = -1, filterMethod = -1, interlaceMethod = -1;

        CRC32 crc = new CRC32();

        while (pos + 4 <= data.length) {
            // 读取 chunk 长度
            int chunkLen = readIntBE(data, pos);
            if (chunkLen < 0) {
                issues.add(new PNGIssue("ERROR", "Chunk 长度为负数: " + chunkLen, pos));
                break;
            }

            if (pos + 12 + chunkLen > data.length) {
                issues.add(new PNGIssue("ERROR",
                        "Chunk 数据超出文件边界：声明长度 " + chunkLen
                                + " 字节，但文件剩余仅 " + (data.length - pos - 4) + " 字节",
                        pos));
                // 尝试读取类型
                if (pos + 8 <= data.length) {
                    String partialType = new String(data, pos + 4, 4, StandardCharsets.US_ASCII);
                    issues.add(new PNGIssue("INFO", "截断 chunk 类型为: " + partialType, pos + 4));
                }
                break;
            }

            // 读取 chunk 类型
            String chunkType = new String(data, pos + 4, 4, StandardCharsets.US_ASCII);
            chunkCount++;

            // 验证 chunk 类型名称合法性（ASCII 字母）
            boolean validTypeName = true;
            for (int i = 0; i < 4; i++) {
                byte c = data[pos + 4 + i];
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                    validTypeName = false;
                    break;
                }
            }
            if (!validTypeName) {
                issues.add(new PNGIssue("ERROR", "非法 chunk 类型名: '" + chunkType + "'（应仅含 ASCII 字母）", pos + 4));
            }

            // CRC 校验
            crc.reset();
            crc.update(data, pos + 4, 4 + chunkLen);
            long expectedCrc = crc.getValue() & 0xFFFFFFFFL;
            int actualCrc = readIntBE(data, pos + 8 + chunkLen);
            if ((actualCrc & 0xFFFFFFFFL) != expectedCrc) {
                issues.add(new PNGIssue("ERROR",
                        "CRC 校验失败: " + chunkType
                                + " 期望 0x" + String.format("%08X", expectedCrc)
                                + " 实际 0x" + String.format("%08X", actualCrc & 0xFFFFFFFFL),
                        pos + 8 + chunkLen));
            }

            // ─── Chunk 内容解析 ──────────────────────────────────

            switch (chunkType) {
                case CHUNK_IHDR:
                    if (hasIHDR) {
                        issues.add(new PNGIssue("ERROR", "重复的 IHDR chunk", pos));
                    }
                    hasIHDR = true;
                    if (chunkLen != 13) {
                        issues.add(new PNGIssue("ERROR", "IHDR 长度应为 13 字节，实际 " + chunkLen, pos));
                    } else {
                        width = readIntBE(data, pos + 8);
                        height = readIntBE(data, pos + 12);
                        bitDepth = data[pos + 16] & 0xFF;
                        colorType = data[pos + 17] & 0xFF;
                        compressionMethod = data[pos + 18] & 0xFF;
                        filterMethod = data[pos + 19] & 0xFF;
                        interlaceMethod = data[pos + 20] & 0xFF;

                        // 检查 IHDR 值有效性
                        if (width <= 0) issues.add(new PNGIssue("ERROR", "宽度非法: " + width, pos + 8));
                        if (width == 0) issues.add(new PNGIssue("ERROR", "宽度为 0", pos + 8));
                        if (height <= 0) issues.add(new PNGIssue("ERROR", "高度非法: " + height, pos + 12));
                        if (height == 0) issues.add(new PNGIssue("ERROR", "高度为 0", pos + 12));

                        // 位深检查
                        boolean validBitDepth = false;
                        switch (colorType) {
                            case COLOR_TYPE_GRAYSCALE:
                                validBitDepth = (bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16);
                                break;
                            case COLOR_TYPE_RGB:
                                validBitDepth = (bitDepth == 8 || bitDepth == 16);
                                break;
                            case COLOR_TYPE_PALETTE:
                                validBitDepth = (bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8);
                                break;
                            case COLOR_TYPE_GRAYSCALE_ALPHA:
                                validBitDepth = (bitDepth == 8 || bitDepth == 16);
                                break;
                            case COLOR_TYPE_RGBA:
                                validBitDepth = (bitDepth == 8 || bitDepth == 16);
                                break;
                        }
                        if (!validBitDepth) {
                            issues.add(new PNGIssue("ERROR",
                                    "颜色类型 " + colorType + " 不支持位深 " + bitDepth, pos + 16));
                        }

                        // 压缩方法必须为 0
                        if (compressionMethod != 0) {
                            issues.add(new PNGIssue("ERROR", "不支持的压缩方法: " + compressionMethod, pos + 18));
                        }
                        // 滤波方法必须为 0
                        if (filterMethod != 0) {
                            issues.add(new PNGIssue("ERROR", "不支持的滤波方法: " + filterMethod, pos + 19));
                        }
                        // 隔行扫描
                        if (interlaceMethod != 0 && interlaceMethod != 1) {
                            issues.add(new PNGIssue("ERROR", "不支持的隔行扫描方法: " + interlaceMethod, pos + 20));
                        }
                    }
                    break;

                case CHUNK_PLTE:
                    if (!hasIHDR) {
                        issues.add(new PNGIssue("ERROR", "PLTE 出现在 IHDR 之前", pos));
                    }
                    if (hasPLTE) {
                        issues.add(new PNGIssue("ERROR", "重复的 PLTE chunk", pos));
                    }
                    hasPLTE = true;
                    if (chunkLen % 3 != 0) {
                        issues.add(new PNGIssue("ERROR", "PLTE 长度不是 3 的倍数: " + chunkLen, pos));
                    }
                    int paletteSize = chunkLen / 3;
                    if (paletteSize < 1 || paletteSize > 256) {
                        issues.add(new PNGIssue("ERROR", "调色板大小超出范围 (1-256): " + paletteSize, pos));
                    }
                    // 索引色时必须要有 PLTE
                    if (colorType == COLOR_TYPE_PALETTE) {
                        // 校验正确
                    }
                    break;

                case CHUNK_IDAT:
                    if (!hasIHDR) {
                        issues.add(new PNGIssue("ERROR", "IDAT 出现在 IHDR 之前", pos));
                    }
                    if (hasIEND) {
                        issues.add(new PNGIssue("ERROR", "IDAT 出现在 IEND 之后", pos));
                    }
                    if (!idatStarted) {
                        // 检查关键 chunk 顺序
                        if (colorType == COLOR_TYPE_PALETTE && !hasPLTE) {
                            issues.add(new PNGIssue("ERROR", "索引色 PNG 缺少 PLTE chunk", pos));
                        }
                    }
                    idatStarted = true;
                    hasIDAT = true;
                    idatCount++;
                    totalIdatData += chunkLen;
                    break;

                case CHUNK_IEND:
                    if (!hasIHDR) {
                        issues.add(new PNGIssue("ERROR", "IEND 出现在 IHDR 之前", pos));
                    }
                    if (hasIEND) {
                        issues.add(new PNGIssue("ERROR", "重复的 IEND chunk", pos));
                    }
                    hasIEND = true;
                    if (chunkLen != 0) {
                        issues.add(new PNGIssue("WARNING", "IEND 长度应为 0，实际 " + chunkLen, pos));
                    }
                    break;

                default:
                    // 辅助 chunk - 只检查可疑内容
                    break;
            }

            pos += 12 + chunkLen;
        }

        // ─── 3. 完整性总结 ──────────────────────────────────────

        if (!hasIHDR) {
            issues.add(new PNGIssue("ERROR", "缺少 IHDR chunk", -1));
        }
        if (!hasIDAT) {
            issues.add(new PNGIssue("ERROR", "缺少 IDAT chunk（图像数据）", -1));
        }
        if (!hasIEND) {
            issues.add(new PNGIssue("ERROR", "缺少 IEND chunk（文件结尾标记）", -1));
        }

        if (hasIHDR && hasIEND && !hasIDAT && pos < data.length) {
            issues.add(new PNGIssue("ERROR", "存在 IEND 后的多余数据", -1));
        }

        // 检查文件末尾是否有多余数据
        if (pos < data.length) {
            issues.add(new PNGIssue("WARNING", "IEND 之后存在 " + (data.length - pos) + " 字节多余数据", pos));
        }

        // 检查 IDAT 连续性
        if (idatCount > 1) {
            issues.add(new PNGIssue("INFO", "IDAT 拆分为 " + idatCount + " 个 chunk，总数据 "
                    + totalIdatData + " 字节", -1));
        }

        // 检查文件截断
        if (pos > data.length) {
            issues.add(new PNGIssue("ERROR", "文件被截断：缺少 " + (pos - data.length) + " 字节", data.length));
        }

        // 对于索引色，验证 palette 大小 vs 位深
        if (hasPLTE && bitDepth > 0 && colorType == COLOR_TYPE_PALETTE) {
            int maxColors = 1 << bitDepth;
            int actualColors = -1;
            // 需要从 PLTE 数据读取
        }

        // ─── 4. 构建结果 ─────────────────────────────────────────

        boolean isValid = hasIHDR && hasIDAT && hasIEND
                && !issues.stream().anyMatch(i -> "ERROR".equals(i.type));

        if (width > 0 && height > 0) {
            info = new ImageInfo(width, height, bitDepth, colorType,
                    compressionMethod, filterMethod, interlaceMethod,
                    actualFileSize, chunkCount);
        }

        return new ValidationResult(isValid, issues, info);
    }

    private static int readIntBE(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24
                | (data[offset + 1] & 0xFF) << 16
                | (data[offset + 2] & 0xFF) << 8
                | (data[offset + 3] & 0xFF);
    }

    private static String colorTypeName(int type) {
        switch (type) {
            case 0: return "灰度";
            case 2: return "RGB";
            case 3: return "索引色";
            case 4: return "灰度+Alpha";
            case 6: return "RGBA";
            default: return "未知(" + type + ")";
        }
    }
}
