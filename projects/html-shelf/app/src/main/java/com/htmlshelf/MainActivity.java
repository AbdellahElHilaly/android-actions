package com.htmlshelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.htmlshelf.core.SafeZipExtractor;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native, minimal, single-screen manager: tap=open, hold=select, grip=drag. */
public final class MainActivity extends Activity {
    private static final int PICK_FILES = 91;
    private static final int BLACK = 0xFF171717;
    private static final int MUTED = 0xFF888888;
    private static final int LIGHT = 0xFFF6F6F6;

    private PageRepository repository;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<Long> selected = new LinkedHashSet<>();
    private LinearLayout toolbar;
    private LinearLayout rows;
    private ScrollView scrollView;
    private SiteService siteService;
    private boolean bound;
    private Long pendingPage;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            siteService = ((SiteService.LocalBinder) binder).service();
            openPending();
        }
        @Override public void onServiceDisconnected(ComponentName name) { siteService = null; }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        repository = new PageRepository(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 targets draw edge-to-edge by default.
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                root.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }
        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(7), dp(16), dp(7));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(60)));
        addDivider(root);
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(rows, new ScrollView.LayoutParams(-1, -2));
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        render();
    }

    private int dp(float d) { return (int)(d * getResources().getDisplayMetrics().density + .5f); }

    private TextView text(String value, int size, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private TextView action(String value, String description, int width) {
        TextView result = text(value, 24, BLACK);
        result.setGravity(Gravity.CENTER);
        result.setContentDescription(description);
        result.setLayoutParams(new LinearLayout.LayoutParams(dp(width), dp(46)));
        result.setBackground(makeRipple());
        return result;
    }

    private android.graphics.drawable.Drawable makeRipple() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x15000000), normal, null);
    }

    private void addDivider(LinearLayout target) {
        View line = new View(this);
        line.setBackgroundColor(0xFFF0F0F0);
        target.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void render() {
        toolbar.removeAllViews();
        rows.removeAllViews();
        if (selected.isEmpty()) {
            TextView title = text("صفحاتي", 21, BLACK);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            toolbar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
            TextView add = action("+", "إضافة HTML أو ZIP", 50);
            add.setOnClickListener(v -> chooseDocuments());
            toolbar.addView(add);
        } else {
            TextView cancel = action("×", "إلغاء التحديد", 48);
            cancel.setOnClickListener(v -> { selected.clear(); render(); });
            toolbar.addView(cancel);
            TextView count = text(selected.size() + " محددة", 17, BLACK);
            toolbar.addView(count, new LinearLayout.LayoutParams(0, -1, 1));
            TextView delete = text("حذف", 15, BLACK);
            delete.setGravity(Gravity.CENTER);
            delete.setBackground(makeRipple());
            delete.setOnClickListener(v -> confirmDelete(new ArrayList<>(selected)));
            toolbar.addView(delete, new LinearLayout.LayoutParams(dp(65), -1));
        }
        List<PageRepository.Page> pages = repository.list();
        if (pages.isEmpty()) {
            TextView empty = text("أضف صفحة HTML أو ملف ZIP", 14, MUTED);
            empty.setGravity(Gravity.CENTER);
            rows.addView(empty, new LinearLayout.LayoutParams(-1,
                    Math.max(dp(300), getResources().getDisplayMetrics().heightPixels - dp(180))));
            return;
        }
        for (PageRepository.Page page : pages) addPageRow(page);
    }

    private void addPageRow(PageRepository.Page page) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(17), 0, dp(17), 0);
        row.setBackgroundColor(selected.contains(page.id) ? LIGHT : Color.WHITE);
        rows.addView(row, new LinearLayout.LayoutParams(-1, dp(63)));

        TextView name = text(page.title, 16, BLACK);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(name, new LinearLayout.LayoutParams(0, -1, 1));

        if (selected.isEmpty()) {
            TextView drag = action("≡", "اسحب لتغيير الترتيب", 40);
            drag.setTextSize(21);
            drag.setTextColor(MUTED);
            row.addView(drag);
            addDragSupport(row, drag, page);
            TextView menu = action("⋮", "خيارات " + page.title, 35);
            menu.setTextColor(0xFF666666);
            menu.setOnClickListener(v -> showMenu(v, page));
            row.addView(menu);
        } else {
            TextView check = action(selected.contains(page.id) ? "●" : "○", "تحديد", 35);
            check.setTextSize(20);
            check.setTextColor(selected.contains(page.id) ? BLACK : MUTED);
            row.addView(check);
            check.setOnClickListener(v -> toggle(page.id));
        }
        row.setOnClickListener(v -> {
            if (selected.isEmpty()) requestOpen(page.id);
            else toggle(page.id);
        });
        row.setOnLongClickListener(v -> { toggle(page.id); return true; });
        addDivider(rows);
    }

    private void toggle(long id) {
        if (!selected.add(id)) selected.remove(id);
        render();
    }

    private void addDragSupport(LinearLayout row, View handle, PageRepository.Page page) {
        float[] touch = new float[2];
        boolean[] dragging = {false};
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touch[0] = e.getRawX(); touch[1] = e.getRawY();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging[0] && Math.hypot(e.getRawX() - touch[0],
                            e.getRawY() - touch[1]) > dp(6)) {
                        dragging[0] = true;
                        ClipData data = ClipData.newPlainText("page-id", Long.toString(page.id));
                        row.startDragAndDrop(data, new View.DragShadowBuilder(row), null, 0);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: return true;
                default: return false;
            }
        });
        row.setOnDragListener((target, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return event.getClipDescription() != null &&
                            event.getClipDescription().hasMimeType("text/plain");
                case DragEvent.ACTION_DRAG_ENTERED:
                    row.setBackgroundColor(LIGHT); return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    row.setBackgroundColor(Color.WHITE); return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    int top = row.getTop() - scrollView.getScrollY();
                    if (top < dp(70)) scrollView.smoothScrollBy(0, -dp(24));
                    if (top > scrollView.getHeight() - dp(100)) scrollView.smoothScrollBy(0, dp(24));
                    return true;
                case DragEvent.ACTION_DROP:
                    try {
                        long from = Long.parseLong(event.getClipData().getItemAt(0).getText().toString());
                        boolean after = event.getY() > row.getHeight() / 2f;
                        repository.move(from, page.id, after);
                        render();
                    } catch (RuntimeException ignored) { render(); }
                    return true;
                default: return true;
            }
        });
    }

    private void showMenu(View anchor, PageRepository.Page page) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "تغيير الاسم");
        popup.getMenu().add(0, 2, 1, "حذف");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) rename(page);
            else if (item.getItemId() == 2) confirmDelete(java.util.Collections.singletonList(page.id));
            return true;
        });
        popup.show();
    }

    private void rename(PageRepository.Page page) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(page.title);
        edit.selectAll();
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(24), 0, dp(24), 0);
        container.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("تغيير الاسم").setView(container)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    String title = edit.getText().toString().trim();
                    if (!title.isEmpty()) { repository.rename(page.id, title); render(); }
                }).show();
    }

    private void confirmDelete(List<Long> ids) {
        new AlertDialog.Builder(this)
                .setMessage(ids.size() == 1 ? "حذف الصفحة وملفاتها؟" :
                        "حذف " + ids.size() + " صفحات وملفاتها؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (dialog, which) -> {
                    if (siteService != null) for (long id : ids) siteService.closeSite(id);
                    io.execute(() -> {
                        File sites = new File(getFilesDir(), "sites");
                        for (long id : ids) {
                            PageRepository.Page page = repository.find(id);
                            if (page == null) continue;
                            repository.delete(id);
                            try {
                                File site = new File(sites, page.folder).getCanonicalFile();
                                if (site.getParentFile().equals(sites.getCanonicalFile())) {
                                    SafeZipExtractor.deleteTree(site);
                                }
                            } catch (Exception ignored) { }
                        }
                        runOnUiThread(() -> { selected.clear(); render(); });
                    });
                }).show();
    }

    private void chooseDocuments() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        // SAF can start here without requesting Android's restricted all-files permission.
        Uri downloads = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloads);
        try { startActivityForResult(intent, PICK_FILES); }
        catch (Exception e) { toast("تعذر فتح مدير الملفات"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return;
        List<Uri> chosen = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                chosen.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) chosen.add(data.getData());
        if (chosen.isEmpty()) return;
        toast("جارٍ الاستيراد...");
        io.execute(() -> {
            ImportManager manager = new ImportManager(this, repository);
            int success = 0;
            List<String> errors = new ArrayList<>();
            for (Uri uri : chosen) {
                try { manager.importDocument(uri); success++; }
                catch (Exception e) { errors.add(e.getMessage() == null ? "ملف غير مدعوم" : e.getMessage()); }
            }
            final int imported = success;
            runOnUiThread(() -> {
                selected.clear(); render();
                if (errors.isEmpty()) toast("تمت إضافة " + imported);
                else toast("أضيف " + imported + " · فشل " + errors.size() + ": " + errors.get(0));
            });
        });
    }

    private void requestOpen(long id) {
        pendingPage = id;
        if (siteService != null) { openPending(); return; }
        Intent intent = new Intent(this, SiteService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            if (!bound) bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            if (!bound) toast("تعذر تشغيل الموقع المحلي");
        } catch (Exception e) { toast("تعذر تشغيل الموقع: " + e.getMessage()); }
    }

    private void openPending() {
        if (siteService == null || pendingPage == null) return;
        long id = pendingPage;
        pendingPage = null;
        try {
            String url = siteService.open(id);
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(browser);
        } catch (Exception e) { toast("تعذر فتح الصفحة: " + e.getMessage()); }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        if (bound) unbindService(serviceConnection);
        io.shutdown();
        repository.close();
        super.onDestroy();
    }
}
