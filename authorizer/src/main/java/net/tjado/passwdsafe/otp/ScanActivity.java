/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2013  Nathaniel McCallum, Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * CameraX port (c) 2026 Authorizer contributors, GPL-3.0.
 */
package net.tjado.passwdsafe.otp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import net.tjado.passwdsafe.R;
import net.tjado.passwdsafe.lib.PasswdSafeUtil;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scan an otpauth:// QR code with the camera. Returns the decoded URI in the
 * "uri" result extra.
 */
public class ScanActivity extends ComponentActivity
{
    private static final String TAG = "ScanActivity";

    private PreviewView itsPreview;
    private TextView itsErrorText;
    private ExecutorService itsAnalysisExecutor;
    private ProcessCameraProvider itsCameraProvider;
    private final AtomicBoolean itsDecoded = new AtomicBoolean(false);

    private final ActivityResultLauncher<String> itsCameraPermission =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            startCamera();
                        } else {
                            showError();
                        }
                    });

    public static boolean hasCamera(Context context)
    {
        PackageManager pm = context.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_otp_scan);
        itsPreview = findViewById(R.id.camera_view);
        itsErrorText = findViewById(R.id.textview);
        itsAnalysisExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            itsCameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        if (itsAnalysisExecutor != null) {
            itsAnalysisExecutor.shutdown();
        }
    }

    private void startCamera()
    {
        var providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                itsCameraProvider = providerFuture.get();
                bindCamera();
            } catch (Exception e) {
                PasswdSafeUtil.dbginfo(TAG, e, "camera provider");
                showError();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera()
    {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(itsPreview.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(itsAnalysisExecutor, this::analyze);

        try {
            itsCameraProvider.unbindAll();
            itsCameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
        } catch (Exception e) {
            PasswdSafeUtil.dbginfo(TAG, e, "camera bind");
            showError();
        }
    }

    /** Decode a QR code from the luminance (Y) plane of a camera frame */
    private void analyze(@NonNull ImageProxy image)
    {
        try {
            if (itsDecoded.get()) {
                return;
            }
            ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
            ByteBuffer buf = yPlane.getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            int rowStride = yPlane.getRowStride();
            int height = image.getHeight();
            int width = image.getWidth();

            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    data, rowStride, height, 0, 0, width, height, false);
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result result = new QRCodeReader().decode(
                    new BinaryBitmap(new HybridBinarizer(source)), hints);
            if ((result != null) && itsDecoded.compareAndSet(false, true)) {
                String text = result.getText();
                runOnUiThread(() -> onCodeScanned(text));
            }
        } catch (NotFoundException e) {
            // no code in this frame
        } catch (Exception e) {
            PasswdSafeUtil.dbginfo(TAG, e, "decode");
        } finally {
            image.close();
        }
    }

    private void onCodeScanned(String uri)
    {
        if (itsCameraProvider != null) {
            itsCameraProvider.unbindAll();
        }
        Intent resultIntent = new Intent();
        resultIntent.putExtra("uri", uri);
        setResult(Activity.RESULT_OK, resultIntent);
        finish();
    }

    private void showError()
    {
        itsErrorText.setVisibility(View.VISIBLE);
    }
}
