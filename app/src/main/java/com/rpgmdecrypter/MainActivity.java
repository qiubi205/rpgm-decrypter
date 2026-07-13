package com.rpgmdecrypter;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_FILE_PICK = 1001;
    private static final int REQUEST_OUTPUT_PICK = 1002;
    private static final int REQUEST_PERMISSION = 1003;
    private static final int REQUEST_MANAGE_STORAGE = 1004;
    private static final int REQUEST_KEY_FILE_PICK = 1005;

    private Button selectFilesButton;
    private Button selectOutputButton;
    private Button decryptButton;
    private Button detectKeyButton;
    private Button loadKeyFromFileButton;
    private EditText keyInput;
    private TextView fileInfoText;
    private TextView resultText;
    private ScrollView resultScroll;

    private final List<Uri> selectedFileUris = new ArrayList<>();
    private List<String> selectedFileNames = new ArrayList<>();
    private long totalSelectedSize = 0;

    private String outputDirPath;
    private Uri outputTreeUri;
    private byte[] keyBytes; // 当前密钥

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        selectFilesButton = findViewById(R.id.selectFilesButton);
        selectOutputButton = findViewById(R.id.selectOutputButton);
        decryptButton = findViewById(R.id.decryptButton);
        detectKeyButton = findViewById(R.id.detectKeyButton);
        loadKeyFromFileButton = findViewById(R.id.loadKeyFromFileButton);
        keyInput = findViewById(R.id.keyInput);
        fileInfoText = findViewById(R.id.fileInfoText);
        resultText = findViewById(R.id.resultText);
        resultScroll = findViewById(R.id.resultScroll);

        selectFilesButton.setOnClickListener(v -> pickFiles());
        selectOutputButton.setOnClickListener(v -> pickOutputDir());
        decryptButton.setOnClickListener(v -> startDecryption());
        detectKeyButton.setOnClickListener(v -> detectKeyFromSelectedFile());
        loadKeyFromFileButton.setOnClickListener(v -> pickKeyFile());

        checkStoragePermission();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            setDefaultOutputDir();
        }
    }

    private void setDefaultOutputDir() {
        File defaultDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "RPGMDecrypted");
        defaultDir.mkdirs();
        outputDirPath = defaultDir.getAbsolutePath();
        selectOutputButton.setText("📁 " + outputDirPath);
        updateDecryptButton();
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("需要存储权限")
                        .setMessage("解密后的图片和 System.json 文件需要读取权限。")
                        .setPositiveButton("去授权", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                        })
                        .setNegativeButton("用 SAF", (d, w) -> pickOutputDir())
                        .show();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_PERMISSION);
            }
        }
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_FILE_PICK);
    }

    private void pickOutputDir() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQUEST_OUTPUT_PICK);
        } else {
            setDefaultOutputDir();
        }
    }

    private void pickKeyFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        // 有些 System.json 可能被识别为 text
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "*/*"});
        startActivityForResult(intent, REQUEST_KEY_FILE_PICK);
    }

    private void detectKeyFromSelectedFile() {
        if (selectedFileUris.isEmpty() || selectedFileUris.size() != 1) {
            Toast.makeText(this, "请先选择 **一个** .png_ 文件用于密钥检测", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("正在检测密钥…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            try {
                // 临时复制到缓存目录
                File tempFile = new File(getCacheDir(), "detect_temp.png_");
                try (InputStream is = getContentResolver().openInputStream(selectedFileUris.get(0));
                     FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }

                String keyHex = RPGMDecrypter.detectKeyFromFile(tempFile);
                tempFile.delete();

                runOnUiThread(() -> {
                    progress.dismiss();
                    if (keyHex != null) {
                        keyInput.setText(keyHex);
                        keyBytes = RPGMDecrypter.hexToBytes(keyHex);
                        appendResult("🔑 自动检测到密钥: " + keyHex + "\n");
                        Toast.makeText(MainActivity.this, "密钥检测成功！", Toast.LENGTH_SHORT).show();
                    } else {
                        appendResult("⚠️ 无法自动检测密钥\n");
                        Toast.makeText(MainActivity.this, "密钥检测失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    appendResult("❌ 密钥检测出错: " + e.getMessage() + "\n");
                });
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_FILE_PICK) {
            selectedFileUris.clear();
            selectedFileNames = new ArrayList<>();
            totalSelectedSize = 0;

            if (data.getData() != null) {
                selectedFileUris.add(data.getData());
            } else if (data.getClipData() != null) {
                ClipData clipData = data.getClipData();
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri uri = clipData.getItemAt(i).getUri();
                    if (uri != null) selectedFileUris.add(uri);
                }
            }

            for (Uri uri : selectedFileUris) {
                try (android.database.Cursor cursor = getContentResolver().query(
                        uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        int sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                        if (nameIdx >= 0) selectedFileNames.add(cursor.getString(nameIdx));
                        else selectedFileNames.add(uri.getLastPathSegment());
                        if (sizeIdx >= 0) totalSelectedSize += cursor.getLong(sizeIdx);
                    }
                } catch (Exception e) {
                    selectedFileNames.add(uri.getLastPathSegment());
                }
            }

            updateFileInfo();
            updateDecryptButton();

        } else if (requestCode == REQUEST_OUTPUT_PICK) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                final int takeFlags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                outputTreeUri = treeUri;
                outputDirPath = treeUri.toString();
                selectOutputButton.setText("📁 " + getDisplayPath(treeUri));
                updateDecryptButton();
            }
        } else if (requestCode == REQUEST_KEY_FILE_PICK) {
            // 从 System.json 加载密钥
            Uri jsonUri = data.getData();
            if (jsonUri == null) return;

            new Thread(() -> {
                try {
                    InputStream is = getContentResolver().openInputStream(jsonUri);
                    if (is == null) {
                        runOnUiThread(() -> Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                    is.close();

                    String json = baos.toString("UTF-8");
                    String key = RPGMDecrypter.extractKeyFromJson(json);

                    runOnUiThread(() -> {
                        if (key != null && !key.isEmpty()) {
                            keyInput.setText(key);
                            keyBytes = RPGMDecrypter.hexToBytes(key);
                            appendResult("🔑 从 System.json 加载密钥: " + key + "\n");
                            Toast.makeText(this, "密钥加载成功！", Toast.LENGTH_SHORT).show();
                        } else {
                            appendResult("⚠️ 文件中未找到 encryptionKey 字段\n");
                            Toast.makeText(this, "未找到密钥", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        appendResult("❌ 读取密钥文件失败: " + e.getMessage() + "\n");
                    });
                }
            }).start();

        } else if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    setDefaultOutputDir();
                }
            }
        }
    }

    private String getDisplayPath(Uri uri) {
        String path = uri.getPath();
        if (path != null) {
            String[] parts = path.split(":");
            return parts.length > 1 ? parts[1] : "/";
        }
        return "已选择";
    }

    private void updateFileInfo() {
        int count = selectedFileUris.size();
        if (count == 0) {
            fileInfoText.setText("未选择文件");
            return;
        }

        long pngUnderCount = 0;
        for (String name : selectedFileNames) {
            if (name.toLowerCase().endsWith(".png_")) pngUnderCount++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📄 ").append(count).append(" 个文件");
        if (pngUnderCount < count) {
            sb.append("（").append(pngUnderCount).append(" 个 .png_）");
        }
        sb.append("  | 共 ").append(formatFileSize(totalSelectedSize));

        int show = Math.min(count, 5);
        for (int i = 0; i < show; i++) {
            sb.append("\n  • ").append(selectedFileNames.get(i));
        }
        if (count > 5) {
            sb.append("\n  … 还有 ").append(count - 5).append(" 个");
        }

        fileInfoText.setText(sb.toString());
    }

    private void updateDecryptButton() {
        decryptButton.setEnabled(!selectedFileUris.isEmpty() && outputDirPath != null);
        if (decryptButton.isEnabled()) {
            String mode = (keyInput.getText().toString().trim().isEmpty()) ? "无密钥" : "有密钥";
            decryptButton.setText("🛠 解密 " + selectedFileUris.size() + " 个文件 [" + mode + "]");
        } else {
            decryptButton.setText("请选择文件和输出目录");
        }
    }

    private void startDecryption() {
        if (selectedFileUris.isEmpty() || outputDirPath == null) return;

        // 读取密钥
        String keyText = keyInput.getText().toString().trim();
        final byte[] keyFinal;
        if (!keyText.isEmpty()) {
            keyFinal = RPGMDecrypter.hexToBytes(keyText);
        } else {
            keyFinal = null;
        }

        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("正在解密…");
        progress.setIndeterminate(false);
        progress.setMax(selectedFileUris.size());
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setCancelable(false);
        progress.show();

        resultText.setText("");
        resultScroll.setVisibility(View.VISIBLE);

        new Thread(() -> {
            int success = 0;
            int failed = 0;
            StringBuilder log = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            boolean useSAF = outputDirPath.startsWith("content://");

            for (int i = 0; i < selectedFileUris.size(); i++) {
                Uri uri = selectedFileUris.get(i);
                String fileName = selectedFileNames.get(i);
                String time = sdf.format(new Date());

                final int progressI = i;
                runOnUiThread(() -> progress.setProgress(progressI));

                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    if (is == null) {
                        log.append("❌ [").append(time).append("] ").append(fileName).append(" — 无法读取\n");
                        failed++;
                        continue;
                    }

                    byte[] decrypted = RPGMDecrypter.decryptStream(is, keyFinal);
                    is.close();

                    if (decrypted == null) {
                        // 尝试无密钥模式
                        log.append("⚠️ [").append(time).append("] ").append(fileName)
                                .append(" — 解密后非有效 PNG（密钥可能不正确）\n");
                        failed++;
                        continue;
                    }

                    String outName;
                    if (fileName.toLowerCase().endsWith(".png_")) {
                        outName = fileName.substring(0, fileName.length() - 1);
                    } else if (!fileName.toLowerCase().endsWith(".png")) {
                        outName = fileName + ".png";
                    } else {
                        outName = fileName;
                    }

                    if (useSAF) {
                        androidx.documentfile.provider.DocumentFile docDir =
                                androidx.documentfile.provider.DocumentFile.fromTreeUri(this, Uri.parse(outputDirPath));
                        if (docDir != null) {
                            androidx.documentfile.provider.DocumentFile outFile = docDir.createFile("image/png", outName);
                            if (outFile != null) {
                                try (FileOutputStream fos = (FileOutputStream) getContentResolver().openOutputStream(outFile.getUri())) {
                                    if (fos != null) {
                                        fos.write(decrypted);
                                    }
                                }
                            }
                        }
                    } else {
                        File outFile = new File(outputDirPath, outName);
                        File parent = outFile.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            fos.write(decrypted);
                        }
                    }

                    log.append("✅ [").append(time).append("] ").append(outName)
                            .append(" (").append(formatFileSize(decrypted.length)).append(")\n");
                    success++;

                } catch (Exception e) {
                    log.append("❌ [").append(time).append("] ").append(fileName).append(" — ")
                            .append(e.getMessage()).append("\n");
                    failed++;
                }
            }

            final int finalSuccess = success;
            final int finalFailed = failed;
            final String logStr = log.toString();

            runOnUiThread(() -> {
                progress.dismiss();

                StringBuilder summary = new StringBuilder();
                String mode = (keyFinal != null) ? "🔑 有密钥模式" : "🔓 无密钥模式（跳过头部）";
                summary.append("━━━ ").append(mode).append(" ━━━\n");
                summary.append("✅ 成功: ").append(finalSuccess).append(" 个\n");
                if (finalFailed > 0) {
                    summary.append("❌ 失败: ").append(finalFailed).append(" 个\n");
                }

                String dirDisplay = useSAF ? getDisplayPath(Uri.parse(outputDirPath)) : outputDirPath;
                summary.append("📁 输出至: ").append(dirDisplay).append("\n\n");
                summary.append(logStr);

                resultText.setText(summary.toString());
                resultScroll.post(() -> resultScroll.fullScroll(View.FOCUS_DOWN));

                String msg = "解密完成: " + finalSuccess + " 个成功";
                if (finalFailed > 0) msg += ", " + finalFailed + " 个失败";
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void appendResult(String line) {
        runOnUiThread(() -> {
            resultText.append(line);
            resultScroll.setVisibility(View.VISIBLE);
            resultScroll.post(() -> resultScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setDefaultOutputDir();
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "存储权限被拒绝", Toast.LENGTH_LONG).show();
            }
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB"};
        int unitIdx = (int) (Math.log10(bytes) / Math.log10(1024));
        if (unitIdx >= units.length) unitIdx = units.length - 1;
        double size = bytes / Math.pow(1024, unitIdx);
        DecimalFormat df = new DecimalFormat("#.##");
        return df.format(size) + " " + units[unitIdx];
    }
}
