package com.rpgmdecrypter;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * RPG Maker MV / MZ .png_ 文件解密工具
 *
 * === 解密机制 ===
 *
 * RPG Maker MV/MZ 对图片文件的加密分两层：
 *
 * 1. 数据加密（可选）：使用 encryptionKey 对文件数据进行 XOR 加密
 *    算法：data[i] ^= key[i % key.length]
 *
 * 2. 头部包裹：在加密数据（或未加密的原数据）前添加 16 字节 Fake Header
 *    ┌─────────┬──────┬──────┬──────────────────────────┐
 *    │ "RPGM"  │ Ver  │ Resv │ 时间戳/GUID (8 bytes)    │
 *    │ (4)     │ (2)  │ (2)  │                          │
 *    └─────────┴──────┴──────┴──────────────────────────┘
 *    对于 MZ，Ver 字段通常是 0x0001；MV 是 0x0003
 *
 * === 解密策略 ===
 *
 * 模式 A - 无密钥还原图片（Restore Image / No-Key）：
 *   直接跳过 Fake Header，结果可能是：
 *   - 标准 PNG（如果原文件未做 XOR 加密）
 *   - 乱码文件（如果原文件做了 XOR 加密，需要模式 B）
 *
 * 模式 B - 有密钥解密（Decrypt）：
 *   1. 跳过 Fake Header
 *   2. 用密钥对剩余数据做 XOR 解密
 *   3. 验证解密结果是否为有效 PNG
 *
 * 密钥来源：
 *   游戏项目中的 System.json 文件的 "encryptionKey" 字段
 *   （MV: www/data/System.json, MZ: data/System.json）
 *
 * 也可以直接从任意加密文件中提取 XOR 密钥：
 *   密钥 = 加密的数据 ^ 预期的原始数据
 *   对于 PNG，我们知道前 8 字节是固定签名，所以：
 *   key[0..7] = encrypted_header[0..7] ^ PNG_SIGNATURE[0..7]
 *   然后根据 8 字节密钥是否可以循环解密全部内容来判断。
 *   MZ 的密钥只有 16 字节。
 */
public class RPGMDecrypter {

    private static final String TAG = "RPGMDecrypter";

    /** 伪造的 RPMG header 长度（固定 16 字节） */
    public static final int FAKE_HEADER_LENGTH = 16;

    /** PNG 文件签名 */
    public static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    /** 最大处理文件大小 */
    private static final int MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    // ─── 模式 A：无密钥还原图像 ─────────────────────────────────────

