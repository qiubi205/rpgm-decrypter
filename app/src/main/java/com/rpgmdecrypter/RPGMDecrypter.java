package com.rpgmdecrypter;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * RPG Maker MV / MZ .png_ 文件解密工具
 *
 * 加密机制（无密钥模式）：
 * RPG Maker MV/MZ 在 PNG 文件头部加入了一个 16 字节的伪造 header，
 * 称为 "RPG Maker MV 加密 Header"（或 Fake Header），结构如下：
 *
 *   偏移  大小  说明
 *   ───────────────────────────────
 *   0     4    "RPGM" → 0x5250474D 签名
 *   4     2    版本号 (MV = 0x0003, MZ = 0x0001)
 *   6     2    保留字段
 *   8     8    疑似文件时间戳或 GUID
 *
 * 解密方法（Image Only / No-Key 模式）：
 *   直接跳过前 16 字节，剩余部分即标准 PNG 文件（从 0x89504E47 签名开始）。
 *
 * 对于音频文件（.rpgmvm / .rpgmvo）需要密钥进行 XOR 解密，本工具暂不处理。
 */
public class RPGMDecrypter {

    private static final String TAG = "RPGMDecrypter";
    private static final int FAKE_HEADER_LENGTH = 16;
    private static final int PNG_HEADER_LENGTH = 8;
    private static final int MAX_PNG_SIZE = 100 * 1024 * 1024; // 100MB

