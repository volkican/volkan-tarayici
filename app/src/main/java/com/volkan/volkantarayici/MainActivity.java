package com.volkan.volkantarayici;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_VPN_PERMISSION = 501;
    private static final int REQUEST_VPN_PROFILE = 502;
    private static final int REQUEST_WEB_FILE = 503;
    private static final String HOME_URL = "https://duckduckgo.com/";
    private static final String PREFS = "volkan_browser_private";
    private static final String PREF_VPN_CONFIG = "vpn_config";

    private static GeckoRuntime runtime;

    private final ExecutorService vpnExecutor = Executors.newSingleThreadExecutor();
    private final BrowserTunnel tunnel = new BrowserTunnel();

    private GeckoSession session;
    private GeckoView geckoView;
    private EditText addressBar;
    private Button backButton;
    private Button forwardButton;
    private Button vpnButton;
    private ProgressBar progressBar;
    private GoBackend vpnBackend;
    private Config vpnConfig;
    private volatile Tunnel.State vpnState = Tunnel.State.DOWN;

    private GeckoSession.PromptDelegate.FilePrompt pendingFilePrompt;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pendingFileResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vpnBackend = new GoBackend(getApplicationContext());
        buildInterface();
        openBrowser();
        restoreVpnProfile();
    }

    private void buildInterface() {
        final int cream = Color.rgb(244, 241, 234);
        final int green = Color.rgb(32, 75, 58);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cream);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = 0;
            int bottom = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(8), dp(6), dp(8), dp(3));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setHint("Adres veya arama");
        addressBar.setTextSize(16);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setOnEditorActionListener((TextView view, int actionId, KeyEvent event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadFromAddressBar();
                return true;
            }
            return false;
        });
        addressRow.addView(addressBar, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button goButton = makeButton("Git", green);
        goButton.setOnClickListener(view -> loadFromAddressBar());
        addressRow.addView(goButton, fixedButtonParams(68));

        vpnButton = makeButton("VPN: Profil", Color.rgb(110, 78, 35));
        vpnButton.setOnClickListener(view -> onVpnButtonPressed());
        vpnButton.setOnLongClickListener(view -> {
            if (vpnState == Tunnel.State.UP) {
                toast("Önce VPN bağlantısını kapatın.");
            } else {
                chooseVpnProfile();
            }
            return true;
        });
        addressRow.addView(vpnButton, fixedButtonParams(118));
        root.addView(addressRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout navigationRow = new LinearLayout(this);
        navigationRow.setOrientation(LinearLayout.HORIZONTAL);
        navigationRow.setGravity(Gravity.CENTER_VERTICAL);
        navigationRow.setPadding(dp(8), 0, dp(8), dp(4));

        backButton = makeButton("←", green);
        backButton.setEnabled(false);
        backButton.setOnClickListener(view -> session.goBack());
        navigationRow.addView(backButton, fixedButtonParams(58));

        forwardButton = makeButton("→", green);
        forwardButton.setEnabled(false);
        forwardButton.setOnClickListener(view -> session.goForward());
        navigationRow.addView(forwardButton, fixedButtonParams(58));

        Button reloadButton = makeButton("Yenile", green);
        reloadButton.setOnClickListener(view -> session.reload());
        navigationRow.addView(reloadButton, fixedButtonParams(86));

        Button homeButton = makeButton("Ana sayfa", green);
        homeButton.setOnClickListener(view -> loadUrl(HOME_URL));
        navigationRow.addView(homeButton, fixedButtonParams(105));

        TextView note = new TextView(this);
        note.setText("Tek sayfa • düşük bellek");
        note.setTextColor(Color.DKGRAY);
        note.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        note.setTextSize(13);
        navigationRow.addView(note, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(navigationRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(green));
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        geckoView = new GeckoView(this);
        root.addView(geckoView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void openBrowser() {
        if (runtime == null) {
            runtime = GeckoRuntime.create(getApplicationContext());
        }

        session = new GeckoSession();
        session.setContentDelegate(new GeckoSession.ContentDelegate() {});
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession activeSession, String url) {
                progressBar.setVisibility(View.VISIBLE);
                if (!addressBar.hasFocus()) {
                    addressBar.setText(url);
                }
            }

            @Override
            public void onProgressChange(GeckoSession activeSession, int progress) {
                progressBar.setProgress(progress);
            }

            @Override
            public void onPageStop(GeckoSession activeSession, boolean success) {
                progressBar.setVisibility(View.GONE);
            }
        });
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession activeSession, boolean canGoBack) {
                backButton.setEnabled(canGoBack);
            }

            @Override
            public void onCanGoForward(GeckoSession activeSession, boolean canGoForward) {
                forwardButton.setEnabled(canGoForward);
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(
                    GeckoSession activeSession, LoadRequest request) {
                if (request.target == TARGET_WINDOW_NEW) {
                    activeSession.loadUri(request.uri);
                    return GeckoResult.deny();
                }
                return null;
            }
        });
        session.setPromptDelegate(new GeckoSession.PromptDelegate() {
            @Override
            public GeckoResult<PromptResponse> onAlertPrompt(
                    GeckoSession activeSession, AlertPrompt prompt) {
                GeckoResult<PromptResponse> result = new GeckoResult<>();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(prompt.title == null ? "Bilgi" : prompt.title)
                        .setMessage(prompt.message)
                        .setPositiveButton("Tamam", (dialog, which) -> result.complete(prompt.dismiss()))
                        .setOnCancelListener(dialog -> result.complete(prompt.dismiss()))
                        .show();
                return result;
            }

            @Override
            public GeckoResult<PromptResponse> onFilePrompt(
                    GeckoSession activeSession, FilePrompt prompt) {
                if (pendingFileResult != null) {
                    return GeckoResult.fromValue(prompt.dismiss());
                }
                pendingFilePrompt = prompt;
                pendingFileResult = new GeckoResult<>();
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(resolveMimeType(prompt.mimeTypes));
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.type == FilePrompt.Type.MULTIPLE);
                startActivityForResult(intent, REQUEST_WEB_FILE);
                return pendingFileResult;
            }
        });

        session.open(runtime);
        geckoView.setSession(session);
        loadUrl(HOME_URL);
    }

    private String resolveMimeType(String[] mimeTypes) {
        if (mimeTypes == null || mimeTypes.length == 0) {
            return "*/*";
        }
        if (mimeTypes.length == 1) {
            return mimeTypes[0];
        }
        return "*/*";
    }

    private void loadFromAddressBar() {
        loadUrl(normalizeAddress(addressBar.getText().toString()));
        addressBar.clearFocus();
    }

    private void loadUrl(String url) {
        if (session == null) {
            return;
        }
        session.loadUri(url);
    }

    private String normalizeAddress(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) {
            return HOME_URL;
        }
        if (value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("about:")) {
            return value;
        }
        if (!value.contains(" ") && value.contains(".")) {
            return "https://" + value;
        }
        return "https://duckduckgo.com/?q="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void onVpnButtonPressed() {
        if (vpnConfig == null) {
            chooseVpnProfile();
            return;
        }
        if (vpnState == Tunnel.State.UP) {
            setVpnState(Tunnel.State.DOWN);
            return;
        }
        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION);
        } else {
            setVpnState(Tunnel.State.UP);
        }
    }

    private void chooseVpnProfile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_VPN_PROFILE);
    }

    private void restoreVpnProfile() {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_VPN_CONFIG, null);
        if (stored == null || stored.isBlank()) {
            updateVpnButton();
            return;
        }
        try {
            vpnConfig = parseVpnConfig(stored);
        } catch (Exception error) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_VPN_CONFIG).apply();
            vpnConfig = null;
        }
        updateVpnButton();
    }

    private void importVpnProfile(Uri uri) {
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) {
                throw new IllegalArgumentException("Dosya açılamadı.");
            }
            String raw = readUtf8(stream);
            String browserOnly = restrictToThisBrowser(raw);
            Config parsed = parseVpnConfig(browserOnly);
            vpnConfig = parsed;
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_VPN_CONFIG, browserOnly)
                    .apply();
            updateVpnButton();
            toast("VPN profili hazır. VPN düğmesine basabilirsiniz.");
        } catch (Exception error) {
            toast("VPN profili okunamadı: " + safeMessage(error));
        }
    }

    private Config parseVpnConfig(String raw) throws Exception {
        StringBuilder repaired = new StringBuilder();
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        for (String originalLine : normalized.split("\n")) {
            String line = originalLine;
            int equals = line.indexOf('=');
            if (equals > 0) {
                String field = line.substring(0, equals).trim();
                if (field.equalsIgnoreCase("PrivateKey")
                        || field.equalsIgnoreCase("PublicKey")
                        || field.equalsIgnoreCase("PresharedKey")) {
                    String value = line.substring(equals + 1)
                            .replaceAll("\\s+", "")
                            .replace('-', '+')
                            .replace('_', '/');
                    while (value.length() % 4 != 0) {
                        value += "=";
                    }
                    try {
                        byte[] decoded = java.util.Base64.getDecoder().decode(value);
                        if (decoded.length == 32) {
                            value = java.util.Base64.getEncoder().encodeToString(decoded);
                            line = line.substring(0, equals + 1) + " " + value;
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Config.parse asil ayrintili hatayi gosterecek.
                    }
                }
            }
            repaired.append(line).append('\n');
        }
        return Config.parse(new ByteArrayInputStream(
                repaired.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private String restrictToThisBrowser(String raw) {
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder result = new StringBuilder();
        boolean interfaceFound = false;
        for (String line : normalized.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, "IncludedApplications", 0,
                    "IncludedApplications".length())
                    || trimmed.regionMatches(true, 0, "ExcludedApplications", 0,
                    "ExcludedApplications".length())) {
                continue;
            }
            result.append(line).append('\n');
            if (trimmed.equalsIgnoreCase("[Interface]")) {
                result.append("IncludedApplications = ")
                        .append(getPackageName())
                        .append('\n');
                interfaceFound = true;
            }
        }
        if (!interfaceFound) {
            throw new IllegalArgumentException("WireGuard [Interface] bölümü bulunamadı.");
        }
        return result.toString();
    }

    private String readUtf8(InputStream input) throws Exception {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    private void setVpnState(Tunnel.State targetState) {
        vpnButton.setEnabled(false);
        vpnButton.setText(targetState == Tunnel.State.UP ? "VPN: Açılıyor" : "VPN: Kapanıyor");
        vpnExecutor.execute(() -> {
            try {
                vpnBackend.setState(tunnel, targetState,
                        targetState == Tunnel.State.UP ? vpnConfig : null);
            } catch (Exception error) {
                vpnState = Tunnel.State.DOWN;
                runOnUiThread(() -> {
                    updateVpnButton();
                    toast("VPN bağlantısı kurulamadı: " + safeMessage(error));
                });
            }
        });
    }

    private void updateVpnButton() {
        if (vpnButton == null) {
            return;
        }
        vpnButton.setEnabled(true);
        if (vpnConfig == null) {
            vpnButton.setText("VPN: Profil");
            vpnButton.setBackgroundColor(Color.rgb(110, 78, 35));
        } else if (vpnState == Tunnel.State.UP) {
            vpnButton.setText("VPN: Açık");
            vpnButton.setBackgroundColor(Color.rgb(23, 120, 73));
        } else {
            vpnButton.setText("VPN: Kapalı");
            vpnButton.setBackgroundColor(Color.rgb(130, 55, 55));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                setVpnState(Tunnel.State.UP);
            } else {
                toast("Android VPN izni verilmedi.");
            }
            return;
        }

        if (requestCode == REQUEST_VPN_PROFILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importVpnProfile(data.getData());
            }
            return;
        }

        if (requestCode == REQUEST_WEB_FILE && pendingFileResult != null
                && pendingFilePrompt != null) {
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    Uri[] uris = new Uri[count];
                    for (int index = 0; index < count; index++) {
                        uris[index] = data.getClipData().getItemAt(index).getUri();
                    }
                    pendingFileResult.complete(pendingFilePrompt.confirm(this, uris));
                } else if (data.getData() != null) {
                    pendingFileResult.complete(pendingFilePrompt.confirm(this, data.getData()));
                } else {
                    pendingFileResult.complete(pendingFilePrompt.dismiss());
                }
            } else {
                pendingFileResult.complete(pendingFilePrompt.dismiss());
            }
            pendingFileResult = null;
            pendingFilePrompt = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (backButton != null && backButton.isEnabled()) {
            session.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && vpnState == Tunnel.State.UP) {
            vpnExecutor.execute(() -> {
                try {
                    vpnBackend.setState(tunnel, Tunnel.State.DOWN, null);
                } catch (Exception ignored) {
                    // Uygulama kapanırken bağlantı zaten sonlanıyor olabilir.
                }
            });
        }
        if (isFinishing()) {
            vpnExecutor.shutdown();
        }
    }

    private Button makeButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackgroundColor(color);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private LinearLayout.LayoutParams fixedButtonParams(int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(52));
        params.setMarginStart(dp(6));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private final class BrowserTunnel implements Tunnel {
        @Override
        public String getName() {
            return "VolkanVPN";
        }

        @Override
        public void onStateChange(State newState) {
            vpnState = newState;
            runOnUiThread(() -> {
                updateVpnButton();
                toast(newState == State.UP
                        ? "VPN yalnızca Volkan Tarayıcı için açıldı."
                        : "VPN kapatıldı.");
            });
        }
    }
}
