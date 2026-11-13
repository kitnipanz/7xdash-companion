package com.kooo.evcam.browser;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * The browser full screen. The toolbar's window button hands the page to the floating window
 * ({@link BrowserFloat}) and closes this screen.
 */
public class BrowserActivity extends AppCompatActivity implements BrowserView.Host {

    public static final String EXTRA_URL = "url";

    private BrowserView browser;

    public static void open(Context context) {
        if (BrowserFloat.isShown()) {
            // One browser at a time: the floating one becomes full screen
            BrowserFloat.hide();
        }
        context.startActivity(new Intent(context, BrowserActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        browser = new BrowserView(this, this, getIntent().getStringExtra(EXTRA_URL));
        setContentView(browser);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!browser.back()) {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = intent.getStringExtra(EXTRA_URL);
        if (url != null) {
            browser.load(url);
        }
    }

    @Override
    protected void onPause() {
        browser.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        browser.onResume();
    }

    @Override
    protected void onDestroy() {
        browser.destroy();
        super.onDestroy();
    }

    // ================================================================= BrowserView.Host

    @Override
    public void close() {
        finish();
    }

    @Override
    public void switchMode(String url) {
        if (BrowserFloat.show(this, url)) {
            finish();
        }
    }

    @Override
    public void wantKeys(boolean on) {
        // A full screen always has the keys
    }

    @Override
    public View.OnTouchListener grip(Runnable onTap) {
        return null;
    }

    @Override
    public boolean floating() {
        return false;
    }
}
