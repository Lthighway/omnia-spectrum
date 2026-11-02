package com.omnia.spectrum;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int ORANGE = Color.rgb(255, 157, 38);
    private static final int TEXT = Color.rgb(232, 235, 242);
    private static final int BG = Color.rgb(13, 17, 23);
    private static final int PANEL = Color.rgb(27, 33, 43);
    private static final int CAMERA_REQUEST = 43;
    private static final String HEADER = "timestamp,amostra,camera,modo,iso,exposicao_ms,x,y,sinal,vermelho,verde,azul,saturacao_pct,delta_off_on\n";

    private CameraManager cameraManager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder previewRequest;
    private Surface cameraSurface;
    private ImageReader jpegReader;
    private Size photoSize;
    private volatile boolean photoBusy = false;
    private volatile String pendingPhoto;
    private int jpegOrientation = 90;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final Handler uiHandler = new Handler();
    private TextureView preview;
    private TargetView targetView;
    private TextView status, measurements, help;
    private Button modeButton, cameraButton, gainButton;
    private int digitalGain = 0;
    private TextView isoLabel, exposureLabel;
    private SeekBar isoSeek, exposureSeek;
    private String cameraId;
    private boolean front = false, manual = true, active = false, opening = false;
    private int iso = 400;
    private long exposureNs = 20000000L;
    private Range<Integer> isoRange = new Range<>(100, 1600);
    private Range<Long> exposureRange = new Range<>(100000L, 100000000L);
    private Size previewSize;
    private float roiX = 0.5f, roiY = 0.5f;
    private Measurement latest;
    private Measurement baseline;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = label("OMNIA  SPECTRUM", 23, ORANGE, true);
        title.setPadding(dp(16), dp(16), dp(12), dp(2));
        root.addView(title);
        TextView subtitle = label("Laboratório óptico • NIR experimental • offline", 12, 0xFF9DA8B8, false);
        subtitle.setPadding(dp(16), 0, dp(12), dp(12));
        root.addView(subtitle);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        root.addView(frame, new LinearLayout.LayoutParams(-1, dp(320)));
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        targetView = new TargetView(this);
        frame.addView(targetView, new FrameLayout.LayoutParams(-1, -1));
        frame.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                roiX = Math.max(0.07f, Math.min(0.93f, event.getX() / Math.max(1, v.getWidth())));
                roiY = Math.max(0.07f, Math.min(0.93f, event.getY() / Math.max(1, v.getHeight())));
                targetView.invalidate();
                baseline = null;
                toast("Alvo definido. Refaça a referência OFF.");
                return true;
            }
            return true;
        });

        status = label("Aguardando câmera...", 13, ORANGE, false);
        status.setPadding(dp(14), dp(10), dp(14), dp(4));
        root.addView(status);
        measurements = label("Aponte para o LED e toque na região do emissor.", 15, TEXT, true);
        measurements.setPadding(dp(14), dp(6), dp(14), dp(8));
        root.addView(measurements);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(10), dp(6), dp(10), dp(18));
        root.addView(controls);

        LinearLayout row1 = new LinearLayout(this);
        cameraButton = button("CÂMERA: TRASEIRA", () -> { front = !front; baseline = null; closeCamera(); openCamera(); });
        modeButton = button("MODO: MANUAL", () -> { manual = !manual; updateModeText(); updateRequest(); });
        row1.addView(cameraButton, new LinearLayout.LayoutParams(0, dp(47), 1));
        row1.addView(modeButton, new LinearLayout.LayoutParams(0, dp(47), 1));
        controls.addView(row1);
        gainButton = button("REALCE: NATURAL", this::cycleDigitalGain);
        controls.addView(gainButton,new LinearLayout.LayoutParams(-1,dp(45)));
        LinearLayout boostRow = new LinearLayout(this);
        boostRow.addView(button("ISO + EXP. MÁX.", this::maximizeSensitivity), new LinearLayout.LayoutParams(0, dp(46), 1));
        boostRow.addView(button("RESTAURAR", this::restoreSensitivity), new LinearLayout.LayoutParams(0, dp(46), 1));
        controls.addView(boostRow);

        isoLabel=label("ISO", 12, ORANGE, true);
        controls.addView(isoLabel);
        isoSeek = new SeekBar(this);
        isoSeek.setMax(100);
        isoSeek.setProgress(33);
        isoSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(ORANGE));
        controls.addView(isoSeek);
        isoSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                iso = isoFromProgress(p);
                updateModeText();
                if (user) updateRequest();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        exposureLabel=label("EXPOSIÇÃO", 12, ORANGE, true);
        controls.addView(exposureLabel);
        exposureSeek = new SeekBar(this);
        exposureSeek.setMax(100);
        exposureSeek.setProgress(55);
        exposureSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(ORANGE));
        controls.addView(exposureSeek);
        exposureSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                exposureNs = exposureFromProgress(p);
                updateModeText();
                if (user) updateRequest();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(button("REFERÊNCIA OFF", () -> takeSample(false)), new LinearLayout.LayoutParams(0, dp(54), 1));
        row2.addView(button("AMOSTRA ON", () -> takeSample(true)), new LinearLayout.LayoutParams(0, dp(54), 1));
        controls.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.addView(button("FOTO NA GALERIA", () -> captureStillPhoto("CAPTURA")), new LinearLayout.LayoutParams(0, dp(48), 1));
        row3.addView(button("EXPORTAR CSV", this::exportCsv), new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(row3);

        help = label("REALCE DIGITAL altera a visualização, não a sensibilidade física; JPEG salvo permanece sem o realce.\nToque no LED para marcar a região. O modo ISO + EXP. MÁX. tenta revelar luz fraca, mas não amplia o espectro físico do sensor. Possível NIR residual; UV e infravermelho térmico não podem ser ativados por software.\nPara comparar OFF e ON, mantenha os controles idênticos.\nOs valores RGB não são medições espectrais calibradas.", 12, 0xFFAAB4C3, false);
        help.setPadding(dp(8), dp(12), dp(8), 0);
        controls.addView(help);
        updateModeText();
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }
    private Button button(String name, Runnable click) {
        Button b = new Button(this);
        b.setText(name);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setTextColor(ORANGE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PANEL));
        b.setOnClickListener(v -> click.run());
        return b;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        cameraThread = new HandlerThread("OmniaCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        if (preview != null && preview.isAvailable() && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
        uiHandler.post(sampler);
    }
    @Override protected void onPause() {
        active = false;
        uiHandler.removeCallbacks(sampler);
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            cameraThread = null;
            cameraHandler = null;
        }
        super.onPause();
    }
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == CAMERA_REQUEST) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) openCamera();
            else status.setText("Permissão da câmera negada.");
        }
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (active && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { closeCamera(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    private synchronized void openCamera() {
        if (!active || opening || camera != null || !preview.isAvailable() || cameraHandler == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        opening = true;
        try {
            String chosen = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    chosen = id; break;
                }
            }
            if (chosen == null) throw new IllegalStateException("Câmera solicitada indisponível");
            cameraId = chosen;
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(chosen);
            Range<Integer> ir = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> er = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (ir != null) isoRange = ir;
            if (er != null) exposureRange = er;
            int[] capabilities = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities == null || !contains(capabilities, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
                manual = false;
            }
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("Sem configuração de saída");
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) throw new IllegalStateException("Prévia não disponível");
            previewSize = chooseSize(sizes);
            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (jpegSizes == null || jpegSizes.length == 0) throw new IllegalStateException("JPEG indisponível");
            photoSize = chooseJpegSize(jpegSizes);
            Integer sensorRotation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            jpegOrientation = sensorRotation == null ? 90 : sensorRotation;
            jpegReader = ImageReader.newInstance(photoSize.getWidth(),photoSize.getHeight(),ImageFormat.JPEG,2);
            jpegReader.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireNextImage();
                    if (img == null) return;
                    String name = pendingPhoto;
                    pendingPhoto = null;
                    if (name != null) {
                        ByteBuffer data = img.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        writeJpegToGallery(name, bytes);
                    }
                } catch (Exception error) {
                    showStatus("Falha ao salvar JPEG: " + error.getMessage());
                } finally {
                    if (img != null) img.close();
                    photoBusy = false;
                }
            }, cameraHandler);
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) throw new IllegalStateException("Textura indisponível");
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            cameraSurface = new Surface(texture);
            runOnUiThread(() -> { cameraButton.setText(front ? "CÂMERA: FRONTAL" : "CÂMERA: TRASEIRA"); updateModeText(); });
            cameraManager.openCamera(chosen, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    camera = device;
                    opening = false;
                    createSession(device);
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close(); if (camera == device) camera = null; opening = false;
                    showStatus("Câmera desconectada");
                }
                @Override public void onError(CameraDevice device, int error) {
                    device.close(); if (camera == device) camera = null; opening = false;
                    showStatus("Erro na câmera: " + error);
                }
            }, cameraHandler);
        } catch (Exception e) {
            opening = false;
            showStatus("Falha: " + e.getMessage());
        }
    }
    private boolean contains(int[] haystack, int value) {
        for (int item : haystack) if (item == value) return true;
        return false;
    }
    private Size chooseSize(Size[] sizes) {
        for (Size s : sizes) if (s.getWidth() == 1280 && s.getHeight() == 720) return s;
        for (Size s : sizes) if (s.getWidth() == 1920 && s.getHeight() == 1080) return s;
        return Arrays.stream(sizes).filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
                .max((a,b) -> Long.compare((long)a.getWidth()*a.getHeight(), (long)b.getWidth()*b.getHeight()))
                .orElse(sizes[0]);
    }
    private Size chooseJpegSize(Size[] candidates) {
        Size best = null;
        for (Size candidate : candidates) {
            long pixels = (long)candidate.getWidth() * candidate.getHeight();
            if (pixels <= 16000000 && (best == null ||
                pixels > (long)best.getWidth() * best.getHeight())) best = candidate;
        }
        if (best != null) return best;
        return candidates[0];
    }
    private void createSession(CameraDevice device) {
        try {
            previewRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(cameraSurface);
            device.createCaptureSession(Arrays.asList(cameraSurface,jpegReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (camera != device) { s.close(); return; }
                    session = s;
                    updateRequest();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { showStatus("Falha ao configurar prévia"); }
            }, cameraHandler);
        } catch (CameraAccessException e) { showStatus("Erro Camera2: " + e.getMessage()); }
    }
    private void updateRequest() {
        Handler h = cameraHandler;
        if (h == null) return;
        h.post(() -> {
            if (camera == null || session == null || previewRequest == null) return;
            try {
                previewRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                previewRequest.set(CaptureRequest.CONTROL_AE_MODE, manual ? CaptureRequest.CONTROL_AE_MODE_OFF : CaptureRequest.CONTROL_AE_MODE_ON);
                if (manual) {
                    previewRequest.set(CaptureRequest.SENSOR_SENSITIVITY, Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), iso)));
                    previewRequest.set(CaptureRequest.SENSOR_EXPOSURE_TIME, Math.max(exposureRange.getLower(), Math.min(exposureRange.getUpper(), exposureNs)));
                }
                session.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
                showStatus((front ? "FRONTAL" : "TRASEIRA") + " • " + (manual ? ("ISO " + iso + " • " + String.format(Locale.US,"%.2f ms",exposureNs / 1000000.0)) : "EXPOSIÇÃO AUTOMÁTICA"));
            } catch (Exception e) { showStatus("Configuração indisponível: " + e.getMessage()); }
        });
    }
    private int isoFromProgress(int p) {
        double lower = isoRange.getLower(), upper = isoRange.getUpper();
        return (int) Math.round(Math.exp(Math.log(lower) + Math.log(upper / lower) * p / 100.0));
    }
    private long exposureFromProgress(int p) {
        double lower = Math.max(exposureRange.getLower(), 100000L);
        double upper = Math.max(lower, Math.min(exposureRange.getUpper(), 1000000000L));
        return (long) Math.exp(Math.log(lower) + Math.log(upper/lower)*p/100.0);
    }
    private void cycleDigitalGain() {
        digitalGain = (digitalGain + 1) % 4;
        float[] levels = {1f, 1.5f, 2.5f, 4f};
        float factor = levels[digitalGain];
        String[] labels = {"NATURAL", "1,5×", "2,5×", "4×"};
        gainButton.setText("REALCE DIGITAL: " + labels[digitalGain]);
        Paint paint = new Paint();
        ColorMatrix matrix = new ColorMatrix(new float[] {
            factor,0,0,0,0,
            0,factor,0,0,0,
            0,0,factor,0,0,
            0,0,0,1,0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        preview.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
        toast("Realce do visor: " + labels[digitalGain] + ". Foto original preservada.");
    }

    private void maximizeSensitivity() {
        manual = true;
        isoSeek.setProgress(100);
        exposureSeek.setProgress(100);
        updateModeText();
        updateRequest();
        toast("ISO e exposição máximos permitidos no aplicativo. Apoie o aparelho.");
    }
    private void restoreSensitivity() {
        manual = true;
        isoSeek.setProgress(40);
        exposureSeek.setProgress(45);
        updateModeText();
        updateRequest();
    }

    private void updateModeText() {
        if (modeButton != null) modeButton.setText(manual ? "MODO: MANUAL" : "MODO: AUTO");
        if (isoSeek != null) isoSeek.setEnabled(manual);
        if (exposureSeek != null) exposureSeek.setEnabled(manual);
        if (isoLabel != null) isoLabel.setText("ISO: " + iso + " (sensor " + isoRange.getLower() + "–" + isoRange.getUpper() + ")");
        if (exposureLabel != null) exposureLabel.setText(String.format(Locale.US,"EXPOSIÇÃO: %.2f ms (até %.0f ms no sensor)",exposureNs/1000000.0,exposureRange.getUpper()/1000000.0));
    }
    private void showStatus(String s) { runOnUiThread(() -> { if (status != null) status.setText(s); }); }
    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (preview != null && preview.isAvailable() && camera != null) {
                try {
                    Bitmap b = preview.getBitmap(240, 240);
                    if (b != null) {
                        latest = measure(b);
                        b.recycle();
                        String line = String.format(Locale.getDefault(),
                                "SINAL %.1f / 255    RGB +%.0f / +%.0f / +%.0f\nSaturação %.1f%%    %s",
                                latest.signal, latest.r, latest.g, latest.b, latest.saturation,
                                baseline == null ? "Referência OFF não definida" : String.format(Locale.getDefault(),"Δ ON−OFF: %.1f", latest.signal - baseline.signal));
                        measurements.setText(line);
                    }
                } catch (Exception ignored) {}
            }
            uiHandler.postDelayed(this, 500);
        }
    };

    private Measurement measure(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int cx = Math.round(roiX * (w-1)), cy = Math.round(roiY * (h-1));
        double cr=0,cg=0,cb=0, br=0,bg=0,bb=0;
        int cn=0,bn=0,sat=0;
        // Central LED region and surrounding annulus, identical size in pixel coordinates.
        int inner = Math.max(3, Math.min(w,h)/34);
        int outer = inner*3;
        for (int y=Math.max(0,cy-outer);y<=Math.min(h-1,cy+outer);y++) {
            for (int x=Math.max(0,cx-outer);x<=Math.min(w-1,cx+outer);x++) {
                int dx=x-cx, dy=y-cy, d2=dx*dx+dy*dy;
                int color = bitmap.getPixel(x,y);
                int r=Color.red(color), g=Color.green(color), b=Color.blue(color);
                if (d2 <= inner*inner) {
                    cr+=r;cg+=g;cb+=b;cn++;
                    if (r >= 250 || g >= 250 || b >= 250) sat++;
                } else if (d2 >= inner*inner*4 && d2 <= outer*outer) {
                    br+=r;bg+=g;bb+=b;bn++;
                }
            }
        }
        if (cn==0 || bn==0) return new Measurement();
        Measurement m = new Measurement();
        m.r=cr/cn-br/bn;m.g=cg/cn-bg/bn;m.b=cb/cn-bb/bn;
        m.signal=Math.max(0,0.2126*m.r+0.7152*m.g+0.0722*m.b);
        m.saturation=100.0*sat/cn;
        return m;
    }
    private void takeSample(boolean on) {
        if (latest == null) { toast("Aguarde a câmera iniciar."); return; }
        if (!manual) { toast("Para comparação, ative o modo manual."); return; }
        Measurement sample = latest.copy();
        if (!on) {
            baseline = sample;
            toast("Referência OFF registrada.");
        } else if (baseline == null) {
            toast("Registre a referência OFF primeiro."); return;
        } else {
            toast(String.format(Locale.getDefault(),"Sinal diferencial: %.1f / 255",sample.signal-baseline.signal));
        }
        recordCsv(on ? "ON" : "OFF", sample, on ? sample.signal-baseline.signal : 0);
        captureStillPhoto(on ? "IR_ON" : "IR_OFF");
    }
    private void recordCsv(String name, Measurement m, double delta) {
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date());
        String data=String.format(Locale.US,"%s,%s,%s,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,%.3f\n",
                when,name,front?"front":"back",manual?"manual":"auto",
                iso,exposureNs/1000000.0,roiX,roiY,m.signal,m.r,m.g,m.b,m.saturation,delta);
        File f = new File(getFilesDir(),"measurements.csv");
        try (FileOutputStream output = new FileOutputStream(f,true)) {
            if (f.length()==0) output.write(HEADER.getBytes(StandardCharsets.UTF_8));
            output.write(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { toast("Falha no registro: " + e.getMessage()); }
    }
    private void captureStillPhoto(String prefix) {
        if (cameraHandler == null) { toast("Câmera indisponível"); return; }
        cameraHandler.post(() -> {
            if (camera == null || session == null || jpegReader == null) {
                showStatus("Câmera não está pronta."); return;
            }
            if (photoBusy) { showStatus("Aguarde a fotografia anterior."); return; }
            photoBusy = true;
            pendingPhoto = prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date()) + ".jpg";
            try {
                CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                req.addTarget(jpegReader.getSurface());
                req.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                req.set(CaptureRequest.CONTROL_AE_MODE,manual ? CaptureRequest.CONTROL_AE_MODE_OFF : CaptureRequest.CONTROL_AE_MODE_ON);
                if (manual) {
                    req.set(CaptureRequest.SENSOR_SENSITIVITY,Math.max(isoRange.getLower(),Math.min(isoRange.getUpper(),iso)));
                    req.set(CaptureRequest.SENSOR_EXPOSURE_TIME,Math.max(exposureRange.getLower(),Math.min(exposureRange.getUpper(),exposureNs)));
                }
                req.set(CaptureRequest.JPEG_ORIENTATION,jpegOrientation);
                req.set(CaptureRequest.JPEG_QUALITY,(byte)95);
                session.capture(req.build(),new CameraCaptureSession.CaptureCallback(){
                    @Override public void onCaptureFailed(CameraCaptureSession sess,CaptureRequest request,android.hardware.camera2.CaptureFailure failure) {
                        photoBusy=false;pendingPhoto=null;
                        showStatus("Falha de captura Camera2");
                    }
                },cameraHandler);
                showStatus("Fotografando em " + photoSize.getWidth() + " × " + photoSize.getHeight() + "...");
            } catch(Exception e) {
                photoBusy=false;pendingPhoto=null;
                showStatus("Falha de captura: " + e.getMessage());
            }
        });
    }

    private void writeJpegToGallery(String name, byte[] jpeg) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,name);
            values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES + "/OMNIA Spectrum");
            values.put(MediaStore.Images.Media.IS_PENDING,1);
            Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(uri==null)throw new IllegalStateException("Galeria indisponível");
            try {
                try(OutputStream out=getContentResolver().openOutputStream(uri)){
                    if(out==null)throw new IllegalStateException("Arquivo indisponível");
                    out.write(jpeg);
                }
                ContentValues done=new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING,0);
                getContentResolver().update(uri,done,null,null);
            } catch(Exception error) {
                getContentResolver().delete(uri,null,null);
                throw error;
            }
        } else {
            File dir=getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if(dir==null)throw new IllegalStateException("Diretório indisponível");
            try(OutputStream out=new FileOutputStream(new File(dir,name))){out.write(jpeg);}
        }
        showStatus("Foto salva na galeria: " + name + " (" + jpeg.length / 1024 + " KB)");
    }

    private void exportCsv() {
        File f=new File(getFilesDir(),"measurements.csv");
        if (!f.exists()) { toast("Nenhuma medição registrada"); return; }
        String filename="omnia_spectrum_" + new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".csv";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v=new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME,filename);
                v.put(MediaStore.Downloads.MIME_TYPE,"text/csv");
                v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
                Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
                if (uri==null) throw new IllegalStateException("Downloads indisponível");
                try (FileInputStream in=new FileInputStream(f);OutputStream out=getContentResolver().openOutputStream(uri)) {
                    if (out==null) throw new IllegalStateException("Saída indisponível");
                    byte[] buffer=new byte[8192];int n;
                    while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
                }
            } else {
                File outFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),filename);
                try (FileInputStream in=new FileInputStream(f);FileOutputStream out=new FileOutputStream(outFile)) {
                    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
                }
            }
            toast("CSV exportado: " + filename);
        } catch(Exception e) { toast("Falha na exportação: "+e.getMessage()); }
    }
    private synchronized void closeCamera() {
        opening=false;
        photoBusy=false; pendingPhoto=null;
        try { if(session!=null) session.close(); } catch(Exception ignored) {}
        session=null; previewRequest=null;
        try { if(camera!=null) camera.close(); } catch(Exception ignored) {}
        camera=null;
        if(cameraSurface!=null) {cameraSurface.release();cameraSurface=null;}
        if(jpegReader!=null) {jpegReader.close();jpegReader=null;}
    }
    private static class Measurement {
        double signal,r,g,b,saturation;
        Measurement copy(){Measurement m=new Measurement();m.signal=signal;m.r=r;m.g=g;m.b=b;m.saturation=saturation;return m;}
    }
    private class TargetView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        TargetView(Context c){super(c);setWillNotDraw(false);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float x=roiX*getWidth(),y=roiY*getHeight();
            p.setColor(ORANGE);p.setStrokeWidth(dp(2));p.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(x,y,dp(24),p);
            canvas.drawLine(x-dp(38),y,x-dp(28),y,p);
            canvas.drawLine(x+dp(28),y,x+dp(38),y,p);
            canvas.drawLine(x,y-dp(38),x,y-dp(28),p);
            canvas.drawLine(x,y+dp(28),x,y+dp(38),p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