    /**
     * 无密钥还原图片：跳过 Fake Header，尝试直接恢复。
     * 如果恢复后不是有效 PNG（无 PNG 签名），返回 null。
     *
     * @param file .png_ 文件
     * @return 还原的 PNG 字节数组，或 null
     */
    public static byte[] restoreImageNoKey(File file) throws IOException {
        long fileLen = file.length();
        if (fileLen < FAKE_HEADER_LENGTH + 4) {
            Log.w(TAG, "文件太小无法处理: " + file.getName());
            return null;
        }
        if (fileLen > MAX_FILE_SIZE) {
            Log.w(TAG, "文件过大: " + file.getName());
            return null;
        }

        // 跳过前 16 字节
        byte[] data;
        try (FileInputStream fis = new FileInputStream(file)) {
            long skipped = 0;
            while (skipped < FAKE_HEADER_LENGTH) {
                long s = fis.skip(FAKE_HEADER_LENGTH - skipped);
                if (s <= 0) break;
                skipped += s;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream((int) (fileLen - FAKE_HEADER_LENGTH));
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            data = baos.toByteArray();
        }

        // 验证是否有效 PNG
        if (hasPNGSignature(data)) {
            return data;
        }

        // 尝试扫描找到 PNG 签名（某些文件可能有额外的 padding）
        int sigOffset = findPNGSignature(data);
        if (sigOffset >= 0) {
            Log.i(TAG, "在偏移 " + sigOffset + " 处找到 PNG 签名，截断处理");
            byte[] trimmed = new byte[data.length - sigOffset];
            System.arraycopy(data, sigOffset, trimmed, 0, trimmed.length);
            return trimmed;
        }

        // 尝试修复 chunk 链
        byte[] fixed = tryFixPNGChunkChain(data);
        if (fixed != null && hasPNGSignature(fixed)) {
            return fixed;
        }

        // 仍然无效，可能是需要密钥
        Log.w(TAG, "无密钥模式无法还原: " + file.getName() + "（可能需要密钥）");
        return null;
    }

    // ─── 模式 B：有密钥解密 ───────────────────────────────────────

    /**
     * 用密钥解密 .png_ 文件。
     *
     * @param file    .png_ 文件
     * @param key     密钥字节数组（hex string 解码后）
     * @return 解密后的 PNG 字节数组，或 null
     */
    public static byte[] decryptWithKey(File file, byte[] key) throws IOException {
        if (key == null || key.length == 0) {
            Log.w(TAG, "密钥为空，回退到无密钥模式");
            return restoreImageNoKey(file);
        }

        long fileLen = file.length();
        if (fileLen < FAKE_HEADER_LENGTH) {
            Log.w(TAG, "文件太小: " + file.getName());
            return null;
        }
        if (fileLen > MAX_FILE_SIZE) {
            Log.w(TAG, "文件过大: " + file.getName());
            return null;
        }

        // 读取整个文件
        byte[] encrypted;
        try (FileInputStream fis = new FileInputStream(file)) {
            encrypted = new byte[(int) fileLen];
            int offset = 0;
            while (offset < encrypted.length) {
                int n = fis.read(encrypted, offset, encrypted.length - offset);
                if (n < 0) break;
                offset += n;
            }
        }

        // 跳过 Fake Header，解密剩余部分
        int dataLen = encrypted.length - FAKE_HEADER_LENGTH;
        byte[] decrypted = new byte[dataLen];
        System.arraycopy(encrypted, FAKE_HEADER_LENGTH, decrypted, 0, dataLen);
        xorData(decrypted, key);

        // 验证解密结果是否有效 PNG
        if (hasPNGSignature(decrypted)) {
            return decrypted;
        }

        // 如果带 header 验证失败，尝试无 header 的 XOR 解密
        // （某些工具可能移除了 header）
        byte[] xorFull = new byte[(int) fileLen];
        System.arraycopy(encrypted, 0, xorFull, 0, (int) fileLen);
        xorData(xorFull, key);

        if (hasPNGSignature(xorFull)) {
            return xorFull;
        }

        // 尝试在整个数据中寻找 PNG 签名
        int sigOffset = findPNGSignature(decrypted);
        if (sigOffset >= 0) {
            byte[] trimmed = new byte[decrypted.length - sigOffset];
            System.arraycopy(decrypted, sigOffset, trimmed, 0, trimmed.length);
            if (hasPNGSignature(trimmed)) return trimmed;
        }

        sigOffset = findPNGSignature(xorFull);
        if (sigOffset >= 0) {
            byte[] trimmed = new byte[xorFull.length - sigOffset];
            System.arraycopy(xorFull, sigOffset, trimmed, 0, trimmed.length);
            if (hasPNGSignature(trimmed)) return trimmed;
        }

        Log.w(TAG, "密钥解密后仍不是有效 PNG，密钥可能不正确");
        return null;
    }

    /**
     * 用密钥解密（基于 InputStream）
     */
    public static byte[] decryptStream(InputStream inputStream, byte[] key) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;

        // 跳过 Fake Header（用 read 而非 skip，SAF InputStream 的 skip() 可能不可靠）
        byte[] headerBuf = new byte[FAKE_HEADER_LENGTH];
        int headerRead = 0;
        while (headerRead < FAKE_HEADER_LENGTH) {
            int r = inputStream.read(headerBuf, headerRead, FAKE_HEADER_LENGTH - headerRead);
            if (r <= 0) break;
            headerRead += r;
        }

        while ((n = inputStream.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }

        byte[] data = baos.toByteArray();

        // 如果有密钥则 XOR 解密
        if (key != null && key.length > 0) {
            xorData(data, key);
        }

        // 验证
        if (hasPNGSignature(data)) return data;

        int sigOffset = findPNGSignature(data);
        if (sigOffset >= 0) {
            byte[] trimmed = new byte[data.length - sigOffset];
            System.arraycopy(data, sigOffset, trimmed, 0, trimmed.length);
            if (hasPNGSignature(trimmed)) return trimmed;
        }

        return null;
    }

    // ─── 密钥自动检测 ─────────────────────────────────────────────

    /**
     * 从 System.json 中提取 encryptionKey
     */
    public static String extractKeyFromSystemJson(File systemJsonFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(systemJsonFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int) systemJsonFile.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            String json = baos.toString("UTF-8");
            return extractKeyFromJson(json);
        }
    }

