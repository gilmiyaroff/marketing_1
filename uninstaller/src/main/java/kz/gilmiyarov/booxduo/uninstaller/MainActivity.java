package kz.gilmiyarov.booxduo.uninstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String TARGET = "kz.gilmiyarov.booxduo";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 40, 28, 28);
        root.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(this);
        title.setText("Обновление BOOX Duo");
        title.setTextSize(25);
        title.setTextColor(Color.BLACK);
        root.addView(title);
        TextView description = new TextView(this);
        description.setText("Эта утилита открывает системное удаление только оболочки «Наши книги». ReadEra, AlReaderX и файлы книг не затрагиваются.");
        description.setTextColor(Color.DKGRAY);
        description.setTextSize(16);
        description.setPadding(0, 18, 0, 32);
        root.addView(description);
        TextView button = new TextView(this);
        button.setText("Удалить старую оболочку");
        button.setTextColor(Color.BLACK);
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(0xffeeeeee);
        root.addView(button, new LinearLayout.LayoutParams(-1, 80));
        button.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Удалить старую версию?")
            .setMessage("Android покажет подтверждение. Это нужно только при ошибке несовпадения подписей APK.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить", (d,which) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + TARGET)));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + TARGET)));
                    } catch (Exception other) {
                        Toast.makeText(this, "Удаление недоступно через системное меню", Toast.LENGTH_LONG).show();
                    }
                }
            }).show());
        setContentView(root);
    }
}
