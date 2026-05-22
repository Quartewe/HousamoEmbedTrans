package com.quarty.housamoembedtrans;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * LSPosed 模块设置界面 — 配置 API、字典等参数。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "housamo_trans_prefs";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_ENABLE_TRANS = "enable_translation";
    private static final String KEY_ENABLE_DICT = "enable_dict";

    private EditText etApiUrl;
    private EditText etApiKey;
    private CheckBox cbEnableTrans;
    private CheckBox cbEnableDict;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etApiUrl = findViewById(R.id.et_api_url);
        etApiKey = findViewById(R.id.et_api_key);
        cbEnableTrans = findViewById(R.id.cb_enable_translation);
        cbEnableDict = findViewById(R.id.cb_enable_dict);
        Button btnSave = findViewById(R.id.btn_save);

        loadSettings();

        btnSave.setOnClickListener(v -> {
            saveSettings();
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        etApiUrl.setText(prefs.getString(KEY_API_URL, ""));
        etApiKey.setText(prefs.getString(KEY_API_KEY, ""));
        cbEnableTrans.setChecked(prefs.getBoolean(KEY_ENABLE_TRANS, true));
        cbEnableDict.setChecked(prefs.getBoolean(KEY_ENABLE_DICT, true));
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_API_URL, etApiUrl.getText().toString().trim())
                .putString(KEY_API_KEY, etApiKey.getText().toString().trim())
                .putBoolean(KEY_ENABLE_TRANS, cbEnableTrans.isChecked())
                .putBoolean(KEY_ENABLE_DICT, cbEnableDict.isChecked())
                .apply();
    }
}