    /**
     * 从 JSON 字符串中提取 encryptionKey
     */
    public static String extractKeyFromJson(String json) {
        // 搜索 "encryptionKey": "xxx"
        String keyPattern = "\"encryptionKey\"\\s*:\\s*\"";
        int start = json.indexOf(keyPattern);
        if (start < 0) {
            // 尝试不引号的 key
            keyPattern = "\"encryptionKey\"\\s*:\\s*";
            start = json.indexOf(keyPattern);
            if (start < 0) return null;
        }

        start = json.indexOf('"', start + keyPattern.indexOf('"') + 1);
        if (start < 0) {
            // 可能没有引号
            start = json.indexOf(keyPattern) + keyPattern.length();
            int end = json.indexOf(',', start);
            if (end < 0) end = json.indexOf('}', start);
            if (end < 0) return null;
            return json.substring(start, end).trim().replaceAll("^\"|\"$", "");
        }
        start++; // 跳过开引号

        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /**
     * 从已加密的 PNG 文件中自动检测密钥
     *
     * 原理：已知 PNG 前 8 字节是固定签名，
     * 加密数据的前 8 字节 XOR PNG 签名 → 得到密钥的前 8 字节
     * 如果密钥长度足够（8 或 16 字节），用这部分密钥解密全文件
     * 再验证解密结果是否有效 PNG
     *
     * @param encryptedFile .png_ 文件
     * @return 检测到的密钥（hex string），或 null
     */
    public static String detectKeyFromFile(File encryptedFile) throws IOException {
        if (encryptedFile.length() < FAKE_HEADER_LENGTH + PNG_SIGNATURE.length) return null;

        byte[] header = new byte[FAKE_HEADER_LENGTH + PNG_SIGNATURE.length];
        try (FileInputStream fis = new FileInputStream(encryptedFile)) {
            int n = fis.read(header);
            if (n < header.length) return null;
        }

        // 跳过 Fake Header，取加密后的数据头部
        byte[] encryptedSig = new byte[PNG_SIGNATURE.length];
        System.arraycopy(header, FAKE_HEADER_LENGTH, encryptedSig, 0, PNG_SIGNATURE.length);

        // XOR 得到密钥前 8 字节
        byte[] keyPart = new byte[PNG_SIGNATURE.length];
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            keyPart[i] = (byte) (encryptedSig[i] ^ PNG_SIGNATURE[i]);
        }

        // 尝试 8 字节密钥
        byte[] fullData;
        try (FileInputStream fis = new FileInputStream(encryptedFile)) {
            fullData = new byte[(int) Math.min(encryptedFile.length(), MAX_FILE_SIZE)];
            int offset = 0;
            while (offset < fullData.length) {
                int n = fis.read(fullData, offset, fullData.length - offset);
                if (n < 0) break;
                offset += n;
            }
        }

        // 跳过头部的数据
        int dataLen = fullData.length - FAKE_HEADER_LENGTH;
        byte[] testData = new byte[dataLen];
        System.arraycopy(fullData, FAKE_HEADER_LENGTH, testData, 0, dataLen);

        // 尝试 8 字节密钥
        xorData(testData, keyPart);
        if (hasPNGSignature(testData)) {
            return bytesToHex(keyPart);
        }

        // 还原
        xorData(testData, keyPart);

        // 尝试把 8 字节补零到 16 字节
        byte[] key16 = new byte[16];
        System.arraycopy(keyPart, 0, key16, 0, keyPart.length);
        xorData(testData, key16);
        if (hasPNGSignature(testData)) {
            return bytesToHex(key16);
        }

        return null;
    }

    // ─── 批量处理 ─────────────────────────────────────────────────

