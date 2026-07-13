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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
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

    private TextView titleText;
    private TextView fileInfoText;
    private Button selectFilesButton;
    private Button selectOutputButton;
    private Button decryptButton;
    private ScrollView resultScroll;
    private TextView resultText;

    private final List<Uri> selectedFileUris = new ArrayList<>();
    private List<String> selectedFileNames = new ArrayList<>();
    private long totalSelectedSize = 0;

    private String outputDirPath;
    private Uri outputTreeUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        titleText = findViewById(R.id.titleText);
        fileInfoText = findViewById(R.id.fileInfoText);
        selectFilesButton = findViewById(R.id.selectFilesButton);
        selectOutputButton = findViewById(R.id.selectOutputButton);
        decryptButton = findViewById(R.id.decryptButton);
        resultScroll = findViewById(R.id.resultScroll);
        resultText = findViewById(R.id.resultText);

        selectFilesButton.setOnClickListener(v -> pickFiles());
        selectOutputButton.setOnClickListener(v -> pickOutputDir());
        decryptButton.setOnClickListener(v -> startDecryption());

        checkStoragePermission();

        // 默认输出到 Downloads/RPGMDecrypted
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            setDefaultOutputDir();
        }
    }

    private void setDefaultOutputDir() {
        File defaultDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "RPGMDecrypted");
        defaultDir.mkdirs();
        outputDirPath = defaultDir.getAbsolutePath();
        selectOutputButton.setText("输出目录: " + outputDirPath);
        updateDecryptButton();
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("需要存储权限")
                        .setMessage("解密后的文件需要保存到设备存储。")
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
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }, REQUEST_PERMISSION);
            }
        }
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // 允许选择 .png_ 文件和所有文件
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "application/octet-stream", "*/*"});
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_FILE_PICK) {
            selectedFileUris.clear();
            selectedFileNames = new ArrayList<>();
            totalSelectedSize = 0;

            if (data.getData() != null) {
                // 单个文件
                selectedFileUris.add(data.getData());
            } else if (data.getClipData() != null) {
                // 多个文件
                ClipData clipData = data.getClipData();
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri uri = clipData.getItemAt(i).getUri();
                    if (uri != null) selectedFileUris.add(uri);
                }
            }

            // 获取文件名和大小
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
                selectOutputButton.setText("输出目录: " + getDisplayPath(treeUri));
                updateDecryptButton();
            }
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

        // 过滤出 .png_ 文件
        long pngUnderCount = selectedFileNames.stream().filter(n -> n.toLowerCase().endsWith(".png_")).count();

        StringBuilder sb = new StringBuilder();
        sb.append("📄 已选 ").append(count).append(" 个文件");
        if (pngUnderCount < count) {
            sb.append("（其中 ").append(pngUnderCount).append(" 个 .png_）");
        }
        sb.append("\n总共 ").append(formatFileSize(totalSelectedSize));

        // 列出文件名（最多 5 个）
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
            decryptButton.setText("🛠 解密 " + selectedFileUris.size() + " 个文件");
        } else {
            decryptButton.setText("请选择文件");
        }
    }

    private void startDecryption() {
        if (selectedFileUris.isEmpty() || outputDirPath == null) return;

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

                    byte[] decrypted = RPGMDecrypter.decryptStream(is, fileName);
                    is.close();

                    if (decrypted == null) {
                        log.append("⚠️ [").append(time).append("] ").append(fileName).append(" — 无法解密（可能不是有效 .png_）\n");
                        failed++;
                        continue;
                    }

                    // 生成输出文件名：去掉 _ 后缀
                    String outName;
                    if (fileName.toLowerCase().endsWith(".png_")) {
                        outName = fileName.substring(0, fileName.length() - 1); // .png_ → .png
                    } else if (!fileName.toLowerCase().endsWith(".png")) {
                        outName = fileName + ".png";
                    } else {
                        outName = fileName;
                    }

                    // 保存到输出
                    if (useSAF) {
                        androidx.documentfile.provider.DocumentFile docDir =
                                androidx.documentfile.provider.DocumentFile.fromTreeUri(this, Uri.parse(outputDirPath));
                        if (docDir != null) {
                            androidx.documentfile.provider.DocumentFile outFile = docDir.createFile("image/png", outName);
                            if (outFile != null) {
                                try (FileOutputStream fos = (FileOutputStream) getContentResolver().openOutputStream(outFile.getUri())) {
                                    if (fos != null) {
                                        fos.write(decrypted);
                                        log.append("✅ [").append(time).append("] ").append(outName)
                                                .append(" (").append(formatFileSize(decrypted.length)).append(")\n");
                                        success++;
                                    } else {
                                        throw new Exception("Cannot open output stream");
                                    }
                                }
                            } else {
                                throw new Exception("Cannot create output file");
                            }
                        } else {
                            throw new Exception("Invalid output directory");
                        }
                    } else {
                        File outFile = new File(outputDirPath, outName);
                        File parent = outFile.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            fos.write(decrypted);
                        }
                        log.append("✅ [").append(time).append("] ").append(outName)
                                .append(" (").append(formatFileSize(decrypted.length)).append(")\n");
                        success++;
                    }

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
                summary.append("━━━ 解密完成 ━━━\n");
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setDefaultOutputDir();
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "存储权限被拒绝，将使用 SAF", Toast.LENGTH_LONG).show();
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