    // PNG 文件签名
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    /**
     * 解密单个 .png_ 文件
     *
     * @param inputFile  加密的 .png_ 文件
     * @param outputFile 输出的 .png 文件
     * @return true 解密成功
     * @throws IOException 如果读取或写入失败
     */
    public static boolean decrypt(File inputFile, File outputFile) throws IOException {
        byte[] decrypted = decryptToBytes(inputFile);
        if (decrypted == null) return false;

        // 确保输出目录存在
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(decrypted);
        }
        return true;
    }

    /**
     * 解密 .png_ 文件并返回字节数组
     *
     * @param file 加密的 .png_ 文件
     * @return 解密后的 PNG 字节数组，或 null（无法解密/不是有效文件）
     * @throws IOException I/O 错误
     */
    public static byte[] decryptToBytes(File file) throws IOException {
        long fileLen = file.length();
        if (fileLen < FAKE_HEADER_LENGTH + PNG_HEADER_LENGTH) {
            Log.w(TAG, "File too small to be valid: " + file.getName() + " (" + fileLen + " bytes)");
            return null;
        }
        if (fileLen > MAX_PNG_SIZE) {
            Log.w(TAG, "File too large: " + file.getName());
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            // 1. 读取并验证 header
            byte[] header = new byte[FAKE_HEADER_LENGTH];
            int read = fis.read(header);
            if (read < FAKE_HEADER_LENGTH) {
                Log.w(TAG, "Cannot read header from: " + file.getName());
                return null;
            }

            // 验证是否为 RPGM 加密文件
            boolean hasRPGMHeader = (header[0] == 'R' && header[1] == 'P' && header[2] == 'G' && header[3] == 'M');
            int offset = hasRPGMHeader ? FAKE_HEADER_LENGTH : 0;

            // 2. 读取剩余数据（含 PNG 签名）
            int remaining = (int) (fileLen - offset);
            byte[] data = new byte[remaining];
            // 如果 offset=0, 需要合并之前读的 header
            if (offset == 0) {
                // 文件可能没有 Fake Header，直接读取全部内容
                // 但这种情况直接返回原始数据可能也有问题
                fis.close();
                try (FileInputStream fis2 = new FileInputStream(file)) {
                    byte[] raw = new byte[(int) fileLen];
                    fis2.read(raw);
                    return raw;
                }
            }

            int totalRead = 0;
            while (totalRead < remaining) {
                int n = fis.read(data, totalRead, remaining - totalRead);
                if (n < 0) break;
                totalRead += n;
            }

            // 3. 验证前 8 字节是否为 PNG 签名
            if (!verifyPNGSignature(data)) {
                Log.w(TAG, "Decrypted data does not have valid PNG signature: " + file.getName());
                // 尝试跳过额外字节找 PNG 签名
                int sigOffset = findPNGSignature(data);
                if (sigOffset >= 0) {
                    Log.i(TAG, "Found PNG signature at offset " + sigOffset + ", trimming...");
                    byte[] trimmed = new byte[data.length - sigOffset];
                    System.arraycopy(data, sigOffset, trimmed, 0, trimmed.length);
                    data = trimmed;
                } else {
                    // 修复可能的 chunk 问题
                    data = tryFixPNGChunkChain(data);
                    if (data == null || !verifyPNGSignature(data)) {
                        Log.e(TAG, "Cannot recover valid PNG from: " + file.getName());
                        return null;
                    }
                }
            }

            return data;
        }
    }

    /**
     * 批量解密 .png_ 文件
     *
     * @param inputDir  输入目录
     * @param outputDir 输出目录
     * @param callback  逐文件进度回调（可为 null）
     * @return 解密成功的文件数
     */
    public static int decryptDirectory(File inputDir, File outputDir, ProgressCallback callback) {
        File[] files = inputDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".png_"));
        if (files == null || files.length == 0) return 0;

        int success = 0;
        if (!outputDir.exists()) outputDir.mkdirs();

        for (File f : files) {
            if (callback != null && !callback.onProgress(f.getName(), success, files.length)) {
                break; // cancelled
            }
            String outName = f.getName().substring(0, f.getName().length() - 1); // .png_ → .png
            File outFile = new File(outputDir, outName);
            try {
                if (decrypt(f, outFile)) {
                    success++;
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to decrypt: " + f.getName(), e);
            }
        }
        return success;
    }

    /**
     * 解密 .png_ 文件并返回字节数组（基于 InputStream）
     */
    public static byte[] decryptStream(InputStream inputStream, String fileName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;

        // 跳过 16 字节 header
        long skipped = 0;
        while (skipped < FAKE_HEADER_LENGTH) {
            long s = inputStream.skip(FAKE_HEADER_LENGTH - skipped);
            if (s <= 0) break;
            skipped += s;
        }

        while ((n = inputStream.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }

        byte[] data = baos.toByteArray();

        // 验证 PNG 签名
        if (!verifyPNGSignature(data)) {
            int sigOffset = findPNGSignature(data);
            if (sigOffset >= 0) {
                byte[] trimmed = new byte[data.length - sigOffset];
                System.arraycopy(data, sigOffset, trimmed, 0, trimmed.length);
                data = trimmed;
            } else {
                Log.w(TAG, "Stream from " + fileName + " does not contain valid PNG");
                return null;
            }
        }

        return data;
    }

    // ====== 内部工具方法 ======

    /**
     * 验证字节数组是否以 PNG 签名开头
     */
    private static boolean verifyPNGSignature(byte[] data) {
        if (data.length < PNG_HEADER_LENGTH) return false;
        for (int i = 0; i < PNG_HEADER_LENGTH; i++) {
            if (data[i] != PNG_SIGNATURE[i]) return false;
        }
        return true;
    }

    /**
     * 在字节数组中查找 PNG 签名位置
     */
    private static int findPNGSignature(byte[] data) {
        for (int i = 0; i < data.length - PNG_HEADER_LENGTH; i++) {
            boolean match = true;
            for (int j = 0; j < PNG_HEADER_LENGTH; j++) {
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
     * （某些工具在移除 header 时可能破坏了 chunk 边界）
     */
    private static byte[] tryFixPNGChunkChain(byte[] data) {
        // 先找 PNG 签名
        int sigOffset = findPNGSignature(data);
        if (sigOffset < 0) return null;

        // 从签名后的第一个 chunk 开始
        int chunkStart = sigOffset + PNG_HEADER_LENGTH;
        if (chunkStart >= data.length) return null;

        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length + 100);
        CRC32 crc = new CRC32();

        try {
            bos.write(PNG_SIGNATURE);

            while (chunkStart + 8 <= data.length) {
                // 解析长度（4字节大端）
                if (chunkStart + 4 > data.length) break;
                int chunkLen = ((data[chunkStart] & 0xFF) << 24)
                        | ((data[chunkStart + 1] & 0xFF) << 16)
                        | ((data[chunkStart + 2] & 0xFF) << 8)
                        | (data[chunkStart + 3] & 0xFF);

                if (chunkLen < 0 || chunkLen > 50 * 1024 * 1024) {
                    // 非法长度，尝试重新扫描
                    chunkStart = scanToNextChunk(data, chunkStart + 1);
                    if (chunkStart < 0) break;
                    continue;
                }

                // 验证类型名是否合法（4个ASCII字母）
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

                // 数据区域
                int dataStart = chunkStart + 8;
                int dataEnd = Math.min(dataStart + chunkLen, data.length);
                int actualLen = dataEnd - dataStart;
                if (actualLen < 0) break;

                // 写入长度
                writeIntBE(bos, actualLen);
                // 写入类型
                bos.write(data, chunkStart + 4, 4);
                // 写入数据
                if (actualLen > 0) {
                    bos.write(data, dataStart, actualLen);
                }
                // 重新计算 CRC
                crc.reset();
                crc.update(data, chunkStart + 4, 4 + actualLen);
                writeIntBE(bos, (int) (crc.getValue() & 0xFFFFFFFF));

                String typeStr = new String(data, chunkStart + 4, 4, "ASCII");
                if (typeStr.equals("IEND")) break;

                // 跳转到下一个 chunk
                chunkStart = dataStart + actualLen + 4;
            }
        } catch (IOException e) {
            return null;
        }

        return bos.toByteArray();
    }

    /**
     * 从 startPos 向后扫描找到下一个合法 PNG chunk
     */
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

    /**
     * 进度回调接口
     */
    public interface ProgressCallback {
        /**
         * @param fileName   当前处理的文件名
         * @param processed  已处理的文件数
         * @param total      总文件数
         * @return true=继续, false=取消
         */
        boolean onProgress(String fileName, int processed, int total);
    }
}