    /**
     * 批量解密目录下的所有 .png_ 文件
     *
     * @param inputDir  输入目录
     * @param outputDir 输出目录
     * @param key       密钥（可为 null，此时走无密钥模式）
     * @param callback  进度回调
     * @return 成功解密数
     */
    public static int decryptDirectory(File inputDir, File outputDir, byte[] key, ProgressCallback callback) {
        File[] files = inputDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".png_"));
        if (files == null || files.length == 0) return 0;

        int success = 0;
        if (!outputDir.exists()) outputDir.mkdirs();

        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (callback != null && !callback.onProgress(f.getName(), i, files.length)) {
                break; // cancelled
            }

            String outName = f.getName().substring(0, f.getName().length() - 1); // .png_ → .png
            File outFile = new File(outputDir, outName);

            try {
                byte[] result;
                if (key != null && key.length > 0) {
                    result = decryptWithKey(f, key);
                } else {
                    result = restoreImageNoKey(f);
                }

                if (result != null) {
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(result);
                    }
                    success++;
                }
            } catch (IOException e) {
                Log.e(TAG, "解密失败: " + f.getName(), e);
            }
        }
        return success;
    }

    // ─── 工具方法 ─────────────────────────────────────────────────

    /**
     * XOR 解密/加密：data[i] ^= key[i % key.length]
     */
    public static void xorData(byte[] data, byte[] key) {
        if (key == null || key.length == 0) return;
        for (int i = 0; i < data.length; i++) {
            data[i] ^= key[i % key.length];
        }
    }

    public static boolean hasPNGSignature(byte[] data) {
        if (data.length < PNG_SIGNATURE.length) return false;
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) return false;
        }
        return true;
    }

    public static int findPNGSignature(byte[] data) {
        for (int i = 0; i < data.length - PNG_SIGNATURE.length; i++) {
            boolean match = true;
            for (int j = 0; j < PNG_SIGNATURE.length; j++) {
                if (data[i + j] != PNG_SIGNATURE[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    /**
     * 尝试修复破损的 PNG chunk 链
     */
    private static byte[] tryFixPNGChunkChain(byte[] data) {
        int sigOffset = findPNGSignature(data);
        if (sigOffset < 0) return null;

        int chunkStart = sigOffset + PNG_SIGNATURE.length;
        if (chunkStart >= data.length) return null;

        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length + 100);
        CRC32 crc = new CRC32();

        try {
            bos.write(PNG_SIGNATURE);

            while (chunkStart + 8 <= data.length) {
                if (chunkStart + 4 > data.length) break;
                int chunkLen = ((data[chunkStart] & 0xFF) << 24)
                        | ((data[chunkStart + 1] & 0xFF) << 16)
                        | ((data[chunkStart + 2] & 0xFF) << 8)
                        | (data[chunkStart + 3] & 0xFF);

                if (chunkLen < 0 || chunkLen > 50 * 1024 * 1024) {
                    chunkStart = scanToNextChunk(data, chunkStart + 1);
                    if (chunkStart < 0) break;
                    continue;
                }

                if (chunkStart + 8 > data.length) break;
                boolean validType = true;
                for (int i = 0; i < 4; i++) {
                    byte c = data[chunkStart + 4 + i];
                    if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                        validType = false;
                        break;
                    }
                }
                if (!validType) {
                    chunkStart = scanToNextChunk(data, chunkStart + 1);
                    if (chunkStart < 0) break;
                    continue;
                }

                int dataStart = chunkStart + 8;
                int dataEnd = Math.min(dataStart + chunkLen, data.length);
                int actualLen = dataEnd - dataStart;
                if (actualLen < 0) break;

                writeIntBE(bos, actualLen);
                bos.write(data, chunkStart + 4, 4);
                if (actualLen > 0) {
                    bos.write(data, dataStart, actualLen);
                }
                crc.reset();
                crc.update(data, chunkStart + 4, 4 + actualLen);
                writeIntBE(bos, (int) (crc.getValue() & 0xFFFFFFFF));

                String typeStr = new String(data, chunkStart + 4, 4, "ASCII");
                if (typeStr.equals("IEND")) break;

                chunkStart = dataStart + actualLen + 4;
            }
        } catch (IOException e) {
            return null;
        }

        return bos.toByteArray();
    }

    private static int scanToNextChunk(byte[] data, int startPos) {
        for (int i = startPos; i < data.length - 8; i++) {
            if (i + 4 > data.length) break;
            int chunkLen = ((data[i] & 0xFF) << 24) | ((data[i + 1] & 0xFF) << 16)
                    | ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            if (chunkLen < 0 || chunkLen > 50 * 1024 * 1024) continue;
            if (i + 8 + chunkLen > data.length) continue;

            boolean validType = true;
            for (int j = 0; j < 4; j++) {
                byte c = data[i + 4 + j];
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                    validType = false;
                    break;
                }
            }
            if (validType) return i;
        }
        return -1;
    }

    private static void writeIntBE(ByteArrayOutputStream bos, int val) {
        bos.write((byte) ((val >> 24) & 0xFF));
        bos.write((byte) ((val >> 16) & 0xFF));
        bos.write((byte) ((val >> 8) & 0xFF));
        bos.write((byte) (val & 0xFF));
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * 进度回调
     */
    public interface ProgressCallback {
        boolean onProgress(String fileName, int processed, int total);
    }
}
