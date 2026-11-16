package local.shake;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 设置页和悬浮窗共用同样的输入范围和错误提示。 */
final class NumberFields {
    final EditText rate, duration;
    NumberFields(LinearLayout parent, int hz, int seconds) {
        this(parent, hz, seconds, false);
    }
    NumberFields(LinearLayout parent, int hz, int seconds, boolean compact) {
        rate = add(parent, compact ? "次数/秒" : "每秒来回次数（1～" + Motion.MAX_RATE + "）", hz, compact);
        duration = add(parent, compact ? "时长（秒）" : "运行时长（1～300 秒）", seconds, compact);
    }
    private EditText add(LinearLayout parent, String title, int value, boolean compact) {
        if (compact) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
            parent = row;
        }
        Context context = parent.getContext();
        TextView label = new TextView(context);
        label.setText(title);
        if (compact) parent.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        else parent.addView(label);
        EditText input = new EditText(context);
        input.setId(android.view.View.generateViewId());
        label.setLabelFor(input.getId());
        if (compact) {
            label.setTextColor(android.graphics.Color.WHITE);
            input.setTextColor(android.graphics.Color.WHITE);
            input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffc3d5e6));
        }
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSelectAllOnFocus(true);
        input.setText(String.valueOf(value));
        if (compact) parent.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        else parent.addView(input);
        return input;
    }
    int readRate() { return read(rate, Motion.MAX_RATE); }
    int readDuration() { return read(duration, 300); }
    private int read(EditText input, int max) {
        int number;
        try { number = Integer.parseInt(input.getText().toString().trim()); }
        catch (NumberFormatException ignored) { number = 0; }
        if (number >= 1 && number <= max) return number;
        input.setError("请输入 1～" + max + " 之间的整数");
        input.requestFocus();
        return 0;
    }
}
