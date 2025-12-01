package com.github.olga_yakovleva.rhvoice.android;

import android.content.Context;
import android.util.Log;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class LocalAddonStore {
    private static final String TAG = "RHVoice.LocalAddonStore";
    private static final String FILE_NAME = "local_addons.json";
    private static final Type LIST_TYPE = Types.newParameterizedType(List.class, LanguageResource.class);

    private LocalAddonStore() {
    }

    private static File getFile(Context context) {
        return new File(Config.getDir(context), FILE_NAME);
    }

    static List<LanguageResource> load(Context context, Moshi moshi) {
        final File file = getFile(context);
        if (!file.exists())
            return new ArrayList<>();
        JsonAdapter<List<LanguageResource>> adapter = moshi.adapter(LIST_TYPE).lenient().nonNull();
        try (okio.BufferedSource source = okio.Okio.buffer(okio.Okio.source(file))) {
            List<LanguageResource> langs = adapter.fromJson(source);
            if (langs == null)
                return new ArrayList<>();
            for (LanguageResource lr : langs)
                lr.index();
            return langs;
        } catch (IOException e) {
            if (BuildConfig.DEBUG)
                Log.w(TAG, "Failed to load local addons", e);
            return new ArrayList<>();
        }
    }

    static void saveLanguage(Context context, Moshi moshi, LanguageResource lang) {
        List<LanguageResource> langs = load(context, moshi);
        Iterator<LanguageResource> it = langs.iterator();
        while (it.hasNext()) {
            LanguageResource existing = it.next();
            if (existing.lang3code.equalsIgnoreCase(lang.lang3code)) {
                it.remove();
            }
        }
        lang.index();
        langs.add(lang);
        JsonAdapter<List<LanguageResource>> adapter = moshi.adapter(LIST_TYPE).lenient().nonNull();
        final File file = getFile(context);
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            adapter.toJson(writer, langs);
        } catch (IOException e) {
            if (BuildConfig.DEBUG)
                Log.w(TAG, "Failed to save local addons", e);
        }
    }
}
