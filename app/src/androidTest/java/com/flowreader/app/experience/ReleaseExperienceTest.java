package com.flowreader.app.experience;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.util.*;

/** Framework-only black-box probe; independent of the app's optimized Kotlin/Compose libraries. */
public class ReleaseExperienceTest extends Instrumentation {
    private String phase = "read";
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (args != null) phase = args.getString("phase", "read");
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            require(getTargetContext().getPackageName().equals("com.flowreader.app.speechcheck"), "Isolated app ID required");
            android.accessibilityservice.AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            getUiAutomation().setServiceInfo(info);
            if (phase.equals("epubnavigation")) epubNavigation(); else if (phase.equals("cold")) coldOpen(); else if (phase.equals("seriespdf")) seriesAndPdf(); else if (phase.equals("pdf")) pdfOnly(); else importReadAndNavigate();
            result.putString("stream", "PROBE_OK: " + phase);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            try { capture("failure"); } catch (Exception diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
            StringWriter stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            result.putString("stream", stack.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private List<AccessibilityNodeInfo> nodes(String text) {
        if (android.os.Build.VERSION.SDK_INT >= 33) getUiAutomation().clearCache();
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        List<AccessibilityNodeInfo> visible = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        if (root != null) pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            CharSequence value = node.getText(), description = node.getContentDescription();
            String needle = text.replaceAll("\\s+", "");
            if (node.isVisibleToUser() && (text.isEmpty() || value != null && value.toString().replaceAll("\\s+", "").contains(needle) ||
                    description != null && description.toString().replaceAll("\\s+", "").contains(needle))) visible.add(node);
            for (int i=0; i<node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
        }
        return visible;
    }
    private List<AccessibilityNodeInfo> waitText(String text) {
        long end = SystemClock.uptimeMillis() + 60000;
        while (SystemClock.uptimeMillis() < end) {
            List<AccessibilityNodeInfo> found = nodes(text);
            if (!found.isEmpty()) return found;
            SystemClock.sleep(100);
        }
        AccessibilityNodeInfo diagnosticRoot=getUiAutomation().getRootInActiveWindow();
        ArrayDeque<AccessibilityNodeInfo> diagnosticPending=new ArrayDeque<>();
        if (diagnosticRoot!=null) diagnosticPending.add(diagnosticRoot);
        StringBuilder diagnostic=new StringBuilder();
        while (!diagnosticPending.isEmpty() && diagnostic.length()<16000) {
            AccessibilityNodeInfo node=diagnosticPending.removeFirst(); Rect bounds=new Rect(); node.getBoundsInScreen(bounds);
            if (node.getText()!=null || node.getContentDescription()!=null) diagnostic.append(node.isVisibleToUser()).append(" ").append(bounds).append(" ").append(node.getText()).append(" / ").append(node.getContentDescription()).append('\n');
            for (int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo child=node.getChild(i); if(child!=null) diagnosticPending.addLast(child); }
        }
        Bundle dump=new Bundle(); dump.putString("missingTextNodes",diagnostic.toString()); sendStatus(1,dump);
        throw new AssertionError("Visible text did not appear: " + text);
    }
    private void click(String text, boolean bottom) {
        List<AccessibilityNodeInfo> found = waitText(text);
        AccessibilityNodeInfo chosen = found.get(0);
        if (bottom) {
            int maxBottom = -1;
            for (AccessibilityNodeInfo node : found) {
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                if (bounds.bottom > maxBottom) { chosen = node; maxBottom = bounds.bottom; }
            }
        } else for (AccessibilityNodeInfo node : found) {
            if (node.getText() != null && node.getText().toString().equals(text)) { chosen = node; break; }
        }
        Rect hitBounds = new Rect(); chosen.getBoundsInScreen(hitBounds);
        while (!chosen.isClickable()) {
            AccessibilityNodeInfo parent = chosen.getParent();
            if (parent == null) break;
            chosen = parent;
        }
        if (!chosen.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            require(!hitBounds.isEmpty(), "Cannot locate: " + text);
            try { shell("input tap " + hitBounds.centerX() + " " + hitBounds.centerY()); }
            catch (Exception error) { throw new AssertionError("Cannot tap: " + text, error); }
        }
        waitForIdleSync(); SystemClock.sleep(300);
    }
    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor pfd = getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(pfd.getFileDescriptor())) {
            byte[] bytes = new byte[1024]; while (input.read(bytes) != -1) { }
        }
    }
    private void capture(String name) throws Exception {
        waitForIdleSync(); shell("screencap -p /data/local/tmp/yijuan-" + (phase.equals("epubnavigation") ? "0312-" : phase.equals("seriespdf") || phase.equals("pdf") ? "0311-" : "0310-") + name + ".png");
    }
    private SQLiteDatabase database(int flags) {
        return SQLiteDatabase.openDatabase(getTargetContext().getDatabasePath("flowreader_db").getAbsolutePath(), null, flags);
    }
    private long queryLong(String sql) {
        try (SQLiteDatabase db = database(SQLiteDatabase.OPEN_READONLY); Cursor rows = db.rawQuery(sql, null)) {
            rows.moveToFirst(); return rows.getLong(0);
        }
    }
    private String locator() {
        try (SQLiteDatabase db = database(SQLiteDatabase.OPEN_READONLY);
             Cursor rows = db.rawQuery("SELECT locatorJson FROM reader_states LIMIT 1", null)) {
            return rows.moveToFirst() ? rows.getString(0) : null;
        }
    }
    interface Condition { boolean get(); }
    private void waitCondition(String message, Condition condition) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) {
            if (condition.get()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError(message);
    }
    private void launch(Intent intent) {
        startActivitySync(intent.setClassName(getTargetContext().getPackageName(), "com.flowreader.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private String label(String name, Object... args) {
        int id = getTargetContext().getResources().getIdentifier(name, "string", getTargetContext().getPackageName());
        require(id != 0, "Missing retained resource: " + name);
        return getTargetContext().getString(id, args);
    }
    private void importReadAndNavigate() throws Exception {
        String title = "一卷体验回归";
        File fixture = new File(getTargetContext().getCacheDir(), "share_cards/" + title + ".txt");
        fixture.getParentFile().mkdirs();
        StringBuilder text = new StringBuilder("第一章 清晨\n\n");
        for (int i=0; i<200; i++) text.append("清晨走在山间，远处的薄雾慢慢散去。");
        text.append("\n\n第二章 山风\n\n");
        for (int i=0; i<150; i++) text.append("风从书页之间吹过，故事还在继续。");
        try (FileOutputStream output = new FileOutputStream(fixture)) { output.write(text.toString().getBytes("UTF-8")); }
        Uri uri = new Uri.Builder().scheme("content").authority(getTargetContext().getPackageName() + ".fileprovider")
            .appendPath("share_cards").appendPath(fixture.getName()).build();
        launch(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        waitText(title); capture("shelf"); click(title, false);
        click(label("book_detail_start_reading"), false);
        waitText(label("reader_menu_toc")); SystemClock.sleep(1000); capture("reader");
        SystemClock.sleep(6500);
        shell("input keyevent 4"); SystemClock.sleep(500);
        shell("input keyevent 4"); SystemClock.sleep(500);
        String statsSql = "SELECT COALESCE(SUM(readTimeSeconds),0) FROM reading_stats WHERE statsVersion >= 3";
        waitCondition("Stationary visible reading time was not persisted", () -> queryLong(statsSql) >= 5);
        waitCondition("Reader locator was not persisted", () -> locator() != null && !locator().isEmpty());
        long stopped = queryLong(statsSql);
        SystemClock.sleep(2000);
        require(stopped == queryLong(statsSql), "Clock kept running after leaving the reader");
        for (String name : Arrays.asList("stats_title", "ui_nav_notes", "settings_title", "nav_shelf")) {
            click(label(name), true);
            if (name.equals("stats_title")) { SystemClock.sleep(1000); capture("stats"); }
            if (name.equals("settings_title")) capture("settings");
        }
        Class<?> callback = Class.forName("com.flowreader.app.util.SpeechPcmCallback");
        require(callback.getMethod("invoke", float[].class).getReturnType() == Integer.class, "Typed JNI callback was changed");
        require(getTargetContext().getSharedPreferences("0310-smoke", 0).edit().putString("locator", locator()).commit(), "Cannot save probe locator");
        Bundle measured = new Bundle(); measured.putLong("recordedSeconds", stopped); sendStatus(1, measured);
        try (SQLiteDatabase db = database(SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("UPDATE reading_stats SET statsVersion = 1, readTimeSeconds = 309600");
            db.execSQL("INSERT INTO reading_stats (bookId,date,readPages,readChapters,readTimeSeconds,lastReadTime," +
                "visualReadMillis,ttsReadMillis,qualifiedTextUnits,settledTurns,statsVersion) " +
                "SELECT bookId,date('now','-1 day'),0,0,17,100,0,0,0,0,3 FROM reading_stats LIMIT 1");
        }
        fixture.delete();
    }
    private String queryString(String sql) {
        try (SQLiteDatabase db = database(SQLiteDatabase.OPEN_READONLY); Cursor rows = db.rawQuery(sql, null)) {
            return rows.moveToFirst() ? rows.getString(0) : null;
        }
    }
    private void setField(String value) {
        for (AccessibilityNodeInfo node : nodes("")) {
            if (node.isEditable() || "android.widget.EditText".contentEquals(node.getClassName())) {
                Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                require(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "Cannot edit series name");
                SystemClock.sleep(300); return;
            }
        }
        throw new AssertionError("Series name field missing");
    }
    private void importFile(File file, String mime, String title) {
        Uri uri = new Uri.Builder().scheme("content").authority(getTargetContext().getPackageName() + ".fileprovider")
            .appendPath("share_cards").appendPath(file.getName()).build();
        launch(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        waitText(title);
    }
    private void backToShelf() throws Exception {
        for (int i=0; i<5 && nodes(label("nav_shelf")).isEmpty(); i++) {
            click(label("action_back"), false);
            waitCondition("Back navigation did not settle", () -> !nodes(label("nav_shelf")).isEmpty() ||
                !nodes(label("book_detail_start_reading")).isEmpty());
        }
        click(label("nav_shelf"), true);
    }
    private Rect waitPdfPage(int page) throws Exception {
        Rect viewport = new Rect();
        waitCondition("PDF pixels did not render for page " + page, () -> {
            android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
            if (bitmap == null) return false;
            try {
                viewport.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
                int matches = 0;
                for (int y=bitmap.getHeight()*4/10; y<bitmap.getHeight()*6/10; y+=20) {
                    for (int x=bitmap.getWidth()*3/10; x<bitmap.getWidth()*7/10; x+=20) {
                        int color=bitmap.getPixel(x,y);
                        int r=android.graphics.Color.red(color), g=android.graphics.Color.green(color), b=android.graphics.Color.blue(color);
                        if (g<40 && (page==1 ? b>220 && r<40 : r>220 && b<40)) matches++;
                    }
                }
                return matches>50;
            } finally { bitmap.recycle(); }
        });
        waitForIdleSync();
        SystemClock.sleep(300);
        // The bitmap can publish before the progress label recomposes. Checking the new label
        // here would mistake that frame for hidden controls and hide an already open bar.
        long end = SystemClock.uptimeMillis() + 15000, lastToggle = 0;
        while (SystemClock.uptimeMillis() < end) {
            if (!nodes(label("pdf_progress_pages", page, 2)).isEmpty()) return viewport;
            if (nodes(label("reader_menu_toc")).isEmpty() && SystemClock.uptimeMillis() - lastToggle > 1000) {
                toggleReaderControls(); lastToggle = SystemClock.uptimeMillis();
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("PDF progress label missing for page " + page);
    }
    private void hidePdfControls(Rect viewport) throws Exception {
        Bundle diagnostic=new Bundle(); diagnostic.putString("hidePdfControlsBounds",viewport.toString()); sendStatus(1,diagnostic);
        if (!nodes(label("reader_menu_toc")).isEmpty()) {
            toggleReaderControls();
            waitCondition("Reader controls did not dismiss", () -> nodes(label("reader_menu_toc")).isEmpty());
        }
        SystemClock.sleep(400);
    }
    private void toggleReaderControls() {
        String expected=label("reader_toggle_controls");
        for (AccessibilityNodeInfo node : nodes("")) {
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                if (action.getLabel()!=null && expected.contentEquals(action.getLabel())) {
                    require(node.performAction(action.getId()), "Reader toggle accessibility action failed");
                    Bundle stage=new Bundle(); stage.putString("controlsToggle","accessibility"); sendStatus(1,stage);
                    return;
                }
            }
        }
        android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
        require(screenshot!=null,"Cannot locate reader touch area");
        int x=screenshot.getWidth()/2,y=screenshot.getHeight()/2; screenshot.recycle();
        tap(x,y);
    }
    private void pointer(long down, int action, float x, float y) {
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { require(getUiAutomation().injectInputEvent(event,true), "Touch injection failed"); }
        finally { event.recycle(); }
    }
    private void tap(int x,int y) {
        long down=SystemClock.uptimeMillis();
        pointer(down,android.view.MotionEvent.ACTION_DOWN,x,y); SystemClock.sleep(40);
        pointer(down,android.view.MotionEvent.ACTION_UP,x,y);
    }
    private void swipe(int from,int to,int y) {
        long down=SystemClock.uptimeMillis(); pointer(down,android.view.MotionEvent.ACTION_DOWN,from,y);
        for (int step=1;step<=15;step++) {
            SystemClock.sleep(30);
            pointer(down,android.view.MotionEvent.ACTION_MOVE,from+(to-from)*step/15f,y);
        }
        pointer(down,android.view.MotionEvent.ACTION_UP,to,y);
    }
    private void seriesAndPdf() throws Exception {
        launch(new Intent()); waitText("一卷体验回归");
        require(queryLong("PRAGMA user_version") == 12, "Real v11 database did not migrate to v12");
        require(queryLong("SELECT SUM(readTimeSeconds) FROM reading_stats") == 17, "Upgrade erased new statistics or retained obsolete time");
        String oldLocator = getTargetContext().getSharedPreferences("0310-smoke", 0).getString("locator", null);
        require(oldLocator != null && oldLocator.equals(locator()), "Upgrade changed the old reader locator");
        File directory = new File(getTargetContext().getCacheDir(), "share_cards"); directory.mkdirs();
        for (String title : Arrays.asList("Series Volume One", "Series Volume Two")) {
            File text = new File(directory, title + ".txt");
            try (FileOutputStream output = new FileOutputStream(text)) { output.write(("第一章 开篇\n\n这是系列阅读测试正文。" + title).getBytes("UTF-8")); }
            importFile(text, "text/plain", title); text.delete();
        }
        click(label("action_more"), false); click(label("series_title"), false);
        waitText(label("series_name")); setField("Mountain Trilogy"); click(label("series_create"), false);
        click("Series Volume One", false); click("Series Volume Two", false); click(label("action_save"), false);
        waitCondition("Series assignment was not saved", () -> queryLong("SELECT COUNT(*) FROM books WHERE seriesName='Mountain Trilogy'") == 2);
        String initialLast = queryString("SELECT title FROM books WHERE seriesName='Mountain Trilogy' ORDER BY seriesOrder DESC LIMIT 1");
        click(label("series_move_up", initialLast), false);
        waitCondition("Manual volume order did not change", () -> initialLast.equals(queryString("SELECT title FROM books WHERE seriesName='Mountain Trilogy' ORDER BY seriesOrder LIMIT 1")));
        SystemClock.sleep(700); capture("series-order");
        shell("input keyevent 4"); SystemClock.sleep(700); capture("series-shelf");
        click("Mountain Trilogy", false); click(initialLast, false);
        waitText(label("reader_menu_toc")); SystemClock.sleep(1200);
        long firstId = queryLong("SELECT id FROM books WHERE seriesName='Mountain Trilogy' ORDER BY seriesOrder LIMIT 1");
        long nextId = queryLong("SELECT id FROM books WHERE seriesName='Mountain Trilogy' ORDER BY seriesOrder DESC LIMIT 1");
        click(label("series_next_volume"), false); SystemClock.sleep(400); capture("next-volume");
        AccessibilityNodeInfo action = null; int top = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo candidate : waitText(label("series_next_open"))) {
            Rect bounds = new Rect(); candidate.getBoundsInScreen(bounds);
            if (candidate.getText() != null && candidate.getText().toString().equals(label("series_next_open")) && bounds.top < top) { action = candidate; top = bounds.top; }
        }
        require(action != null, "Next-volume snackbar action missing");
        Rect actionBounds = new Rect(); action.getBoundsInScreen(actionBounds);
        while (!action.isClickable() && action.getParent() != null) action = action.getParent();
        boolean activated = action.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle clickResult = new Bundle(); clickResult.putString("nextActionBounds", actionBounds.toString()); clickResult.putBoolean("nextActionActivated", activated); sendStatus(1, clickResult);
        if (!activated) shell("input tap " + actionBounds.centerX() + " " + actionBounds.centerY());
        waitText(queryString("SELECT title FROM books WHERE id=" + nextId));
        capture("next-volume-open");
        require(firstId != nextId, "Invalid series fixture");
        backToShelf();
        waitCondition("Next-volume locator was not saved on exit", () -> queryLong("SELECT COUNT(*) FROM reader_states WHERE bookId=" + nextId) == 1);
        File pdf = new File(directory, "PDF Smoke.pdf");
        android.graphics.pdf.PdfDocument document = new android.graphics.pdf.PdfDocument();
        for (int index=1; index<=2; index++) {
            android.graphics.pdf.PdfDocument.Page page = document.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 800, index).create());
            page.getCanvas().drawColor(android.graphics.Color.WHITE);
            android.graphics.Paint paint = new android.graphics.Paint(); paint.setColor(index == 1 ? android.graphics.Color.BLUE : android.graphics.Color.RED); paint.setTextSize(36);
            page.getCanvas().drawText("PDF page " + index, 60, 140, paint);
            page.getCanvas().drawRect(60, 220, 540, 550, paint); document.finishPage(page);
        }
        try (FileOutputStream output = new FileOutputStream(pdf)) { document.writeTo(output); } finally { document.close(); }
        long expectedSize = pdf.length();
        importFile(pdf, "application/pdf", "PDF Smoke");
        click("PDF Smoke", false); waitText(label("book_detail_file_size_value", "").trim());
        require(queryLong("SELECT fileSize FROM books WHERE title='PDF Smoke'") == expectedSize, "PDF file size is incorrect");
        SystemClock.sleep(700); capture("pdf-details");
        exercisePdf();
        require(queryLong("SELECT COUNT(*) FROM books WHERE seriesName='Mountain Trilogy'") == 2, "PDF import changed series membership");
        pdf.delete();
        Bundle measured = new Bundle(); measured.putLong("pdfBytes", expectedSize); measured.putLong("nextVolumeId", nextId); sendStatus(1, measured);
    }
    private void pdfOnly() throws Exception {
        launch(new Intent()); click(label("nav_shelf"), true); waitText("PDF Smoke"); click("PDF Smoke", false);
        exercisePdf();
    }
    private void exercisePdf() throws Exception {
        enterPdfReader();
        if (queryLong("SELECT currentChapter FROM books WHERE title='PDF Smoke'") > 0) click(label("pdf_prev_page"), false);
        Rect pdfBounds = waitPdfPage(1); capture("pdf-page1");
        hidePdfControls(pdfBounds);
        int shortSwipe = Math.round(45 * getTargetContext().getResources().getDisplayMetrics().density);
        for (int i=0; i<2; i++) {
            swipe(pdfBounds.centerX(),pdfBounds.centerX()-shortSwipe,pdfBounds.centerY());
            SystemClock.sleep(300);
        }
        waitPdfPage(1);
        for (int turn=0; turn<3; turn++) {
            Bundle stage=new Bundle(); stage.putInt("pdfRoundTrip",turn+1); sendStatus(1,stage);
            hidePdfControls(pdfBounds);
            swipe(pdfBounds.width()*4/5,pdfBounds.width()/5,pdfBounds.centerY());
            waitPdfPage(2);
            click(label("pdf_prev_page"), false); waitPdfPage(1);
        }
        click(label("pdf_next_page"), false); waitPdfPage(2); SystemClock.sleep(500); capture("pdf-page2");
        waitCondition("PDF progress was not saved", () -> queryLong("SELECT currentChapter FROM books WHERE title='PDF Smoke'") == 1);
        waitCondition("PDF last-page progress did not reach the end", () -> queryLong("SELECT readingProgress >= 0.99 FROM books WHERE title='PDF Smoke'") == 1);
        click(label("action_back"), false);
        waitCondition("PDF exit did not settle", () -> !nodes(label("book_detail_start_reading")).isEmpty() || !nodes(label("nav_shelf")).isEmpty());
        if (nodes(label("book_detail_start_reading")).isEmpty()) {
            backToShelf(); click("PDF Smoke", false);
        }
        enterPdfReader();
        waitPdfPage(2); capture("pdf-resume");
    }
    private void enterPdfReader() {
        waitCondition("PDF entry did not settle", () -> !nodes(label("book_detail_start_reading")).isEmpty() || !nodes(label("reader_menu_toc")).isEmpty());
        if (!nodes(label("book_detail_start_reading")).isEmpty()) click(label("book_detail_start_reading"), false);
    }
    private void coldOpen() throws Exception {
        String expected = getTargetContext().getSharedPreferences("0310-smoke", 0).getString("locator", null);
        require(expected != null && !expected.isEmpty(), "Read probe fixture missing");
        launch(new Intent()); waitText("一卷体验回归");
        require(queryLong("SELECT COUNT(*) FROM reading_stats") == 1, "Restart did not preserve exactly the new-version row");
        require(queryLong("SELECT SUM(readTimeSeconds) FROM reading_stats") == 17, "Old time survived or new time was erased");
        require(expected.equals(locator()), "Reader locator changed during stats restart");
        capture("cold-open");
    }

    private void epubEntry(java.util.zip.ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new java.util.zip.ZipEntry(path)); zip.write(content.getBytes("UTF-8")); zip.closeEntry();
    }
    private File epubFixture(boolean oneDocument) throws Exception {
        File file = new File(getTargetContext().getCacheDir(), "share_cards/" + (oneDocument ? "EPUB Anchors Regression" : "EPUB Spine Regression") + ".epub");
        file.getParentFile().mkdirs();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new FileOutputStream(file))) {
            byte[] mime="application/epub+zip".getBytes("UTF-8"); java.util.zip.CRC32 crc=new java.util.zip.CRC32(); crc.update(mime);
            java.util.zip.ZipEntry entry=new java.util.zip.ZipEntry("mimetype"); entry.setMethod(java.util.zip.ZipEntry.STORED);
            entry.setSize(mime.length); entry.setCrc(crc.getValue()); zip.putNextEntry(entry); zip.write(mime); zip.closeEntry();
            epubEntry(zip,"META-INF/container.xml","<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"EPUB/book.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            StringBuilder manifest=new StringBuilder("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>");
            StringBuilder spine=new StringBuilder(), nav=new StringBuilder(), combined=new StringBuilder();
            String html="<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Fixture</title></head><body>";
            for (int i=0;i<12;i++) {
                String number=String.format(java.util.Locale.ROOT,"%02d",i);
                String body="<h1 id=\"c"+number+"\">Regression "+number+"</h1><p>EPUB_TOKEN_"+number+". This is the complete body of this chapter.</p>";
                if (!oneDocument) {
                    manifest.append("<item id=\"p").append(i).append("\" href=\"p").append(i).append(".xhtml\" media-type=\"application/xhtml+xml\"/>");
                    spine.append("<itemref idref=\"p").append(i).append("\"/>");
                    epubEntry(zip,"EPUB/p"+i+".xhtml",html+body+"</body></html>");
                }
                combined.append(body);
                nav.append("<li><a href=\"").append(oneDocument?"all.xhtml":"p"+i+".xhtml").append("#c").append(number).append("\">Regression ").append(number).append("</a></li>");
            }
            if (oneDocument) {
                manifest.append("<item id=\"all\" href=\"all.xhtml\" media-type=\"application/xhtml+xml\"/>"); spine.append("<itemref idref=\"all\"/>");
                epubEntry(zip,"EPUB/all.xhtml",html+combined+"</body></html>");
            }
            epubEntry(zip,"EPUB/nav.xhtml","<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>Contents</title></head><body><nav epub:type=\"toc\"><ol>"+nav+"</ol></nav></body></html>");
            epubEntry(zip,"EPUB/book.opf","<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">regression-"+oneDocument+"</dc:identifier><dc:title>"+file.getName().replace(".epub","")+"</dc:title><dc:language>en</dc:language></metadata><manifest>"+manifest+"</manifest><spine>"+spine+"</spine></package>");
        }
        return file;
    }
    private void epubPage(long bookId, int index) {
        // The full-screen controls overlay hides body nodes from accessibility even when the
        // text remains visible through it. Assert the actual page after dismissing the overlay.
        if (!nodes(label("reader_menu_toc")).isEmpty()) {
            toggleReaderControls();
            waitCondition("EPUB controls did not dismiss", () -> nodes(label("reader_menu_toc")).isEmpty());
        }
        waitText(String.format(java.util.Locale.ROOT,"EPUB_TOKEN_%02d",index));
        waitCondition("Wrong EPUB chapter was saved: "+index, () -> queryLong("SELECT currentChapter FROM books WHERE id="+bookId)==index);
        Bundle stage=new Bundle(); stage.putString("epubChapterVerified",bookId+":"+index); sendStatus(1,stage);
    }
    private void readerControls() {
        long end=SystemClock.uptimeMillis()+15000, lastToggle=0;
        while (SystemClock.uptimeMillis()<end && nodes(label("reader_menu_brightness")).isEmpty()) {
            if (SystemClock.uptimeMillis()-lastToggle>1000) {
                toggleReaderControls(); lastToggle=SystemClock.uptimeMillis();
            }
            SystemClock.sleep(100);
        }
        waitText(label("reader_menu_brightness"));
    }
    private void chooseEpubChapter(int destination, int previous) throws Exception {
        readerControls(); click(label("reader_menu_toc"),false);
        waitText("Regression");
        String chapter=String.format(java.util.Locale.ROOT,"Regression %02d",destination);
        for (int tries=0;tries<15 && nodes(chapter).isEmpty();tries++) {
            boolean moved=false;
            for (AccessibilityNodeInfo node:nodes("")) if (node.isScrollable()) {
                moved=node.performAction(destination<previous?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if (moved) break;
            }
            SystemClock.sleep(moved ? 250 : 500);
        }
        click(chapter,false);
        // "Contents" is also the sheet tab label, so it cannot prove the sheet has closed.
        waitText(label("reader_menu_brightness"));
    }
    private void epubNavigation() throws Exception {
        launch(new Intent()); click(label("nav_shelf"),true);
        int transitions=0;
        for (boolean oneDocument:new boolean[]{false,true}) {
            File fixture=epubFixture(oneDocument); String title=fixture.getName().replace(".epub","");
            importFile(fixture,"application/epub+zip",title); click(title,false); enterPdfReader();
            long id=queryLong("SELECT id FROM books WHERE title='"+title+"'");
            require(queryLong("SELECT COUNT(*) FROM chapters WHERE bookId="+id)==12,"EPUB chapter segmentation changed");
            chooseEpubChapter(0,11);
            epubPage(id,0);
            android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot(); require(screenshot!=null,"No EPUB viewport");
            Rect viewport=new Rect(0,0,screenshot.getWidth(),screenshot.getHeight()); screenshot.recycle();
            hidePdfControls(viewport);
            for (int i=1;i<12;i++) { tap(viewport.width()*9/10,viewport.centerY()); epubPage(id,i); transitions++; }
            capture(oneDocument?"anchors-end":"spine-end");
            for (int i=10;i>=0;i--) { tap(viewport.width()/10,viewport.centerY()); epubPage(id,i); transitions++; }
            int previous=0;
            for (int destination:new int[]{4,5,8,7,3,2,10,9,6,1,0,11}) {
                chooseEpubChapter(destination,previous); epubPage(id,destination); previous=destination; transitions++;
            }
            capture(oneDocument?"anchors-toc-jump":"spine-toc-jump");
            for (int destination:new int[]{10,9,8,9,10,11}) {
                readerControls(); click(label(destination<previous?"reader_prev_chapter":"reader_next_chapter"),false);
                epubPage(id,destination); previous=destination; transitions++;
            }
            readerControls(); backToShelf(); click(title,false); enterPdfReader(); epubPage(id,11);
            capture(oneDocument?"anchors-resume":"spine-resume");
            readerControls(); backToShelf(); fixture.delete();
        }
        for (String resource:new String[]{"stats_title","ui_nav_notes","settings_title","nav_shelf"}) click(label(resource),true);
        Bundle measured=new Bundle(); measured.putInt("epubTransitions",transitions); measured.putInt("epubFixtures",2); sendStatus(1,measured);
    }
}
